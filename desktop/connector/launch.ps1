param([Parameter(Mandatory=$true)][string]$InstallerPath, [switch]$MigrateCertificate)
$ErrorActionPreference = 'Stop'
$setupPath = Join-Path $PSScriptRoot 'setup.json'
try {
    $settings = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'portal.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $node = Join-Path $PSScriptRoot 'hermes-relay.exe'
    $installerSignature = Get-AuthenticodeSignature -LiteralPath $InstallerPath
    $nodeSignature = Get-AuthenticodeSignature -LiteralPath $node
    if ($installerSignature.Status -ne 'Valid' -or -not $installerSignature.SignerCertificate -or
        $nodeSignature.Status -ne 'Valid' -or -not $nodeSignature.SignerCertificate -or
        [Convert]::ToBase64String($installerSignature.SignerCertificate.RawData) -cne
        [Convert]::ToBase64String($nodeSignature.SignerCertificate.RawData)) {
        Write-Warning 'The installer and its node program need valid signatures from the same publisher.'
        exit 1
    }
    Write-Host 'Ayin-Relay Windows node setup'
    Write-Host 'Compare the confirmation code below with the one shown in your browser.'
    $browserOptions = @('--prepare-browser', [string]$settings.portal, '--remote', [string]$settings.relay_url, '--setup', $setupPath)
    if ($MigrateCertificate) { $browserOptions += '--migrate-certificate' }
    & $node connector @browserOptions
    if ($LASTEXITCODE -ne 0) { throw 'Browser approval did not finish.' }
    & (Join-Path $PSScriptRoot 'install.ps1') -EnableManagedUpdates
} catch {
    Write-Warning 'Setup did not finish. Check the browser approval and the installer message above.'
    [void](Read-Host 'Press Enter to close setup')
    exit 1
} finally {
    Remove-Item -LiteralPath $setupPath -Force -ErrorAction SilentlyContinue
}
