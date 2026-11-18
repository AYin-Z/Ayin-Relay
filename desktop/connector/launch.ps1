$ErrorActionPreference = 'Stop'
$setupPath = Join-Path $PSScriptRoot 'setup.json'
try {
    $settings = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'portal.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $node = Join-Path $PSScriptRoot 'hermes-relay.exe'
    Write-Host 'Ayin-Relay Windows node setup'
    Write-Host 'Compare the confirmation code below with the one shown in your browser.'
    & $node connector --prepare-browser ([string]$settings.portal) --remote ([string]$settings.relay_url) --setup $setupPath
    if ($LASTEXITCODE -ne 0) { throw 'Browser approval did not finish.' }
    & (Join-Path $PSScriptRoot 'install.ps1')
} catch {
    Write-Warning 'Setup did not finish. Check the browser approval and the installer message above.'
    [void](Read-Host 'Press Enter to close setup')
    exit 1
} finally {
    Remove-Item -LiteralPath $setupPath -Force -ErrorAction SilentlyContinue
}
