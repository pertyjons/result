"""Reject mismatched tags, branch dispatches and snapshot versions before publishing."""
import os
from pathlib import Path
import re
import sys


def validate(properties: str, tag: str, ref_type: str) -> str:
    versions = re.findall(r"^\s*version\s*=\s*(\S+)\s*$", properties, re.MULTILINE)
    if len(versions) != 1:
        raise ValueError("gradle.properties must contain exactly one non-empty version")
    version = versions[0]
    if "SNAPSHOT" in version.upper():
        raise ValueError("A release version must not contain SNAPSHOT")
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?", version):
        raise ValueError("A release version must have the form major.minor.patch[-prerelease][+build]")
    if ref_type != "tag":
        raise ValueError("Publishing requires a tag; select a tag when dispatching manually")
    if tag != f"v{version}":
        raise ValueError(f"Release tag must be v{version}, got {tag!r}")
    return version


def main() -> int:
    try:
        version = validate(
            Path("gradle.properties").read_text(encoding="utf-8"),
            os.environ.get("RELEASE_TAG", ""),
            os.environ.get("RELEASE_REF_TYPE", ""),
        )
    except (ValueError, OSError) as error:
        print(f"Release validation failed: {error}", file=sys.stderr)
        return 1
    print(f"Validated release v{version}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
