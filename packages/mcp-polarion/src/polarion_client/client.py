"""HTTP client for the Polarion REST API."""

from __future__ import annotations

import os
import re
from typing import Any
from urllib.parse import quote

import httpx

from polarion_client.credentials import EnvCredentialProvider, PolarionCredentials
from polarion_client.errors import (
    PolarionApiError,
    PolarionAuthError,
    PolarionError,
    PolarionUnavailableError,
)
from polarion_client.models import (
    CreatedWorkItem,
    CreatedWorkItemLink,
    CurrentUser,
    DeletedWorkItemLink,
    LinkRole,
    Project,
    UpdatedWorkItem,
    WorkItem,
    WorkItemCreatePreview,
    WorkItemLink,
    WorkItemLinkDeletePreview,
    WorkItemLinkPreview,
    WorkItemUpdatePreview,
)

_BEARER = re.compile(r"Bearer\s+\S+", re.IGNORECASE)
_PROJECT_FIELDS = "id,name,description,active,trackerPrefix"
_WORK_ITEM_FIELDS = "id,title,type,status"


def redact(text: str, token: str | None = None) -> str:
    """Remove bearer tokens from text that might be logged or returned."""
    cleaned = _BEARER.sub("Bearer [redacted]", text)
    if token:
        cleaned = cleaned.replace(token, "[redacted]")
    return cleaned


def project_allowlist() -> frozenset[str]:
    raw = os.environ.get("POLARION_PROJECT_ALLOWLIST", "").strip()
    if not raw:
        return frozenset()
    return frozenset(part.strip() for part in raw.split(",") if part.strip())


