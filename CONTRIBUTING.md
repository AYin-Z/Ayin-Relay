# Contributing to Ayin-Relay

Start with the [architecture](docs/spec.md). Develop on a branch based on current `origin/dev`, open the pull request into `dev`, and use Conventional Commits. Keep version changes on release-preparation branches. See [Releasing](RELEASE.md).

## Verify the affected component

**Relay server or download portal**

```sh
python -m pip install -e '.[dev,connector-portal]'
python -m pytest plugin/tests/test_connector_authorization.py plugin/tests/test_connector_portal.py \
  plugin/tests/test_desktop_multidevice.py plugin/tests/test_desktop_transfer.py \
  scripts/tests/package_desktop_connector_test.py
```

**Desktop connector**

```sh
cd desktop
npm ci
npm run type-check
npm test
npm run build
```

On Windows, run both PowerShell tests under `desktop/connector/tests/` for startup or installation changes. Use an isolated Windows profile for end-to-end verification and record the tested OS, package revision and recovery outcome. The [installer guide](desktop/connector/README.md) describes the isolated installation option.

Use sanitized examples. Keep tokens, private hostnames, local logs and device identifiers out of issues, tests and commits.
