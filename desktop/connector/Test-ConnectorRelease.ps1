param(
    [Parameter(Mandatory=$true)][string]$ReleaseManifest,
    [Parameter(Mandatory=$true)][string]$RelayUrl,
    [string]$InstallerPath,
    [string]$PreviousEvidence,
    [switch]$AfterReboot,
    [ValidateSet('applied','rolled_back')][string]$RolloutResult,
    [ValidatePattern('\A[0-9a-f]{32}\z')][string]$RolloutId,
    [string]$AttemptedReleaseManifest
)
$ErrorActionPreference = 'Stop'

function Read-EvidenceJson([string]$Path) {
    if ((Get-Item -LiteralPath $Path).Length -gt 16KB) { throw 'Evidence file exceeds its size limit.' }
    [IO.File]::ReadAllText((Resolve-Path -LiteralPath $Path).Path) | ConvertFrom-Json
}
function Get-EvidenceHash([byte[]]$Bytes) {
    $hash = [Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($hash.ComputeHash($Bytes))).Replace('-','').ToLowerInvariant() }
    finally { $hash.Dispose() }
}
function Assert-EvidenceSignature([string]$Path,[object]$Artifact,[string]$Publisher) {
    if ($Artifact.sha256 -cnotmatch '\A[0-9a-f]{64}\z' -or $Publisher -cnotmatch '\A[0-9a-f]{64}\z' -or
        (Get-Item -LiteralPath $Path).Length -ne $Artifact.size -or
        (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ine $Artifact.sha256) {
        throw 'Artifact differs from the approved release manifest.'
    }
    $signature = Get-AuthenticodeSignature -LiteralPath $Path
    if ($signature.Status -ne 'Valid' -or -not $signature.SignerCertificate -or
        -not $signature.TimeStamperCertificate -or
        (Get-EvidenceHash $signature.SignerCertificate.RawData) -cne $Publisher) {
        throw 'Artifact does not have the approved trusted publisher and timestamp.'
    }
}
function Assert-EvidenceTask([object]$Task,[string]$Sid,[string]$Launcher,[string]$Arguments) {
    $principal = [string]$Task.Principal.UserId
    $actualSid = if ($principal -match '^S-1-') { $principal } else {
        ([Security.Principal.NTAccount]::new($principal)).Translate([Security.Principal.SecurityIdentifier]).Value
    }
    $triggers = @($Task.Triggers)
    $logon = @($triggers | Where-Object {
        $_.CimClass.CimClassName -eq 'MSFT_TaskLogonTrigger' -and $_.Enabled
    })
    $watchdog = @($triggers | Where-Object {
        $_.CimClass.CimClassName -eq 'MSFT_TaskTimeTrigger' -and $_.Enabled -and
        $_.Repetition.Interval -eq 'PT1M'
    })
    if ($actualSid -cne $Sid -or [string]$Task.Principal.RunLevel -ne 'Limited' -or
        [string]$Task.Principal.LogonType -ne 'Interactive' -or [string]$Task.State -in @('Disabled','Unknown') -or
        @($Task.Actions).Count -ne 1 -or $Task.Actions[0].Execute -ine $Launcher -or
        $Task.Actions[0].Arguments -cne $Arguments -or -not $logon.Count -or -not $watchdog.Count) {
        throw 'Scheduled startup does not match the limited, interactive connector task.'
    }
}
function Assert-EvidenceConnection([object]$Status,[string]$Version,[string]$Origin,[long]$Now,[long]$Boot) {
    if ($Status.state -cne 'connected' -or $Status.configured_url -cne $Origin -or
        $Status.node_version -cne $Version -or $Status.privilege -cne 'user' -or
        [string]$Status.pid -notmatch '^[1-9][0-9]{0,9}$' -or [long]$Status.started_at -lt $Boot -or
        [long]$Status.updated_at -lt [long]$Status.started_at -or
        [long]$Status.updated_at -gt $Now -or ($Now-[long]$Status.updated_at) -gt 90) {
        throw 'No fresh authenticated status for the expected release and Relay origin.'
    }
}
function Assert-EvidenceTransition([object]$Current,[object]$Previous,[bool]$RequireReboot) {
    if ($Previous.installation_sha256 -cne $Current.installation_sha256 -or
        [long]$Previous.boot_at -le 0 -or [long]$Previous.daemon_started_at -lt [long]$Previous.boot_at -or
        [long]$Previous.observed_at -lt [long]$Previous.daemon_started_at -or
        [long]$Previous.observed_at -ge [long]$Current.observed_at -or
        [long]$Current.daemon_started_at -le [long]$Previous.daemon_started_at -or
        ($RequireReboot -and [long]$Current.boot_at -le [long]$Previous.boot_at)) {
        throw 'The previous report does not prove a restart of this installation.'
    }
}
function Assert-EvidenceReceipt([object]$Receipt,[object]$Attempt,[string]$Id,[string]$Result,[long]$Since,[long]$Now) {
    if ($Receipt.rollout_id -cne $Id -or $Receipt.attempt_id -cnotmatch '\A[0-9a-f]{32}\z' -or
        $Receipt.state -cne $Result -or $Receipt.version -cne $Attempt.version -or
        $Receipt.sha256 -cne $Attempt.node.sha256 -or
        [long]$Receipt.completed_at -le $Since -or [long]$Receipt.completed_at -gt $Now) {
        throw 'No matching terminal receipt for the requested rollout after the previous report.'
    }
}

if ($env:OS -ne 'Windows_NT') { throw 'Run this check on the Windows acceptance machine.' }
if (($AfterReboot -or $RolloutResult) -and -not $PreviousEvidence) { throw 'Capture a previous report before testing a reboot or rollout.' }
if ([bool]$RolloutResult -ne [bool]$RolloutId -or
    ($RolloutResult -ceq 'rolled_back') -ne [bool]$AttemptedReleaseManifest) {
    throw 'Specify both rollout result and ID, with the attempted release manifest for rollback.'
}
$uri = [Uri]$RelayUrl
if (-not $uri.IsAbsoluteUri -or $uri.Scheme -cne 'wss' -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) {
    throw 'Supply the expected WSS enrollment origin without credentials or query parameters.'
}
$origin = $uri.AbsoluteUri.TrimEnd('/')
$release = Read-EvidenceJson $ReleaseManifest
if ($release.source_sha -cnotmatch '\A[0-9a-f]{40}\z' -or
    $release.version -cnotmatch '\A[0-9A-Za-z.-]{5,128}\z' -or
    $release.node.filename -cne 'hermes-relay-win-x64.exe') { throw 'Invalid signed release manifest.' }
$homeDir = Join-Path ([IO.Path]::GetFullPath($env:USERPROFILE)) '.hermes'
$executable = Join-Path $homeDir 'bin\hermes-relay.exe'
Assert-EvidenceSignature $executable $release.node $release.signer_sha256
if ($InstallerPath) { Assert-EvidenceSignature $InstallerPath $release.installer $release.signer_sha256 }

$taskHash = (Get-EvidenceHash ([Text.Encoding]::UTF8.GetBytes($homeDir.ToLowerInvariant()))).Substring(0,12)
$taskName = 'HermesRelay-Connector-'+$taskHash
$task = Get-ScheduledTask -TaskPath '\' -TaskName $taskName
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$launcher = Join-Path $env:WINDIR 'System32\wscript.exe'
$launcherScript = Join-Path $homeDir 'relay-connector-hidden.vbs'
if (-not [IO.File]::Exists($launcherScript) -or -not [IO.File]::Exists((Join-Path $homeDir 'run-connector.ps1'))) {
    throw 'The connector startup scripts are missing.'
}
$arguments = '//B //Nologo "'+$launcherScript+'"'
Assert-EvidenceTask $task $sid $launcher $arguments
$boot = ([DateTimeOffset](Get-CimInstance -ClassName Win32_OperatingSystem).LastBootUpTime).ToUnixTimeSeconds()
$status = Read-EvidenceJson (Join-Path $homeDir 'daemon-status.json')
$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
Assert-EvidenceConnection $status $release.version $origin $now $boot

$process = Get-CimInstance -ClassName Win32_Process -Filter ('ProcessId='+[int]$status.pid) -Property Handle,ProcessId,ExecutablePath,CreationDate
if (-not $process -or $process.ExecutablePath -ine $executable -or
    [Math]::Abs(([DateTimeOffset]$process.CreationDate).ToUnixTimeSeconds()-[long]$status.started_at) -gt 60) {
    throw 'The live process does not match the connector status and executable.'
}
$owner = Invoke-CimMethod -InputObject $process -MethodName GetOwnerSid
if ($owner.ReturnValue -ne 0 -or $owner.Sid -cne $sid) { throw 'The live connector is owned by a different account.' }
$deviceFile = Join-Path $homeDir 'desktop-device-id'
if ((Get-Item -LiteralPath $deviceFile).Length -gt 128) { throw 'Invalid connector installation ID.' }
$device = [IO.File]::ReadAllText($deviceFile).Trim().ToLowerInvariant()
if ($device -cnotmatch '\A[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\z') { throw 'Invalid connector installation ID.' }
$report = [ordered]@{
    source_sha=$release.source_sha; version=$release.version; node_sha256=$release.node.sha256
    installer_verified=[bool]$InstallerPath
    installation_sha256=(Get-EvidenceHash ([Text.Encoding]::UTF8.GetBytes($device)))
    boot_at=$boot; daemon_started_at=[long]$status.started_at; observed_at=$now
    scheduled_task_verified=$true; connected=$true
}
if ($PreviousEvidence) {
    $previous = Read-EvidenceJson $PreviousEvidence
    Assert-EvidenceTransition $report $previous ([bool]$AfterReboot)
    if ($AfterReboot) {
        $lastRun = ([DateTimeOffset](Get-ScheduledTaskInfo -TaskPath '\' -TaskName $taskName).LastRunTime).ToUnixTimeSeconds()
        if ($lastRun -lt $boot -or $lastRun -gt $now) { throw 'The connector startup task has not run since reboot.' }
        $report.reboot_verified = $true
    }
}
if ($RolloutResult) {
    $attempt = if ($AttemptedReleaseManifest) { Read-EvidenceJson $AttemptedReleaseManifest } else { $release }
    if ($RolloutResult -ceq 'rolled_back' -and
        ($previous.node_sha256 -cne $release.node.sha256 -or $attempt.node.sha256 -ceq $release.node.sha256)) {
        throw 'The current binary must be the verified baseline after a distinct failed release.'
    }
    if ($RolloutResult -ceq 'applied' -and $previous.node_sha256 -ceq $release.node.sha256) {
        throw 'The applied rollout must change the baseline binary.'
    }
    $receipt = Read-EvidenceJson ($executable+'.rollout-result.json')
    Assert-EvidenceReceipt $receipt $attempt $RolloutId $RolloutResult ([long]$previous.observed_at) $now
    $report.rollout_id = $RolloutId
    $report.rollout_result = $RolloutResult
}
$report | ConvertTo-Json -Compress