class PolarionClient:
    """Calls Polarion as the user who owns the configured personal access token."""

    def __init__(
        self,
        credentials: EnvCredentialProvider | None = None,
        *,
        http: httpx.Client | None = None,
        timeout: float = 30.0,
    ) -> None:
        self._credentials = credentials or EnvCredentialProvider()
        self._http = http
        self._timeout = timeout

    def get_current_user(self) -> CurrentUser:
        """GET /user — the Polarion user for this token."""
        payload = self._request("GET", "/user")
        return _parse_current_user(payload)

    def list_projects(
        self,
        *,
        page_size: int = 100,
        page_number: int = 1,
        query: str | None = None,
    ) -> list[Project]:
        """GET /projects — projects this user can see."""
        params: dict[str, str | int] = {
            "fields[projects]": _PROJECT_FIELDS,
            "page[size]": page_size,
            "page[number]": page_number,
        }
        if query:
            params["query"] = query
        payload = self._request("GET", "/projects", params=params)
        projects = [_parse_project(item) for item in _list_data(payload, "project list")]
        allowed = project_allowlist()
        if allowed:
            projects = [project for project in projects if project.id in allowed]
        return projects

    def get_project(self, project_id: str) -> Project:
        """GET /projects/{projectId}."""
        _assert_project_allowed(project_id)
        encoded = quote(project_id, safe="")
        try:
            payload = self._request(
                "GET",
                f"/projects/{encoded}",
                params={"fields[projects]": _PROJECT_FIELDS},
            )
        except PolarionApiError as exc:
            if exc.status_code == 404:
                raise PolarionApiError(
                    404, f"Project not found: {project_id}"
                ) from None
            raise
        data = _single_data(payload, "project")
        return _parse_project(data)

    def create_work_item(
        self,
        project_id: str,
        wi_type: str,
        title: str,
        *,
        description: str | None = None,
        dry_run: bool = True,
    ) -> CreatedWorkItem | WorkItemCreatePreview:
        """POST /projects/{projectId}/workitems, or return the body when dry_run."""
        _assert_project_allowed(project_id)
        body = _work_item_create_body(wi_type, title, description)
        if dry_run:
            return WorkItemCreatePreview(project_id=project_id, body=body)
        encoded = quote(project_id, safe="")
        payload = self._request(
            "POST",
            f"/projects/{encoded}/workitems",
            json=body,
        )
        return _parse_created_work_item(payload)

    def get_work_item(self, project_id: str, work_item_id: str) -> WorkItem:
        """GET /projects/{projectId}/workitems/{workItemId}."""
        _assert_project_allowed(project_id)
        project = quote(project_id, safe="")
        item = quote(work_item_id, safe="")
        try:
            payload = self._request(
                "GET",
                f"/projects/{project}/workitems/{item}",
                params={"fields[workitems]": _WORK_ITEM_FIELDS},
            )
        except PolarionApiError as exc:
            if exc.status_code == 404:
                raise PolarionApiError(
                    404, f"Work item not found: {project_id}/{work_item_id}"
                ) from None
            raise
        return _parse_work_item(_single_data(payload, "work item"))

    def update_work_item(
        self,
        project_id: str,
        work_item_id: str,
        *,
        title: str | None = None,
        description: str | None = None,
        status: str | None = None,
        dry_run: bool = True,
    ) -> UpdatedWorkItem | WorkItemUpdatePreview:
        """PATCH /projects/{projectId}/workitems/{workItemId}, or preview when dry_run."""
        _assert_project_allowed(project_id)
        body = _work_item_update_body(
            project_id,
            work_item_id,
            title=title,
            description=description,
            status=status,
        )
        if dry_run:
            return WorkItemUpdatePreview(
                project_id=project_id,
                work_item_id=work_item_id,
                body=body,
            )
        project = quote(project_id, safe="")
        item = quote(work_item_id, safe="")
        try:
            self._request(
                "PATCH",
                f"/projects/{project}/workitems/{item}",
                json=body,
            )
        except PolarionApiError as exc:
            if exc.status_code == 404:
                raise PolarionApiError(
                    404, f"Work item not found: {project_id}/{work_item_id}"
                ) from None
            raise
        return UpdatedWorkItem(id=f"{project_id}/{work_item_id}")

    def list_work_items(
        self,
        project_id: str,
        *,
        query: str | None = None,
        page_size: int = 100,
        page_number: int = 1,
    ) -> list[WorkItem]:
        """GET /projects/{projectId}/workitems — Lucene query, for example type:requirement."""
        _assert_project_allowed(project_id)
        params: dict[str, str | int] = {
            "fields[workitems]": _WORK_ITEM_FIELDS,
            "page[size]": page_size,
            "page[number]": page_number,
        }
        if query:
            params["query"] = query
        encoded = quote(project_id, safe="")
        payload = self._request(
            "GET",
            f"/projects/{encoded}/workitems",
            params=params,
        )
        return [_parse_work_item(item) for item in _list_data(payload, "work item list")]

    def list_link_roles(self, project_id: str) -> list[LinkRole]:
        """GET /projects/{projectId}/enumerations/~/workitem-link-role/~."""
        _assert_project_allowed(project_id)
        encoded = quote(project_id, safe="")
        payload = self._request(
            "GET",
            f"/projects/{encoded}/enumerations/~/workitem-link-role/~",
        )
        return _parse_link_roles(payload)

    def list_work_item_links(
        self, project_id: str, work_item_id: str
    ) -> list[WorkItemLink]:
        """GET /projects/{projectId}/workitems/{workItemId}/linkedworkitems."""
        return self._list_work_item_links(project_id, work_item_id, backlinks=False)

    def list_work_item_backlinks(
        self, project_id: str, work_item_id: str
    ) -> list[WorkItemLink]:
        """GET /projects/{projectId}/workitems/{workItemId}/backlinkedworkitems."""
        return self._list_work_item_links(project_id, work_item_id, backlinks=True)

    def _list_work_item_links(
        self,
        project_id: str,
        work_item_id: str,
        *,
        backlinks: bool,
    ) -> list[WorkItemLink]:
        _assert_project_allowed(project_id)
        project = quote(project_id, safe="")
        item = quote(work_item_id, safe="")
        collection = "backlinkedworkitems" if backlinks else "linkedworkitems"
        kind = "backlink list" if backlinks else "link list"
        try:
            payload = self._request(
                "GET",
                f"/projects/{project}/workitems/{item}/{collection}",
                params={
                    "fields[linkedworkitems]": "role",
                    "fields[workitems]": _WORK_ITEM_FIELDS,
                    "include": "workItem",
                },
            )
        except PolarionApiError as exc:
            if exc.status_code == 404:
                raise PolarionApiError(
                    404, f"Work item not found: {project_id}/{work_item_id}"
                ) from None
            raise
        included = _included_work_items(payload)
        return [
            _parse_work_item_link(entry, included)
            for entry in _list_data(payload, kind)
        ]

    def create_work_item_link(
        self,
        project_id: str,
        work_item_id: str,
        target_work_item_id: str,
        role: str,
        *,
        target_project_id: str | None = None,
        dry_run: bool = True,
    ) -> CreatedWorkItemLink | WorkItemLinkPreview:
        """POST .../linkedworkitems, or return the body when dry_run."""
        _assert_project_allowed(project_id)
        target_project = target_project_id or project_id
        _assert_project_allowed(target_project)
        body = _work_item_link_create_body(target_project, target_work_item_id, role)
        if dry_run:
            return WorkItemLinkPreview(
                project_id=project_id,
                work_item_id=work_item_id,
                body=body,
            )
        project = quote(project_id, safe="")
        item = quote(work_item_id, safe="")
        try:
            payload = self._request(
                "POST",
                f"/projects/{project}/workitems/{item}/linkedworkitems",
                json=body,
            )
        except PolarionApiError as exc:
            if exc.status_code == 404:
                raise PolarionApiError(
                    404, f"Work item not found: {project_id}/{work_item_id}"
                ) from None
            raise
        return _parse_created_work_item_link(payload)

    def delete_work_item_link(
        self,
        project_id: str,
        work_item_id: str,
        target_work_item_id: str,
        role: str,
        *,
        target_project_id: str | None = None,
        dry_run: bool = True,
    ) -> DeletedWorkItemLink | WorkItemLinkDeletePreview:
        """DELETE one linkedworkitems resource, or preview the path when dry_run."""
        _assert_project_allowed(project_id)
        target_project = target_project_id or project_id
        _assert_project_allowed(target_project)
        path = (
            f"/projects/{quote(project_id, safe='')}/workitems/"
            f"{quote(work_item_id, safe='')}/linkedworkitems/"
            f"{quote(role, safe='')}/{quote(target_project, safe='')}/"
            f"{quote(target_work_item_id, safe='')}"
        )
        link_id = (
            f"{project_id}/{work_item_id}/{role}/{target_project}/{target_work_item_id}"
        )
        if dry_run:
            return WorkItemLinkDeletePreview(
                project_id=project_id,
                work_item_id=work_item_id,
                role=role,
                target_project_id=target_project,
                target_work_item_id=target_work_item_id,
                path=path,
            )
        try:
            self._request("DELETE", path)
        except PolarionApiError as exc:
            if exc.status_code == 404:
                raise PolarionApiError(404, f"Link not found: {link_id}") from None
            raise
        return DeletedWorkItemLink(id=link_id)

    def _request(
        self,
        method: str,
        path: str,
        *,
        json: Any | None = None,
        params: dict[str, str | int] | None = None,
    ) -> Any:
        creds = self._credentials.get()
        http = self._http or httpx.Client(timeout=self._timeout)
        owns_client = self._http is None
        url = f"{creds.rest_root}{path}"
        headers = _headers(creds)
        if json is not None:
            headers["Content-Type"] = "application/json"
        try:
            try:
                response = http.request(
                    method,
                    url,
                    headers=headers,
                    json=json,
                    params=params,
                )
            except httpx.RequestError as exc:
                raise PolarionUnavailableError(
                    f"Could not reach Polarion: {redact(str(exc), creds.token)}"
                ) from None
            _raise_for_status(response, creds.token)
            if response.status_code == 204 or not response.content:
                return None
            return response.json()
        finally:
            if owns_client:
                http.close()


