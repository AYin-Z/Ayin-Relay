"""Exercise the release-to-deployment boundary without configuring a host."""

from __future__ import annotations

from hashlib import sha256
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile


SPEC = importlib.util.spec_from_file_location(
    "prepare_windows_connector_deployment",
    Path(__file__).resolve().parents[1] / "prepare-windows-connector-deployment.py",
)
assert SPEC is not None and SPEC.loader is not None
handoff = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(handoff)

SOURCE = "a" * 40
PUBLISHER = "b" * 64
DEVICE = "4c960e6a-941f-41ec-a3bc-88053e263d84"
ORIGIN = "https://admin.example.test"
RELAY = "wss://relay.example.test/ws"
VERSION = "1.2.0"


class DeploymentHandoffTests(unittest.TestCase):
    def setUp(self) -> None:
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.release = self.root / "signed"
        self.release.mkdir()
        self.output = self.root / "candidate"
        self.base_zip = self.root / "existing.zip"
        with ZipFile(self.base_zip, "w") as archive:
            for name in ("Install.cmd", "install.ps1", "hermes-relay.exe", "manifest.json"):
                archive.writestr(name, "fixture")
        self.policy = self.root / "portal.json"
        handoff._write_private(self.policy, {
            "public_origin": ORIGIN, "relay_url": RELAY,
            "access_issuer": "https://fixture.cloudflareaccess.com", "access_audience": "fixture",
            "base_zip_path": str(self.base_zip), "downloads_per_window": 4,
        })
        self.original_policy = self.policy.read_bytes()
        self.node = b"MZ" + bytes(58) + (64).to_bytes(4, "little") + b"PE\0\0\x64\x86fixture"
        self.wrapper = b"MZ signed wrapper"
        self.node_digest = sha256(self.node).hexdigest()
        self.endpoints = {"portal": f"{ORIGIN}/relay/private/enroll", "relay_url": RELAY}
        self.embedded = {"source_sha": SOURCE, "version": VERSION, "sha256": self.node_digest}
        self.embedded_node = self.node
        self.signatures_checked: list[str] = []
        self.manifest = {
            "source_sha": SOURCE, "signer_sha256": PUBLISHER, "version": VERSION,
            "node": self.artifact(handoff.NODE_FILENAME, self.node),
            "installer": self.artifact(handoff.INSTALLER_FILENAME, self.wrapper),
        }
        (self.release / "release.json").write_text(json.dumps(self.manifest))
        self.enterContext(patch.object(handoff.subprocess, "run", side_effect=self.fixture_tool))

    def artifact(self, name: str, data: bytes) -> dict:
        (self.release / name).write_bytes(data)
        return {"filename": name, "size": len(data), "sha256": sha256(data).hexdigest()}

    def fixture_tool(self, command, **options):
        self.assertTrue(options["check"])
        if command[0] == "osslsigncode":
            self.assertIn(f"sha256:{PUBLISHER}", command)
            self.assertIn("-TSA-CAfile", command)
            self.signatures_checked.append(Path(command[3]).name)
            return subprocess.CompletedProcess(command, 0, stdout=b"Timestamp Server Signature verification: ok\n")
        elif command[0] == "7z":
            self.assertEqual(len(self.signatures_checked), 2)
            destination = Path(command[3][2:])
            (destination / "portal.json").write_text(json.dumps(self.endpoints))
            (destination / "manifest.json").write_text(json.dumps(self.embedded))
            (destination / "hermes-relay.exe").write_bytes(self.embedded_node)
        else:
            self.fail(f"unexpected tool {command[0]}")

    def run_handoff(self, **overrides):
        handoff.prepare(**{
            "release_dir": self.release, "portal_config": self.policy,
            "source": SOURCE, "publisher": PUBLISHER, "trust": self.root / "trust.pem",
            "timestamp_trust": self.root / "timestamps.pem", "devices": [DEVICE],
            "output": self.output, **overrides,
        })

    def test_stages_both_configs_and_keeps_existing_policy(self) -> None:
        self.run_handoff()
        self.assertEqual(self.output.stat().st_mode & 0o777, 0o700)
        self.assertEqual({path.name for path in self.output.iterdir()},
                         {"portal.json", "windows-release-offer.json"})
        for path in self.output.iterdir():
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        staged = handoff.PortalConfig.from_file(self.output / "portal.json")
        self.assertTrue(staged.browser_enrollment_enabled)
        self.assertEqual(staged.downloads_per_window, 4)
        self.assertEqual(staged.base_zip_path, self.base_zip)
        self.assertEqual(staged.installer_exe.sha256, self.manifest["installer"]["sha256"])
        offer = json.loads((self.output / "windows-release-offer.json").read_bytes())
        self.assertEqual(offer["artifact"]["sha256"], self.node_digest)
        self.assertEqual(offer["device_ids"], [DEVICE])
        self.assertEqual(self.policy.read_bytes(), self.original_policy)
        with self.assertRaises(FileExistsError):
            self.run_handoff()

    def test_mismatched_origin_or_node_rejects_bundle(self) -> None:
        self.endpoints["relay_url"] = "wss://other.example.test/ws"
        with self.assertRaisesRegex(ValueError, "enrollment endpoints"):
            self.run_handoff()
        self.endpoints["relay_url"] = RELAY
        self.embedded_node = self.node[:-1] + b"x"
        self.signatures_checked.clear()
        with self.assertRaisesRegex(ValueError, "payload differs"):
            self.run_handoff()
        self.assertFalse(self.output.exists())

    def test_mismatched_embedded_source_and_unapproved_inputs_leave_no_output(self) -> None:
        with self.assertRaisesRegex(ValueError, "approved commit and publisher"):
            self.run_handoff(publisher="c"*64)
        self.assertFalse(self.signatures_checked)
        self.embedded["source_sha"] = "f"*40
        with self.assertRaisesRegex(ValueError, "manifest differs"):
            self.run_handoff()
        self.assertFalse(self.output.exists())
        self.assertEqual(self.policy.read_bytes(), self.original_policy)


if __name__ == "__main__":
    unittest.main()
