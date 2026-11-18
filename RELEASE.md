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

Use the native Windows build for the release node payload. It embeds the Ayin-Relay Windows Node product name and the exact release version. The release job checks those fields before uploading the tested file. Cross-builds remain useful for packaging fixtures. Sign the Windows node payload before building the single-file connector. Sign the outer installer with the same Authenticode certificate. Record both hashes after signing. The signed EXE download setup is covered in [connector downloads](docs/connector-downloads.md#serve-a-signed-exe).

Windows self-update checks the downloaded hash, validates both the installed program and the candidate with Windows Authenticode, and requires the same publisher certificate. A Windows x64 node update also matches the signed executable's product and exact version to the selected release. Cooperative swaps verify the saved hash and release version again and retain the previous program. Changing publisher certificates or migrating an unsigned Windows installation uses a separately verified signed installer. Central rollout and rollback after an unhealthy reconnect require a separate device lifecycle check.

## Hotfix and back-merge

Start from the deployed surface tag. Prepare and test the patch and release from the approved `main` hotfix result. After the stable release, the release-backmerge workflow checks a candidate that merges the published hotfix into the existing `dev` history. It fast-forwards `dev` only after checks pass on that exact tree and the base still matches. Conflicts, stale bases, failed checks and permissions failures require a reconciliation PR. Regular `dev` → `main` releases already contain the integration parent and need no extra back-merge.