def _assert_project_allowed(project_id: str) -> None:
    allowed = project_allowlist()
    if allowed and project_id not in allowed:
        raise PolarionApiError(
            403,
            f"Project {project_id} is not in POLARION_PROJECT_ALLOWLIST.",
        )


def _headers(creds: PolarionCredentials) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {creds.token}",
        "Accept": "application/json",
    }


def _raise_for_status(response: httpx.Response, token: str) -> None:
    if response.status_code < 400:
        return
    if response.status_code == 401:
        raise PolarionAuthError(
            "Polarion rejected the token. Create a personal access token in "
            "Polarion and set POLARION_TOKEN."
        )
    if response.status_code == 503:
        raise PolarionUnavailableError(
            "Polarion REST API is not enabled on this instance."
        )
    detail = redact(_error_detail(response), token)
    raise PolarionApiError(response.status_code, detail)


def _error_detail(response: httpx.Response) -> str:
    try:
        body = response.json()
    except ValueError:
        text = response.text.strip()
        return text or response.reason_phrase
    errors = body.get("errors") if isinstance(body, dict) else None
    if isinstance(errors, list) and errors:
        first = errors[0]
        if isinstance(first, dict):
            detail = first.get("detail") or first.get("title")
            if isinstance(detail, str) and detail.strip():
                return detail.strip()
    return response.reason_phrase


