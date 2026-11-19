"""Verify a signed release bundle and stage matching Ayin-Relay configurations."""

from __future__ import annotations

import argparse
from dataclasses import asdict, replace
from hashlib import file_digest
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
from typing import Any
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from plugin.connector_portal import PortalConfig, validate_template
from plugin.installer_release import InstallerRelease
from plugin.relay.desktop_rollout import parse_preflight
from plugin.relay.node_release import NODE_FILENAME, load_published_node_release


INSTALLER_FILENAME = "Ayin-Relay-Setup-x64.exe"


def _read_object(path: Path) -> dict[str, Any]:
    # Release manifests are public metadata. They may be copied from Windows
    # without Unix permission bits, but must still be bounded regular files.
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or not 0 < info.st_size <= 8192:
            raise ValueError(f"invalid metadata file: {path.name}")
        raw = source.read(8193)
    if len(raw) > 8192:
        raise ValueError(f"metadata file exceeds its limit: {path.name}")
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValueError(f"expected a JSON object: {path.name}")
    return value


def _write_private(path: Path, value: dict[str, Any]) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        json.dump(value, output, default=str, indent=2)
        output.write("\n")


def _artifact(
    release_dir: Path, value: object, filename: str, publisher: str,
    trust: Path, timestamp_trust: Path | None,
) -> tuple[InstallerRelease, int]:
    if (not isinstance(value, dict) or set(value) != {"filename", "sha256", "size"}
            or value["filename"] != filename or type(value["size"]) is not int
            or not 0 < value["size"] <= 128 * 1024 * 1024):
        raise ValueError(f"invalid manifest entry for {filename}")
    return InstallerRelease(release_dir / filename, value["sha256"], publisher, trust, timestamp_trust), value["size"]


def _check_embedded_release(
    extractor: str, signed_installer: Path, directory: Path,
    policy: PortalConfig, source: str, version: str, node_digest: str, node_size: int,
) -> None:
    directory.mkdir()
    names = ("portal.json", "manifest.json", "hermes-relay.exe")
    subprocess.run(
        [extractor, "e", "-y", f"-o{directory}", str(signed_installer),
         *(f"$PLUGINSDIR/{name}" for name in names)],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True, timeout=45,
    )
    endpoints = {
        "portal": f"{policy.public_origin}{policy.prefix}/private/enroll",
        "relay_url": policy.relay_url,
    }
    if _read_object(directory / "portal.json") != endpoints:
        raise ValueError("signed installer enrollment endpoints differ from this portal policy")
    if _read_object(directory / "manifest.json") != {"source_sha": source, "version": version, "sha256": node_digest}:
        raise ValueError("signed installer manifest differs from the approved release")
    descriptor = os.open(directory / "hermes-relay.exe", os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as executable:
        info = os.fstat(executable.fileno())
        if (not stat.S_ISREG(info.st_mode) or info.st_size != node_size
                or file_digest(executable, "sha256").hexdigest() != node_digest):
            raise ValueError("signed installer payload differs from the approved update executable")


def prepare(
    *,
    release_dir: Path,
    portal_config: Path,
    source: str,
    publisher: str,
    trust: Path,
    timestamp_trust: Path | None,
    devices: list[str],
    output: Path,
    extractor: str = "7z",
) -> None:
    if not re.fullmatch(r"[0-9a-f]{40}", source) or not re.fullmatch(r"[0-9a-f]{64}", publisher):
        raise ValueError("supply the independently approved commit SHA and publisher SHA-256")
    output, release_dir = output.absolute(), release_dir.absolute()
    if output.exists() or output.is_symlink():
        raise FileExistsError(f"deployment output already exists: {output}")
    manifest = _read_object(release_dir / "release.json")
    if (set(manifest) != {"source_sha", "version", "signer_sha256", "node", "installer"}
            or manifest["source_sha"] != source or manifest["signer_sha256"] != publisher):
        raise ValueError("release manifest does not match the approved commit and publisher")
    version, _, devices = parse_preflight({"version": manifest["version"], "device_ids": devices})
    policy = PortalConfig.from_file(portal_config.absolute())
    validate_template(policy.base_zip_path)
    node_artifact, node_size = _artifact(release_dir, manifest["node"], NODE_FILENAME,
                                         publisher, trust.absolute(), timestamp_trust)
    installer, installer_size = _artifact(release_dir, manifest["installer"], INSTALLER_FILENAME,
                                           publisher, trust.absolute(), timestamp_trust)
    with tempfile.TemporaryDirectory(prefix=".connector-deployment-", dir=output.parent) as temporary:
        root = Path(temporary)
        configs = root / "configs"
        configs.mkdir(mode=0o700)
        offer = configs / "windows-release-offer.json"
        _write_private(offer, {"version": version, "device_ids": devices, "artifact": asdict(node_artifact)})
        node = load_published_node_release(offer, root)
        installer_snapshot = installer.snapshot(root)
        if node.size != node_size or installer_snapshot.stat().st_size != installer_size:
            raise ValueError("signed file size does not match the release manifest")
        _check_embedded_release(extractor, installer_snapshot, root / "payload",
                                policy, source, version, node.sha256, node.size)
        _write_private(configs / "portal.json", asdict(
            replace(policy, browser_enrollment_enabled=True, installer_exe=installer)
        ))
        if output.exists() or output.is_symlink():
            raise FileExistsError(f"deployment output already exists: {output}")
        os.rename(configs, output)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-dir", type=Path, required=True)
    parser.add_argument("--portal-config", type=Path, required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--publisher-sha256", required=True)
    parser.add_argument("--trust-bundle", type=Path, required=True)
    parser.add_argument("--timestamp-trust-bundle", type=Path)
    parser.add_argument("--device-id", action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--7z", dest="extractor", default="7z")
    args = parser.parse_args()
    try:
        prepare(release_dir=args.release_dir, portal_config=args.portal_config,
                source=args.source_sha, publisher=args.publisher_sha256, trust=args.trust_bundle,
                timestamp_trust=args.timestamp_trust_bundle.absolute() if args.timestamp_trust_bundle else None,
                devices=args.device_id, output=args.output, extractor=args.extractor)
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Could not prepare the connector deployment: {error}\n")
    print(f"Verified connector configurations staged in {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
