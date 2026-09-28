"""arctic whoami — show the Polarion user for the configured token."""

from __future__ import annotations

import argparse
import sys

from polarion_client.client import PolarionClient
from polarion_client.credentials import EnvCredentialProvider
from polarion_client.errors import MissingCredentialsError, PolarionError


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="arctic")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("whoami", help="Show the Polarion user for the configured token")
    return parser


def run_whoami(client: PolarionClient, *, out: object | None = None) -> int:
    stream = out or sys.stdout
    try:
        user = client.get_current_user()
    except MissingCredentialsError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    except PolarionError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print(f"id: {user.id}", file=stream)
    if user.name:
        print(f"name: {user.name}", file=stream)
    if user.email:
        print(f"email: {user.email}", file=stream)
    return 0


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "whoami":
        return run_whoami(PolarionClient(EnvCredentialProvider()))
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