def _work_item_create_body(
    wi_type: str, title: str, description: str | None
) -> dict[str, Any]:
    attributes: dict[str, Any] = {"type": wi_type, "title": title}
    if description:
        attributes["description"] = {"type": "text/html", "value": description}
    return {"data": [{"type": "workitems", "attributes": attributes}]}


def _work_item_update_body(
    project_id: str,
    work_item_id: str,
    *,
    title: str | None,
    description: str | None,
    status: str | None,
) -> dict[str, Any]:
    attributes: dict[str, Any] = {}
    if title is not None:
        attributes["title"] = title
    if description is not None:
        attributes["description"] = {"type": "text/html", "value": description}
    if status is not None:
        attributes["status"] = status
    if not attributes:
        raise PolarionError(
            "Provide at least one of title, description, or status to update."
        )
    return {
        "data": {
            "type": "workitems",
            "id": f"{project_id}/{work_item_id}",
            "attributes": attributes,
        }
    }


def _work_item_link_create_body(
    target_project_id: str, target_work_item_id: str, role: str
) -> dict[str, Any]:
    return {
        "data": [
            {
                "type": "linkedworkitems",
                "attributes": {"role": role},
                "relationships": {
                    "workItem": {
                        "data": {
                            "type": "workitems",
                            "id": f"{target_project_id}/{target_work_item_id}",
                        }
                    }
                },
            }
        ]
    }


def _list_data(payload: Any, kind: str) -> list[Any]:
    if not isinstance(payload, dict):
        raise PolarionError(f"Polarion returned an unexpected {kind} response.")
    data = payload.get("data")
    if not isinstance(data, list):
        raise PolarionError(f"Polarion returned an unexpected {kind} response.")
    return data


def _single_data(payload: Any, kind: str) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise PolarionError(f"Polarion returned an unexpected {kind} response.")
    data = payload.get("data")
    if not isinstance(data, dict):
        raise PolarionError(f"Polarion returned an unexpected {kind} response.")
    return data


def _text_value(value: Any) -> str | None:
    if isinstance(value, str) and value:
        return value
    if isinstance(value, dict):
        inner = value.get("value")
        if isinstance(inner, str) and inner:
            return inner
    return None


def _parse_current_user(payload: Any) -> CurrentUser:
    data = _single_data(payload, "user")
    user_id = data.get("id")
    if not isinstance(user_id, str) or not user_id:
        raise PolarionError("Polarion returned a user response without an id.")
    attributes = data.get("attributes")
    name = email = None
    if isinstance(attributes, dict):
        raw_name = attributes.get("name")
        raw_email = attributes.get("email")
        if isinstance(raw_name, str) and raw_name:
            name = raw_name
        if isinstance(raw_email, str) and raw_email:
            email = raw_email
    return CurrentUser(id=user_id, name=name, email=email)


def _parse_project(item: Any) -> Project:
    if not isinstance(item, dict):
        raise PolarionError("Polarion returned an unexpected project response.")
    project_id = item.get("id")
    if not isinstance(project_id, str) or not project_id:
        raise PolarionError("Polarion returned a project without an id.")
    attributes = item.get("attributes") if isinstance(item.get("attributes"), dict) else {}
    active = attributes.get("active")
    return Project(
        id=project_id,
        name=_text_value(attributes.get("name")),
        description=_text_value(attributes.get("description")),
        active=active if isinstance(active, bool) else None,
        tracker_prefix=_text_value(attributes.get("trackerPrefix")),
    )


