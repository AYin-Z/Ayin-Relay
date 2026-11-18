# Ayin-Relay architecture

AyinServer uses Ayin-Relay to enroll and operate Windows nodes in the Ayin system. The Ayin task layer owns scheduling and task state. Relay maintains device sessions, routes an authorized operation to a specific node and returns its result. The local Windows user controls what that connector can access.

## Components

```text
Windows browser --> authenticated download portal --> Windows installer
                                                           |
Ayin task layer --> AyinServer Relay <-- authenticated WSS -- Windows node
                         |
                 Node status and receipts
```

- **Portal.** An authenticated operator downloads a Windows package with a short-lived, single-use enrollment invitation. The portal issues invitations through a restricted server interface. See the [download portal contract](connector-downloads.md).
- **Relay service.** The node establishes the outbound WSS connection. The service exchanges the invitation for a revocable device authorization, tracks connected nodes and routes desktop calls to the selected node ID. Health and command receipts support diagnosis. The desktop operator interfaces used on AyinServer are loopback-only.
- **Node.** The installer and daemon are under [`desktop/`](../desktop/). The daemon retains the existing node identity and authorization across restarts and maintains the connection. The node enforces its local tool policy for file operations, commands, desktop input and device capabilities. Windows account, session and device permissions also apply.

## Current Windows release path

The private portal serves a ZIP with a Windows executable, installation scripts and a manifest. The install flow gives the user one capability review. It uses a per-user Windows Scheduled Task that starts at sign-in and recovers the process. The task runs with limited rights. A legacy task migration can require a Windows elevation prompt. The sign-in screen and UAC secure desktop remain Windows-controlled.

The [installer guide](../desktop/connector/README.md) details pause, restart, local recovery and migration. The desktop CLI has a local update command. AyinServer can preview selected installations and offer a verified update package for them. The node still applies updates through the local update command.

### Rollout preflight

`POST /desktop/rollout/preflight` accepts a target Windows release version and an explicit list of up to 64 installation UUIDs from the direct loopback operator. Ayin system membership comes from the operator's selection. The endpoint matches each UUID to an active desktop-only authorization and an authenticated desktop socket. It treats reports older than 90 seconds as stale and keeps offline, duplicate, incompatible and older-protocol installations distinct.

`update_candidate` means the node reports Windows x64, an older version and the current local update protocol. `reported_at_target` is a status observation. Release signature checks and a post-update health check provide separate evidence before a rollout can be accepted.

### Signed update packages

Set `RELAY_WINDOWS_RELEASE_OFFER` to an absolute path to a JSON file readable only by its owner, the Relay service user or root. The file selects one release and up to 64 enrolled Windows installation UUIDs. A release has the following structure.

```json
{
  "version": "2.0.0",
  "device_ids": ["d42314e1-3858-4fc4-b2bd-92a41df48ed2"],
  "artifact": {
    "path": "/srv/relay/releases/hermes-relay-win-x64.exe",
    "sha256": "<64 lowercase hex characters of the file SHA-256>",
    "signer_sha256": "<64 lowercase hex characters of the signing certificate SHA-256>",
    "trust_bundle": "/srv/relay/trust/publisher-ca.pem"
  }
}
```

Use the signed Windows x64 **node executable** as the artifact. For a timestamped release, `artifact.timestamp_trust_bundle` can specify the timestamp authority's CA bundle. On startup Relay snapshots the file and runs offline Authenticode verification with `osslsigncode`. If verification fails, Relay starts without offering the update. Change or withdraw the offer by updating the configuration and restarting the service.

Only a device with a current desktop-only token and a UUID in the offer can download that exact version and SHA-256. Route the offer and download through HTTPS, with a same-host loopback connection from the TLS proxy to Relay. The Windows updater must independently verify the package before applying it. The download interface does not initiate installation.

## Target Windows release path

| Capability | Acceptance condition |
| --- | --- |
| Single-file install | The authenticated download serves one Authenticode-signed Windows EXE. Signature and publisher can be verified before launch. Opening it displays the enrollment destination and the requested device capabilities. |
| One-time elevation | Windows requests UAC once for setup operations that need it. Enrollment binds to the intended Windows user, including when Windows uses a different administrator account for elevation. The running connector uses limited rights, and local stop and uninstall remain available. |
| Enrollment | The signed binary stays the same for every download. The browser or another authenticated enrollment flow supplies a short-lived, single-use grant separately. Reusing a download or guessing a code cannot enroll another device. |
| Startup and recovery | A reboot test proves sign-in recovery. Any required pre-sign-in availability needs its own Windows service design and verification. Interactive screen and input operations require a permitted user session. |
| Device operations | Exercise file transfer, commands, screen capture and input on each supported Windows build. Test attached devices separately. Verify remote revocation and local pause, including cases with an operation already running. |
| Managed update | The operator selects a published, signed version and a set of devices. Each node verifies the publisher, platform, version and payload integrity against a trust root independent of the transport. A healthy reconnect confirms success. Failure rolls back to the previous validated build while preserving the node identity. A rollout status records success, failure, or a device still offline. |

A production-signed installer, pre-sign-in operation and automatic update application and rollback are target work. Completion requires a clean Windows installation test, restart and reconnect evidence, and one successful update plus an intentional rollback. [Open work](project/TODO.md)

## Compatibility

The Android app and its build and release jobs are removed. The Python server still supports the existing phone calendar consumer, and the Hermes adapter still serves callers of the desktop tools. The historical `plugin.relay` entry point, executable name and state paths remain compatible with deployed installations. For a new server installation, `ayin-relay-server` reads explicit service environment variables and Relay configuration. The legacy module entry point additionally loads the historical Hermes environment. The [decisions](decisions.md) describe the retention boundary.
