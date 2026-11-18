# Releasing Ayin-Relay

| Surface | Tag | Version authority | Workflow |
| --- | --- | --- | --- |
| Relay server and compatibility package | `server-v*` | `pyproject.toml` and synchronized server/plugin metadata | `release-plugin.yml` |
| Desktop tools and node executable | `desktop-v*` | `desktop/package.json` and synchronized desktop metadata | `release-cli.yml` |

Use `python scripts/check-version-tracks.py` to validate versions. The two surfaces can be released independently.

## Prepare and publish

1. Merge and verify the feature changes on `dev`. Open a separate release-preparation branch from current `origin/dev` after the applicable release scope is approved.
2. Bump only the affected surface with `bash scripts/bump-plugin-version.sh <version>` or `npm version --no-git-tag-version <version>` in `desktop/`. Update the matching release notes and changelog. The notes use public examples and contain no credentials or private infrastructure.
3. Merge release preparation into `dev`. Candidate releases use an immutable prerelease tag for the tested `dev` revision. For a stable release, open and merge the `dev` → `main` release PR and tag the resulting `main` tip.
4. Use the release approval workflow for the selected surface. Verify the immutable tag and exact-source artifacts. The private download portal uses a credential-free connector template built from a pinned Windows executable. Build it with [the packaging instructions](desktop/connector/README.md#build-the-portal-template).
5. Verify the artifact actually used by the deployment. Record the candidate's artifact digest, an authorized test enrollment, reconnect after interruption, and the rollback version. GitHub publishing alone does not update a deployed node.

The coordinated approval workflow can dispatch both surface approvals. Review status on the exact candidate tree. Production uses immutable stable tags.

## Windows signatures and updates

Prepare the private installer and its node binary on an x64 Windows signing host with Git, Python 3.11+, Node/npm, the Bun version in `desktop/.bun-version`, NSIS and Windows SDK SignTool. The signing provider must expose the approved code-signing certificate and key through the current Windows user's personal certificate store. Supply the provider's HTTPS RFC 3161 timestamp URL and pin the certificate's SHA-256 DER fingerprint. [Windows SDK SignTool documentation](https://learn.microsoft.com/en-us/windows/win32/seccrypto/signtool)

Run the following from the repository with deployment settings supplied in the indicated environment variables.

```powershell
python scripts/build-signed-windows-connector.py `
  --source-sha $env:RELEASE_COMMIT_SHA `
  --output $env:RELEASE_OUTPUT_DIR `
  --certificate-sha1 $env:CODE_SIGNING_CERTIFICATE_THUMBPRINT `
  --publisher-sha256 $env:CODE_SIGNING_CERTIFICATE_SHA256 `
  --timestamp-url $env:CODE_SIGNING_TIMESTAMP_URL `
  --browser-portal $env:CONNECTOR_ENROLLMENT_URL `
  --relay-url $env:CONNECTOR_RELAY_URL
```

If SignTool or NSIS is outside PATH, pass its full path with `--signtool` or `--makensis`. Use a new output directory under an existing parent. The command builds from the selected Git commit in a temporary directory, signs the node before embedding it, then signs the outer installer. It checks both embedded signatures, timestamps, publisher pins and product metadata before making the output available.

The output contains the signed node `hermes-relay-win-x64.exe`, the signed private installer `Ayin-Relay-Setup-x64.exe` and `release.json` with the source commit, version, publisher fingerprint and SHA-256 of each finished file. Configure the portal with the installer entry and the update offer with the node entry. Keep this installer within its intended authenticated download portal. See [connector downloads](docs/connector-downloads.md#serve-a-signed-exe).

Windows self-update checks the downloaded hash, validates both the installed program and the candidate with Windows Authenticode, and requires the same publisher certificate. A Windows x64 node update also matches the signed executable's product and exact version to the selected release. Cooperative swaps verify the saved hash and release version again and retain the previous program. Changing publisher certificates or migrating an unsigned Windows installation uses a separately verified signed installer. Central rollout and rollback after an unhealthy reconnect require a separate device lifecycle check.

## Stage the signed configuration

Use the Ayin-Relay server's Python environment on the staging host, with `osslsigncode` and `7z` available. Prepare both configurations from the signed release directory and the intended portal's existing owner-only configuration. Pin the approved commit and publisher from the release approval.

```sh
python scripts/prepare-windows-connector-deployment.py \
  --release-dir "$SIGNED_RELEASE_DIR" \
  --portal-config "$EXISTING_PORTAL_CONFIG" \
  --source-sha "$RELEASE_COMMIT_SHA" \
  --publisher-sha256 "$CODE_SIGNING_CERTIFICATE_SHA256" \
  --trust-bundle "$PUBLISHER_CA_BUNDLE" \
  --timestamp-trust-bundle "$TIMESTAMP_CA_BUNDLE" \
  --device-id "$PILOT_INSTALLATION_UUID" \
  --output "$STAGED_CONFIG_DIR"
```

Repeat `--device-id` for each approved installation. The command verifies both signatures and checks the enrollment origin, embedded release manifest and embedded node bytes before staging `portal.json` and `windows-release-offer.json`. It retains the existing Access policy and ZIP fallback. Use the new files to configure staging after review and confirm the signed installer on Windows before switching production.

## Windows device evidence

Use a designated Windows x64 test installation signed by the approved publisher. Capture evidence while signed in as the connector user, after downloading the exact installer from the authenticated portal and completing setup. The read-only check inspects the installed binary, task, live process and fresh connection. The JSON contains a digest of the installation identity and omits credentials.

```powershell
$expected = @{
  ReleaseManifest = $env:RELEASE_MANIFEST
  RelayUrl = $env:CONNECTOR_RELAY_URL
}
& .\desktop\connector\Test-ConnectorRelease.ps1 @expected -InstallerPath $env:DOWNLOADED_INSTALLER |
  Set-Content -LiteralPath .\before-reboot.json -Encoding UTF8
```

Reboot and sign in without manually starting the connector. Run the same check with `-PreviousEvidence .\before-reboot.json -AfterReboot` and save the result as the baseline for the next rollout. The check requires a newer boot, execution of the installed startup task since that boot and a live connector with the same installation identity.

After a managed update, pass the newer release manifest, the baseline report, the central rollout ID and `-RolloutResult applied`. For a deliberate rollback trial, pass the preserved release as `-ReleaseManifest`, the failed package as `-AttemptedReleaseManifest` and `-RolloutResult rolled_back`. Match the device result against the operator's rollout status. Manually verify the browser approval, expected elevation and cancellation behavior, local stop and uninstall. Record any checks that could not be exercised.

## Hotfix and back-merge

Start from the deployed surface tag. Prepare and test the patch and release from the approved `main` hotfix result. After the stable release, the release-backmerge workflow checks a candidate that merges the published hotfix into the existing `dev` history. It fast-forwards `dev` only after checks pass on that exact tree and the base still matches. Conflicts, stale bases, failed checks and permissions failures require a reconciliation PR. Regular `dev` → `main` releases already contain the integration parent and need no extra back-merge.
