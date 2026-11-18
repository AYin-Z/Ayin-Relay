# Ayin-Relay

Ayin-Relay connects a Hermes Agent to Windows desktops and Android devices. It is
maintained independently in this repository, with its own development branches
and release channel.

- **Windows background connector:** Install through the authenticated download
  portal, approve the requested capabilities once, and connect automatically at
  Windows sign-in. Each device has its own revocable authorization.
- **Desktop tools:** Run commands, transfer files, manage background jobs, and use
  the screen and input tools according to the local Windows account's permissions.
- **Hermes integration:** The optional Relay plugin extends Hermes Agent. The
  standard Android connection continues to work with vanilla Hermes Agent.

## Runtime map

| Flow | Code entry point | Focused verification |
|---|---|---|
| Android chat | [`GatewayChatClient`](app/src/main/kotlin/com/hermesandroid/relay/network/upstream/GatewayChatClient.kt) talks to Hermes Agent Dashboard/Gateway. API-only connections own separate conversations. | [Gateway contract fixtures](docs/gateway-contract-testing.md) |
| Desktop tools | [`plugin/relay`](plugin/relay/) matches the paired device and sends commands to the Windows [`daemon`](desktop/src/commands/daemon.ts). Windows enforces the local input and file permissions. | [Exact-device routing test](plugin/tests/test_desktop_strict_id.py) |
| Windows enrollment | [`connector_portal.py`](plugin/connector_portal.py) issues an authenticated download invitation. [`install.ps1`](desktop/connector/install.ps1) installs the per-user client and recovery task. | [Connector authorization test](plugin/tests/test_connector_authorization.py) |

## Setup and development

- [Windows connector](desktop/connector/README.md)
- [Authenticated download portal](docs/connector-downloads.md)
- [Desktop CLI guide](user-docs/desktop/index.md)
- [Project architecture](docs/spec.md)
- [Contributing](CONTRIBUTING.md) · [Release process](RELEASE.md) · [Report a security issue](SECURITY.md)

Existing installations use the `hermes-relay` executable and plugin identifiers,
`.hermes` paths, and the existing protocol. The repository rename does not require
already enrolled Windows devices to pair again. New Ayin-Relay builds check this
repository for releases. A device running an older build continues to use the
update source compiled into that build until it is upgraded.

## Provenance and license

Ayin-Relay originated from [Hermes-Relay](https://github.com/Codename-11/hermes-relay).
The project retains the original Git history and [MIT license notice](LICENSE).
