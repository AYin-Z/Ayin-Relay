#!/usr/bin/env python3
"""Validate the Windows and historical Android privacy policy."""

from __future__ import annotations

import argparse
import html
import sys
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
POLICY_PATH = "user-docs/privacy.md"
CANONICAL_URL = f"https://github.com/AYin-Z/Ayin-Relay/blob/main/{POLICY_PATH}"
REQUIRED_MARKERS = (
    "Privacy Policy",
    "Windows nodes",
    "Google Play Track",
    "Data Storage",
    "Network Connections",
    "Data Export & Deletion",
    "Children's Privacy",
    "Voice Overlay",
    "Android Digital Assistant",
    "Stop voice",
)


def validate_content(label: str, content: str) -> None:
    text = html.unescape(content).casefold()
    missing = [marker for marker in REQUIRED_MARKERS if marker.casefold() not in text]
    if missing:
        raise ValueError(f"{label} is missing: {', '.join(missing)}")
    for obsolete in ("cannot read your screen", "does not collect, transmit, or share personal data"):
        if obsolete in text:
            raise ValueError(f"{label} has an obsolete blanket privacy claim: {obsolete}")


def validate_repository() -> None:
    validate_content(POLICY_PATH, (ROOT / POLICY_PATH).read_text(encoding="utf-8"))
    website_link = ROOT / "website/public/privacy.html"
    if CANONICAL_URL not in website_link.read_text(encoding="utf-8"):
        raise ValueError(f"{website_link.relative_to(ROOT)} must link to {CANONICAL_URL}")


def validate_published_policy() -> None:
    request = urllib.request.Request(
        CANONICAL_URL,
        headers={"User-Agent": "Ayin-Relay-release-check/1"},
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        validate_content(CANONICAL_URL, response.read().decode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--live", action="store_true", help="also verify the public policy")
    args = parser.parse_args()
    try:
        validate_repository()
        if args.live:
            validate_published_policy()
    except (ValueError, OSError) as error:
        print(f"privacy policy validation failed: {error}", file=sys.stderr)
        return 1
    print("Privacy policy verified." if args.live else "Privacy policy source and links verified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
