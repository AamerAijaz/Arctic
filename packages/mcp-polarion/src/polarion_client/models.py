"""Current Polarion user, as returned by GET /user."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class CurrentUser:
    id: str
    name: str | None = None
    email: str | None = None

    def to_dict(self) -> dict[str, str | None]:
        return {"id": self.id, "name": self.name, "email": self.email}
