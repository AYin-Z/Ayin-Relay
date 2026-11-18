# Authenticated connector downloads

The authenticated Ayin-Relay download portal supplies the Windows node connector. See the
[installer guide](../desktop/connector/README.md) for user consent, local backups
and Windows startup behavior.

## Authorization

Place the private portal behind an existing Cloudflare Access application. It
verifies the signed `Cf-Access-Jwt-Assertion` itself against the configured team
issuer and application audience. Forward the original assertion and browser
Origin header through the proxy; do not replace them with a shared secret.
The public landing page contains only a link to the private page.

A successful download creates a random, single-use invitation valid for one
hour, allowing time to download a large installer over a slow connection.
Ordinary six-character pairing codes retain their ten-minute lifetime. The
portal records the operator endpoint's returned lifetime in the installation
package's expiry. The installation exchanges that invitation for a permanent
session with exactly one `desktop` grant. It cannot use server chat, terminal, voice,
media, profiles or management routes. Revoke a device using the existing
operator session controls. This revocation is separate from pausing its local
startup task.

The installer explicitly confirms file access, command execution, screen and
input control, USB, microphone and camera capabilities, then saves the existing
`full_access` host policy. Operating system permissions still apply. The
connector runs with `--no-voice`; permitting microphone and camera tools does
not automatically start recording or a voice conversation.

## Browser registration for a fixed installer

The browser registration path keeps authenticated confirmation independent of the signed EXE. The installer holds a random verifier and sends its SHA-256 challenge when opening the portal. The approval is bound to the logged-in identity, the challenge and the callback parameters. The user compares the confirmation identifier on the installer and the browser.

The portal returns the five-minute invitation to the installer's local listener. Relay accepts the invitation only with the original verifier. A bad proof leaves the invitation available to the original installer. Successful redemption consumes it and creates the usual revocable desktop-only authorization.

Browser registration is disabled by default during migration. This server-side path requires a compatible installer that verifies the callback state, trusts the expected portal and Relay origins and keeps the verifier local. The public installer and the production download switch have separate Windows acceptance requirements.

## Operator setup

Install the `connector-portal` extra and run `ayin-relay-download --config <path>` with the portal service configuration. Prepare a credential-free template
with `scripts/package-desktop-connector.py`. Store the template outside the
public web root. Create a JSON configuration owned by the service account,
mode `0600`, with absolute template path and these values:

```json
{
  "public_origin": "https://admin.example.com",
  "relay_url": "wss://relay.example.com",
  "access_issuer": "https://example.cloudflareaccess.com",
  "access_audience": "existing-access-application-audience",
  "base_zip_path": "/srv/apps/relay-download/connector-template.zip"
}
```

Run the portal as a separate service:

```sh
python -m plugin.connector_portal --config /private/connector-portal.json --port 18767
```

It listens on loopback only. Proxy `/relay/` on the protected origin to this
listener, preserving the path. Private routes return `no-store`; do not cache
them. Only `POST /relay/private/download` can issue a package. It requires a
valid Access assertion, same-origin request and a subject-bound CSRF token.
Per-user rate and global concurrency limits bound package generation. The
portal streams from a private temporary file and removes it on completion or
cancellation. Access logs are disabled to avoid retaining invitation material.

The Relay's `POST /connector/invite` accepts only direct loopback operator
requests with the actual local listener Host and without browser or forwarding
headers. Do not proxy that endpoint. Ordinary portal requests have no body.
For an existing installation migration, a local operator can submit
`{"device_id":"<exact-existing-UUID>"}`; its invitation is bound to that
identity. Redemption atomically replaces that device's prior credentials.
Other installations are unaffected. A public download invitation cannot
overwrite a paired identity.

## Verification and recovery

The focused tests cover signature/issuer/audience/expiry validation, anonymous
and cross-origin denial, CSRF binding, isolated invitation ZIPs, download
cancellation, bounded concurrency, desktop-only scope and targeted migration.
Windows installation and process recovery require separate native evidence.

Back up authorization state before a server schema rollback. Older server
versions do not understand the desktop-only flag; restore the corresponding
old authorization checkpoint rather than carrying newly issued permanent
credentials into an older server. Coordinate any client rollback with that
checkpoint. Once a device's credentials are revoked, local file restoration
cannot reactivate them; finish the new installation or issue a fresh operator
invitation bound to that device.
