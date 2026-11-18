'use strict';

const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { classifyCiPaths } = require('./classify-ci-paths.cjs');

const none = {
  desktop: false,
  plugin: false,
  dashboard: false,
  docs: false,
};

assert.deepEqual(classifyCiPaths(['README.md']), none);
assert.deepEqual(classifyCiPaths(['desktop/src/cli.ts']), { ...none, desktop: true });
assert.deepEqual(classifyCiPaths(['.github/workflows/approve-release-extensions.yml']), {
  ...none,
  desktop: true,
  plugin: true,
});
assert.deepEqual(classifyCiPaths(['.github/workflows/release-cli.yml']), { ...none, desktop: true });
assert.deepEqual(classifyCiPaths(['.github/workflows/release-plugin.yml']), { ...none, plugin: true });
assert.deepEqual(classifyCiPaths(['plugin/relay/server.py']), { ...none, plugin: true });
assert.deepEqual(classifyCiPaths(['plugin/dashboard/src/App.tsx']), { ...none, dashboard: true });
assert.deepEqual(classifyCiPaths(['user-docs/index.md']), { ...none, docs: true });
assert.deepEqual(classifyCiPaths(['scripts/package-desktop-connector.py']), { ...none, plugin: true });
assert.deepEqual(classifyCiPaths(['scripts/check-privacy-policy.py']), { ...none, docs: true });
assert.deepEqual(classifyCiPaths(['.github/workflows/ci-required.yml']), {
  desktop: true,
  plugin: true,
  dashboard: true,
  docs: true,
});
assert.deepEqual(classifyCiPaths(['.github/workflows/release-backmerge.yml']), {
  desktop: true,
  plugin: true,
  dashboard: true,
  docs: true,
});
assert.deepEqual(classifyCiPaths(['.github/workflows/approve-release-train.yml']), {
  desktop: true,
  plugin: true,
  dashboard: true,
  docs: true,
});

const repoRoot = join(__dirname, '..', '..');
const approvalWorkflow = readFileSync(
  join(repoRoot, '.github', 'workflows', 'approve-release-extensions.yml'),
  'utf8',
);
const cliReleaseWorkflow = readFileSync(
  join(repoRoot, '.github', 'workflows', 'release-cli.yml'),
  'utf8',
);
const pluginReleaseWorkflow = readFileSync(
  join(repoRoot, '.github', 'workflows', 'release-plugin.yml'),
  'utf8',
);
const desktopCiWorkflow = readFileSync(
  join(repoRoot, '.github', 'workflows', 'ci-desktop.yml'),
  'utf8',
);
const requiredChecksWorkflow = readFileSync(
  join(repoRoot, '.github', 'workflows', 'ci-required.yml'),
  'utf8',
);
const releaseTrainWorkflow = readFileSync(
  join(repoRoot, '.github', 'workflows', 'approve-release-train.yml'),
  'utf8',
);

assert.match(approvalWorkflow, /permissions:\r?\n  contents: read/);
assert.match(
  approvalWorkflow,
  /approve:[\s\S]*?permissions:\r?\n      actions: write\r?\n      contents: write/,
);
assert.match(
  approvalWorkflow,
  /ref: \$\{\{ contains\(inputs\.version, '-'\) && 'dev' \|\| 'main' \}\}/,
);
assert.match(cliReleaseWorkflow, /workflow_dispatch:[\s\S]*?Approved CLI\+UI version/);
assert.match(cliReleaseWorkflow, /name: Restore exact-source tray build cache[\s\S]*?actions\/cache@v6/);
assert.match(desktopCiWorkflow, /name: Restore exact-source tray build cache[\s\S]*?actions\/cache@v6/);
assert.match(pluginReleaseWorkflow, /workflow_dispatch:[\s\S]*?Approved Plugin version/);
assert.match(requiredChecksWorkflow, /name: Reuse exact-tree required checks/);
assert.match(requiredChecksWorkflow, /name: required-checks-\$\{\{ needs\.changes\.outputs\.tree \}\}/);
assert.match(releaseTrainWorkflow, /name: Ayin-Relay Coordinated Release Approval/);

console.log('CI path classification tests passed.');
