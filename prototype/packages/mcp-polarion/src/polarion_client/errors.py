"""Errors safe to show to operators and language models. Never include the token."""


class PolarionError(Exception):
    """Base error for Polarion calls."""


class MissingCredentialsError(PolarionError):
    """POLARION_URL or POLARION_TOKEN is not configured."""


class PolarionAuthError(PolarionError):
    """Polarion rejected the personal access token."""


class PolarionUnavailableError(PolarionError):
    """Polarion could not be reached, or the REST API is disabled."""


class PolarionApiError(PolarionError):
    """Polarion returned an unexpected HTTP error."""

    def __init__(self, status_code: int, detail: str) -> None:
        self.status_code = status_code
        super().__init__(f"Polarion request failed ({status_code}): {detail}")
