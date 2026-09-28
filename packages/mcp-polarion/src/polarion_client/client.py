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
    CurrentUser,
    Project,
    WorkItem,
    WorkItemCreatePreview,
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
