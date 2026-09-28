"""HTTP client for the Polarion REST API."""

from __future__ import annotations

import re
from typing import Any

import httpx

from polarion_client.credentials import EnvCredentialProvider, PolarionCredentials
from polarion_client.errors import (
    PolarionApiError,
    PolarionAuthError,
    PolarionError,
    PolarionUnavailableError,
)
from polarion_client.models import CurrentUser

_BEARER = re.compile(r"Bearer\s+\S+", re.IGNORECASE)


def redact(text: str, token: str | None = None) -> str:
    """Remove bearer tokens from text that might be logged or returned."""
    cleaned = _BEARER.sub("Bearer [redacted]", text)
    if token:
        cleaned = cleaned.replace(token, "[redacted]")
    return cleaned


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
        creds = self._credentials.get()
        http = self._http or httpx.Client(timeout=self._timeout)
        owns_client = self._http is None
        try:
            try:
                response = http.get(
                    f"{creds.rest_root}/user",
                    headers=_headers(creds),
                )
            except httpx.RequestError as exc:
                raise PolarionUnavailableError(
                    f"Could not reach Polarion: {redact(str(exc), creds.token)}"
                ) from None
            _raise_for_status(response, creds.token)
            return _parse_current_user(response.json())
        finally:
            if owns_client:
                http.close()


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


def _parse_current_user(payload: Any) -> CurrentUser:
    if not isinstance(payload, dict):
        raise PolarionError("Polarion returned an unexpected user response.")
    data = payload.get("data")
    if not isinstance(data, dict):
        raise PolarionError("Polarion returned an unexpected user response.")
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
