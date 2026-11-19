"""Check the installed Ayin-Relay distribution from an isolated working directory."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from importlib.metadata import version
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
from typing import Iterator
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, build_opener
from zipfile import ZipFile


@contextmanager
def _service(entrypoint: str, args: list[str], root: Path, env: dict[str, str]) -> Iterator[subprocess.Popen]:
    executable = Path(sys.executable).absolute().parent / entrypoint
    with (root / f"{entrypoint}.log").open("w+b") as log:
        child = subprocess.Popen([str(executable), *args], cwd=root, env=env, stdout=log, stderr=log)
        try:
            yield child
        except Exception:
            log.seek(0, os.SEEK_END)
            log.seek(max(0, log.tell() - 4096))
            print(log.read().decode("utf-8", "replace"), file=sys.stderr, end="")
            raise
        finally:
            if child.poll() is None:
                child.terminate()
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)


def _loopback_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def _request(port: int, path: str) -> tuple[int, bytes]:
    client = build_opener(ProxyHandler({}))
    try:
        with client.open(f"http://127.0.0.1:{port}{path}", timeout=1) as result:
            return result.status, result.read(65536)
    except HTTPError as error:
        with error:
            return error.code, error.read(65536)


def _ready(child: subprocess.Popen, port: int, path: str) -> bytes:
    limit = time.monotonic() + 15
    while child.poll() is None and time.monotonic() < limit:
        try:
            status, body = _request(port, path)
        except (URLError, TimeoutError, ConnectionError):
            time.sleep(0.1)
            continue
        if status != 200:
            raise RuntimeError(f"{path} returned HTTP {status}")
        return body
    raise RuntimeError(f"{path} did not become ready, process exit code {child.poll()}")


def smoke(expected_version: str) -> None:
    if version("hermes-relay") != expected_version:
        raise RuntimeError("Installed distribution does not match the release version")
    with tempfile.TemporaryDirectory(prefix="ayin-relay-distribution-") as directory:
        root = Path(directory)
        env = {key: value for key, value in os.environ.items()
               if not key.startswith(("RELAY_", "HERMES_", "AYIN_", "PYTHON"))}
        env.update(HERMES_HOME=str(root / "profile"), RELAY_PROFILE_DISCOVERY_ENABLED="0",
                   RELAY_SESSIONS_FILE=str(root / "sessions.json"))
        relay_port = _loopback_port()
        with _service("ayin-relay-server", ["--host", "127.0.0.1", "--port", str(relay_port), "--no-ssl"], root, env) as relay:
            health = json.loads(_ready(relay, relay_port, "/health"))
            if (health.get("status"), health.get("version"), health.get("sessions")) != ("ok", expected_version, 0):
                raise RuntimeError("Installed server did not report the expected fresh health state")
            if _request(relay_port, "/desktop/rollout/assignment")[0] != 404:
                raise RuntimeError("An unconfigured fresh server must not offer Windows updates")

        archive = root / "connector.zip"
        with ZipFile(archive, "w") as template:
            for path in ("Install.cmd", "install.ps1", "hermes-relay.exe", "manifest.json"):
                template.writestr(path, "isolated smoke fixture")
        config = root / "portal.json"
        config.write_text(json.dumps({
            "public_origin": "https://admin.example.test", "relay_url": "wss://relay.example.test/ws",
            "access_issuer": "https://fixture.cloudflareaccess.com", "access_audience": "smoke",
            "base_zip_path": str(archive),
        }), encoding="utf-8")
        config.chmod(0o600)
        portal_port = _loopback_port()
        with _service("ayin-relay-download", ["--config", str(config), "--port", str(portal_port)], root, env) as portal:
            if b"/relay/private/" not in _ready(portal, portal_port, "/relay/"):
                raise RuntimeError("Installed download portal is missing its protected entry point")
            if _request(portal_port, "/relay/private/")[0] != 401:
                raise RuntimeError("Installed download portal did not reject anonymous access")
        legacy = subprocess.check_output(
            [sys.executable, "-I", "-m", "relay_server", "--version"], cwd=root, env=env, text=True,
        ).strip()
        if legacy != f"hermes-relay {expected_version}":
            raise RuntimeError("Legacy module entry point does not match the release version")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-version", required=True)
    smoke(parser.parse_args().expected_version)
    print("Installed release passed server, protected portal and compatibility entry point checks.")