def _parse_work_item(item: Any) -> WorkItem:
    if not isinstance(item, dict):
        raise PolarionError("Polarion returned an unexpected work item response.")
    item_id = item.get("id")
    if not isinstance(item_id, str) or not item_id:
        raise PolarionError("Polarion returned a work item without an id.")
    attributes = item.get("attributes") if isinstance(item.get("attributes"), dict) else {}
    return WorkItem(
        id=item_id,
        title=_text_value(attributes.get("title")),
        type=_text_value(attributes.get("type")),
        status=_text_value(attributes.get("status")),
    )


def _parse_created_work_item(payload: Any) -> CreatedWorkItem:
    data = payload.get("data") if isinstance(payload, dict) else None
    item: Any = None
    if isinstance(data, list) and data:
        item = data[0]
    elif isinstance(data, dict):
        item = data
    if not isinstance(item, dict):
        raise PolarionError("Polarion returned an unexpected create-work-item response.")
    item_id = item.get("id")
    if not isinstance(item_id, str) or not item_id:
        raise PolarionError("Polarion create response had no work item id.")
    links = item.get("links") if isinstance(item.get("links"), dict) else {}
    portal = links.get("portal")
    return CreatedWorkItem(
        id=item_id,
        portal_url=portal if isinstance(portal, str) and portal else None,
    )


def _parse_link_roles(payload: Any) -> list[LinkRole]:
    data = _single_data(payload, "link role enumeration")
    attributes = data.get("attributes") if isinstance(data.get("attributes"), dict) else {}
    options = attributes.get("options")
    if not isinstance(options, list):
        raise PolarionError("Polarion returned an unexpected link-role response.")
    roles: list[LinkRole] = []
    for option in options:
        if not isinstance(option, dict):
            continue
        role_id = option.get("id")
        if not isinstance(role_id, str) or not role_id:
            continue
        name = option.get("name")
        opposite = option.get("oppositeName")
        raw_rules = option.get("linkRules")
        rules: list[dict[str, Any]] | None = None
        if isinstance(raw_rules, list):
            rules = [rule for rule in raw_rules if isinstance(rule, dict)]
        roles.append(
            LinkRole(
                id=role_id,
                name=name if isinstance(name, str) and name else None,
                opposite_name=opposite if isinstance(opposite, str) and opposite else None,
                link_rules=rules,
            )
        )
    return roles


def _included_work_items(payload: Any) -> dict[str, WorkItem]:
    if not isinstance(payload, dict):
        return {}
    included = payload.get("included")
    if not isinstance(included, list):
        return {}
    result: dict[str, WorkItem] = {}
    for item in included:
        if not isinstance(item, dict) or item.get("type") != "workitems":
            continue
        parsed = _parse_work_item(item)
        result[parsed.id] = parsed
    return result


def _relationship_work_item_id(item: dict[str, Any]) -> str | None:
    relationships = item.get("relationships")
    if not isinstance(relationships, dict):
        return None
    work_item = relationships.get("workItem")
    if not isinstance(work_item, dict):
        return None
    data = work_item.get("data")
    if not isinstance(data, dict):
        return None
    target_id = data.get("id")
    if isinstance(target_id, str) and target_id:
        return target_id
    return None


def _parse_work_item_link(
    item: Any, included: dict[str, WorkItem]
) -> WorkItemLink:
    if not isinstance(item, dict):
        raise PolarionError("Polarion returned an unexpected work item link.")
    link_id = item.get("id")
    if not isinstance(link_id, str) or not link_id:
        raise PolarionError("Polarion returned a work item link without an id.")
    attributes = item.get("attributes") if isinstance(item.get("attributes"), dict) else {}
    role = _text_value(attributes.get("role"))
    target_id = _relationship_work_item_id(item)
    target_title = None
    if target_id and target_id in included:
        target_title = included[target_id].title
    return WorkItemLink(
        id=link_id,
        role=role,
        target_id=target_id,
        target_title=target_title,
    )


def _parse_created_work_item_link(payload: Any) -> CreatedWorkItemLink:
    data = payload.get("data") if isinstance(payload, dict) else None
    item: Any = None
    if isinstance(data, list) and data:
        item = data[0]
    elif isinstance(data, dict):
        item = data
    if not isinstance(item, dict):
        raise PolarionError("Polarion returned an unexpected create-link response.")
    item_id = item.get("id")
    if not isinstance(item_id, str) or not item_id:
        raise PolarionError("Polarion create-link response had no id.")
    return CreatedWorkItemLink(id=item_id)
