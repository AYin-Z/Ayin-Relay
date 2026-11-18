# Ayin-Relay

Ayin-Relay connects Windows computers to AyinServer as managed nodes in the Ayin system. AyinServer selects the target node. The Windows connector carries out authorized device operations and returns their results. The node connection uses its own enrollment and authenticated WebSocket session.

## Install a Windows node

1. Sign in to the authenticated download page provided by your AyinServer operator.
2. Download and extract the Windows connector ZIP, then run `Install.cmd`.
3. Verify the server and confirm the listed capabilities. The package supplies a one-use invitation, so there is no code to type.

The background connector starts at Windows sign-in, restarts after a crash and reconnects after network interruptions. A node can be paused locally and its server authorization can be revoked individually. Operations run within the Windows account and session that own the connector. The [installer guide](desktop/connector/README.md) describes recovery and how to stop the connector.

## Runtime map

| Component | Location | Responsibility |
| --- | --- | --- |
| Download portal | [`plugin/connector_portal.py`](plugin/connector_portal.py) | Authenticate the download and issue a short-lived invitation |
| Relay server | [`plugin/relay/`](plugin/relay/) | Enroll and revoke nodes, route requests to the selected node and return results |
| Windows node | [`desktop/connector/`](desktop/connector/) and [`desktop/src/commands/daemon.ts`](desktop/src/commands/daemon.ts) | Install and run the connector, reconnect and enforce local permissions |

The [architecture](docs/spec.md) describes the current protocol boundary and the target for a signed, single-file Windows installer and managed updates. The Android app and its release toolchain have been retired from this repository. Existing server configuration and installation identifiers such as `hermes-relay` still identify deployed nodes. The [architecture decisions](docs/decisions.md) record the remaining compatibility integrations.

[Contributing](CONTRIBUTING.md) · [Releasing](RELEASE.md) · [Security](SECURITY.md)

Ayin-Relay retains the Git history and [MIT license](LICENSE) of its original project.
