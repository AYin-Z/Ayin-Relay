# AGENTS.md

Universal agent instructions for **Ayin-Relay**. This is the entry point for any
coding agent (Claude Code, Codex, Cursor, etc.).

Ayin-Relay is maintained in `AYin-Z/Ayin-Relay`. `origin` is the standalone
repository and `origin/dev` is the integration authority. The former Hermes-Relay
repository is historical provenance. Do not sync from it or use it as a default
release or update source. The Windows connector and AyinServer Relay service are the primary surfaces.
Use the existing compatibility tests when changing retained adapters.

## Read this first

Start with this file and the [runtime map in README](README.md#runtime-map).
Use [the architecture](docs/spec.md) and [decisions](docs/decisions.md)
for the surface you are changing. Provider adapters
such as **[CLAUDE.md](CLAUDE.md)** import this file instead of duplicating
policy. They do not redefine the branch, release, hotfix, or verification
contract here and in `RELEASE.md`.

- Release process → **[RELEASE.md](RELEASE.md)**
- Contributor setup → **[CONTRIBUTING.md](CONTRIBUTING.md)**
- Follow-ups / deferred work / known gaps → **[docs/project/TODO.md](docs/project/TODO.md)** (the single home for "what's next" — never DEVLOG, never scattered code comments)

## Branch contract

| Contract item | Canonical source or target |
|---|---|
| Integration branch | `dev`; normal feature, fix, docs, and chore PRs target `dev` |
| Integration authority | `origin/dev`; local `dev` is a fast-forward-only mirror, never a private staging queue |
| Release branch | `main`; release history and hotfix integration only |
| Production tag source | The new `main` tip after an approved `dev` → `main` release PR, or after an approved hotfix PR to `main` |
| Candidate tag source | An exact release-prepared and tested `dev` SHA; prerelease suffix required (`-alpha`, `-beta`, or `-rc.N`) |
| Staging source | An exact tested `dev` SHA or release-candidate tag; staging is an environment, never a branch |
| Production source | Immutable `server-v*` or `desktop-v*` tags, selected by surface |
| Hotfix base | The immutable production tag for the affected surface |
| Back-merge target | `dev`; stable hotfixes reconcile automatically when the exact tested merge is conflict-free, otherwise through a PR |

Feature completion means merged and verified on `dev`; it does not mean
released. A release train is separate work owned by a Forge release
issue/session: reconcile only the affected surface version and notes on `dev`,
open the `dev` → `main` release PR, tag the resulting `main` tip, publish the
surface artifacts, deploy or roll out, and verify the live result. Never create
a staging branch.

A normal `dev` → `main` release needs no back-merge: the released integration
parent is already in `dev`. A production-tag hotfix is different. After its
stable release succeeds, `Release Backmerge` prepares a `dev`-first merge
commit, runs the same path-aware required checks on that exact SHA, verifies
that `dev` has not moved, and fast-forwards `dev`. Conflicts, failed checks,
stale refs, or denied branch updates fail closed and require a reconciliation
PR; never resolve those cases by choosing a side automatically.

### Local integration discipline

- Fetch `origin/dev` before creating a task branch or worktree; do not base new
  work on a stale local `dev` ref.
- Keep the primary local `dev` checkout tracked-clean and update it only with
  `git merge --ff-only origin/dev`. Feature, fix, docs, release-prep, and
  integration commits belong on their own branches and reach `dev` through PRs.
- When several reviewed branches must move together, combine them on a named
  `integration/<batch>` branch in its own worktree, then open one PR to `dev`.
  An integration branch is not a second `dev` and must not become a hidden queue.
- One coordinator owns final base refresh, required checks, and merges while
  concurrent worktrees continue independently.

## Non-negotiables (the short list)

- **Windows nodes:** Keep authenticated enrollment, per-device revocation,
  local consent and device targeting intact. Retain the deployed wire protocol,
  state paths and service names until a verified migration can replace them.
- **Compatibility:** Validate consumers before removing a retained adapter.
  The current phone calendar path and Hermes desktop tool clients have a
  compatibility contract.
- **Conventional Commits + `main`/`dev` branching.** Normal branches start at
  current `origin/dev` and PR back to `dev`; merge commits/no-ff are the
  repository policy.
  Version bumps happen only on a release-prep branch targeting `dev`, and
  production tags are cut only from `main`.
- **Relay server (Python 3.11+):** aiohttp + asyncio (no threading), type hints
  everywhere, structured `logging` (no `print`). **Desktop CLI (Node ≥21):**
  zero runtime deps, strict TS + ES modules, ship compiled `dist/`. Contributor
  commands and the development loop live in `CONTRIBUTING.md`.

## Review guidelines

- Report only actionable correctness, security, compatibility, or release-risk
  findings; avoid stylistic preferences unless they violate a documented rule.
- Treat the node enrollment, device permissions, targeting and upgrade contracts
  as release-critical.
- Check that changes preserve public-repo writing hygiene and do not expose
  secrets, private infrastructure, or personal information.
- Use CI evidence for the tested scope. Claims about Windows startup, UAC,
  interactive desktop control or device access require Windows verification.
- Prioritize findings that warrant holding the merge. State the impacted path
  and the concrete failure mode.

## Public-repo writing hygiene

Everything committed is public. In CHANGELOG, DEVLOG, README, docs, and release
notes:

- **No personal names** — attribute impersonally; identity lives in git + the
  signing cert.
- **No private infrastructure** — real hostnames/IPs, internal deployment names,
  `~/SYSTEM.md`. (Generic example IPs in setup docs are fine.)
- **No AI/assistant process self-narration** ("I should have…", course
  corrections) — state the technical conclusion only.
- **No internal jargon or fork/branch plumbing** in user-facing notes.
- **CHANGELOG** uses Keep-a-Changelog grouping; condense the version block to
  crisp public bullets at release-prep (see RELEASE.md §2 "Scrub for public
  distribution"). **DEVLOG** is a depersonalized, factual engineering log.
