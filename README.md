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
