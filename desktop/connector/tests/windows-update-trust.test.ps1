param(
    [Parameter(Mandatory=$true)] [string]$NodeExecutable,
    [Parameter(Mandatory=$true)] [string]$Version
)
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -cne 'true' -or $env:RUNNER_ENVIRONMENT -cne 'github-hosted') {
    throw 'This certificate fixture requires a disposable GitHub-hosted runner.'
}
$source = (Resolve-Path -LiteralPath $NodeExecutable).Path
$id = [Guid]::NewGuid().ToString('N')
$root = Join-Path $env:RUNNER_TEMP "relay-signature-$id"
$thumbprints = [Collections.Generic.List[string]]::new()

function New-FixtureSigner([string]$Name) {
    Write-Host "Creating disposable $Name signing identity."
    $certificate = New-SelfSignedCertificate -Type CodeSigningCert -CertStoreLocation 'Cert:\CurrentUser\My' -Subject "CN=Ayin Relay CI $Name $id" -NotAfter (Get-Date).AddHours(2)
    $thumbprints.Add($certificate.Thumbprint)
    $publicCertificate = Join-Path $root "$Name.cer"
    Export-Certificate -Cert $certificate -FilePath $publicCertificate | Out-Null
    Write-Host "Trusting disposable $Name certificate for this runner account."
    & certutil.exe -f -user -addstore Root $publicCertificate | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Cannot trust the disposable signing certificate.' }
    return $certificate
}

function Sign-Fixture([string]$Name, $Certificate) {
    $path = Join-Path $root $Name
    Copy-Item -LiteralPath $source -Destination $path
    Write-Host "Signing fixture $Name."
    $signature = Set-AuthenticodeSignature -LiteralPath $path -Certificate $Certificate -HashAlgorithm SHA256 -Confirm:$false
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Thumbprint -cne $Certificate.Thumbprint) {
        throw "Fixture signing did not produce a trusted Windows signature for $Name ($($signature.Status))."
    }
}

try {
    New-Item -ItemType Directory -Path $root | Out-Null
    $publisher = New-FixtureSigner 'publisher'
    $otherPublisher = New-FixtureSigner 'other'
    Sign-Fixture 'current.exe' $publisher
    Sign-Fixture 'approved.exe' $publisher
    Sign-Fixture 'other.exe' $otherPublisher
    Copy-Item -LiteralPath $source -Destination (Join-Path $root 'unsigned.exe')
    Push-Location (Join-Path $PSScriptRoot '..\..')
    try {
        & node --import tsx (Join-Path $PSScriptRoot 'windows-update-trust.test.ts') $root $Version
        if ($LASTEXITCODE -ne 0) { throw 'Native Windows update trust assertions failed.' }
    } finally {
        Pop-Location
    }
} finally {
    Write-Host 'Removing the temporary test certificates and files.'
    foreach ($thumbprint in $thumbprints) {
        $trusted = "Cert:\CurrentUser\Root\$thumbprint"
        if (Test-Path -LiteralPath $trusted) { Remove-Item -LiteralPath $trusted -Confirm:$false }
        $identity = "Cert:\CurrentUser\My\$thumbprint"
        if (Test-Path -LiteralPath $identity) { Remove-Item -LiteralPath $identity -DeleteKey -Confirm:$false }
    }
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}
