'use strict';

function classifyCiPaths(paths) {
  const forceAll = paths.some((path) => [
    '.github/workflows/ci-required.yml',
    '.github/workflows/dependabot-auto-merge.yml',
    '.github/scripts/check-dependabot-merge.cjs',
    '.github/scripts/check-dependabot-merge.test.cjs',
    '.github/workflows/release-backmerge.yml',
    '.github/workflows/approve-release-train.yml',
    '.github/scripts/classify-ci-paths.cjs',
    '.github/scripts/classify-ci-paths.test.cjs',
    'scripts/plan_release_backmerge.py',
    'scripts/tests/plan_release_backmerge_test.py',
  ].includes(path));
  const exact = (values) => paths.some((path) => values.includes(path));
  const under = (prefixes) => paths.some((path) => prefixes.some((prefix) => path.startsWith(prefix)));

  return {
    desktop: forceAll || under(['desktop/']) || exact([
      '.github/workflows/ci-desktop.yml',
      '.github/workflows/approve-release-extensions.yml',
      '.github/workflows/release-cli.yml',
    ]),
    plugin: forceAll || paths.some((path) => /^plugin\/[^/]+\.py$/.test(path)) ||
      under(['plugin/relay/', 'plugin/tools/', 'plugin/tests/', 'relay_server/', 'hermes_relay_bootstrap/']) || exact([
        'plugin/plugin.yaml', 'pyproject.toml', 'uv.lock',
        'scripts/prepare-windows-connector-deployment.py', 'scripts/tests/prepare_windows_connector_deployment_test.py',
        'scripts/smoke-relay-distribution.py',
        'scripts/build-signed-windows-connector.py', 'scripts/tests/build_signed_windows_connector_test.py',
        'scripts/package-desktop-connector.py', 'scripts/tests/package_desktop_connector_test.py', 'scripts/check-plugin-version-sync.py',
        'scripts/check-server-version-sync.py', 'scripts/bump-plugin-version.sh',
        'scripts/bump-server-version.sh', '.github/workflows/ci-plugin.yml',
        '.github/workflows/approve-release-extensions.yml',
        '.github/workflows/release-plugin.yml',
      ]),
    dashboard: forceAll || under(['plugin/dashboard/']) || exact([
      '.github/workflows/ci-dashboard.yml',
    ]),
    docs: forceAll || under(['user-docs/']) || exact([
      'scripts/check-privacy-policy.py',
      '.github/workflows/docs.yml',
    ]),
  };
}

module.exports = { classifyCiPaths };
