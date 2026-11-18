param([string]$InstallerPath = (Join-Path $PSScriptRoot '../install.ps1'))
$ErrorActionPreference = 'Stop'
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($InstallerPath,[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw ($errors -join [Environment]::NewLine) }
foreach ($name in @('Get-ConnectorRolloutDigest','Write-ConnectorRolloutJson','Read-ConnectorRolloutJson',
    'Clear-ConnectorRolloutTrial','Complete-ConnectorRollout','Restore-ConnectorRollout','Wait-ConnectorRolloutHealth','Invoke-ConnectorRollout')) {
    $node = $ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$true)
    if (-not $node) { throw ('Missing installer function '+$name) }
    . ([ScriptBlock]::Create($node.Extent.Text))
}
function Assert-Case([bool]$condition,[string]$message) {
    if (-not $condition) { throw $message }
}
$root = Join-Path ([IO.Path]::GetTempPath()) ('relay-rollout-'+[Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($root)
$script:remote = 'wss://relay.example.test/ws'
$script:expectedVersion = '1.2.0'
$script:publisher = 'a'*64
function Get-ConnectorRolloutPublisher([string]$Path) { return $script:publisher }
function Stop-ConnectorRolloutDaemon([string]$StatusPath,[string]$Executable) {
    $script:stops++
    [IO.File]::Delete($StatusPath)
}
function Get-ConnectorDaemonState([string]$StatusPath,[string]$Executable) {
    if ([IO.File]::Exists($StatusPath)) { return 'alive' }
    return 'gone'
}
function Start-Sleep([int]$Seconds) {
    if ($script:mode -eq 'stale_heartbeat') { return }
    $record = Read-ConnectorRolloutJson $script:status
    $record.updated_at = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    if ($script:mode -eq 'lost_connection') { $record.state = 'reconnecting' }
    if ($script:mode -eq 'changed_pid') { $record.pid = 4321 }
    Write-ConnectorRolloutJson $script:status $record
}
function Invoke-ConnectorHiddenCli([string]$Executable,[string]$Arguments,[int]$TimeoutMilliseconds) {
    $script:calls += $Arguments
    if ($Arguments -eq 'update --verify-rollout --json') {
        Assert-Case ([IO.File]::ReadAllText($Executable) -eq 'old') 'Verification must be done by the old program'
        if ($script:mode -eq 'bad_signature') { return @{ExitCode=1} }
        return @{ExitCode=0;StandardOutput=(ConvertTo-Json -Compress @{version=$script:expectedVersion;sha256=$script:digest})}
    }
    Assert-Case ([IO.File]::ReadAllText($Executable) -eq 'new') 'The launcher must start the replacement, not the old image'
    if ($script:mode -eq 'start_failure') { return @{ExitCode=1} }
    $relay = if ($script:mode -eq 'wrong_relay') { 'wss://other.example.test/ws' } else { $script:remote }
    $version = if ($script:mode -eq 'wrong_version') { '1.1.0' } else { $script:expectedVersion }
    Write-ConnectorRolloutJson $script:status @{pid=1234;state='connected';node_version=$version;configured_url=$relay;
        privilege='user';updated_at=([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()-35)}
    return @{ExitCode=0}
}
function New-Fixture([string]$Name) {
    $directory = Join-Path $root $Name
    [void][IO.Directory]::CreateDirectory($directory)
    $script:executable = Join-Path $directory 'node.exe'
    $script:status = Join-Path $directory 'daemon-status.json'
    $script:stage = $script:executable+'.rollout-stage'
    $script:trial = $script:executable+'.rollout-trial'
    $script:receipt = $script:executable+'.rollout-result.json'
    $script:previous = $script:executable+'.rollout-previous.exe'
    [void][IO.Directory]::CreateDirectory($script:stage)
    [IO.File]::WriteAllText($script:executable,'old')
    [IO.File]::WriteAllText((Join-Path $script:stage 'candidate.exe'),'new')
    $script:digest = Get-ConnectorRolloutDigest (Join-Path $script:stage 'candidate.exe')
    $script:oldDigest = Get-ConnectorRolloutDigest $script:executable
    $script:mode = $Name
    $script:stops = 0
    $script:calls = @()
}
function Interrupt-Fixture {
    [IO.Directory]::Move($script:stage,$script:trial)
    $plan = @{attempt_id=('b'*32);version=$script:expectedVersion;sha256=$script:digest;
        previous_sha256=$script:oldDigest;publisher_sha256=$script:publisher}
    Write-ConnectorRolloutJson (Join-Path $script:trial 'plan.json') $plan
    [IO.File]::Replace((Join-Path $script:trial 'candidate.exe'),$script:executable,(Join-Path $script:trial 'backup.exe'))
    return $plan
}
try {
    New-Fixture 'success'
    Invoke-ConnectorRollout $executable $status $remote
    Assert-Case ((Read-ConnectorRolloutJson $receipt).state -eq 'applied') 'Authenticated target version should commit'
    Assert-Case ([IO.File]::ReadAllText($previous) -eq 'old') 'Successful rollout must retain its recoverable previous version'
    Assert-Case ($stops -eq 1 -and $calls.Count -eq 2 -and -not [IO.Directory]::Exists($trial)) 'A successful trial must run once'
    Invoke-ConnectorRollout $executable $status $remote
    Assert-Case ($calls.Count -eq 2) 'A completed trial must not launch again'

    foreach ($case in @('start_failure','wrong_relay','wrong_version','lost_connection','changed_pid','stale_heartbeat')) {
        New-Fixture $case
        Invoke-ConnectorRollout $executable $status $remote
        Assert-Case ((Read-ConnectorRolloutJson $receipt).state -eq 'rolled_back') 'Unhealthy versions must produce a rollback receipt'
        Assert-Case ([IO.File]::ReadAllText($executable) -eq 'old') 'Failed trial must restore the exact previous binary'
        Assert-Case ($stops -eq 2 -and -not [IO.Directory]::Exists($trial)) 'Failed version must be stopped and retired'
    }

    New-Fixture 'bad_signature'
    Invoke-ConnectorRollout $executable $status $remote
    Assert-Case ($calls.Count -eq 1 -and $stops -eq 0) 'Unverified update must not stop or launch a daemon'
    Assert-Case ([IO.File]::ReadAllText($executable) -eq 'old') 'Rejected update must keep the active image'

    New-Fixture 'tampered'
    [IO.File]::WriteAllText((Join-Path $stage 'candidate.exe'),'changed after verification')
    Invoke-ConnectorRollout $executable $status $remote
    Assert-Case ($calls.Count -eq 1 -and $stops -eq 0) 'Digest change after verification must block replacement'

    New-Fixture 'interrupted'
    $null = Interrupt-Fixture
    Invoke-ConnectorRollout $executable $status $remote
    Assert-Case ((Read-ConnectorRolloutJson $receipt).state -eq 'rolled_back') 'Power loss before commit should restore the previous version'
    Assert-Case ($calls.Count -eq 0 -and [IO.File]::ReadAllText($executable) -eq 'old') 'Recovery must not execute the unconfirmed image'

    New-Fixture 'committed'
    $plan = Interrupt-Fixture
    Write-ConnectorRolloutJson $receipt @{attempt_id=$plan.attempt_id;state='applied'}
    Invoke-ConnectorRollout $executable $status $remote
    Assert-Case ($calls.Count -eq 0 -and $stops -eq 0 -and [IO.File]::ReadAllText($executable) -eq 'new') 'A committed trial interrupted during cleanup must not roll back'
    Assert-Case ([IO.File]::ReadAllText($previous) -eq 'old') 'Committed recovery must also retain the previous image'

    New-Fixture 'invalid_backup'
    $null = Interrupt-Fixture
    [IO.File]::WriteAllText((Join-Path $trial 'backup.exe'),'invalid')
    $rejected = $false
    try { Invoke-ConnectorRollout $executable $status $remote } catch { $rejected = $true }
    Assert-Case ($rejected -and $calls.Count -eq 0 -and $stops -eq 0) 'An invalid backup must stay unexecuted'
    Assert-Case ([IO.Directory]::Exists($trial) -and -not [IO.File]::Exists($receipt)) 'An unresolved recovery must preserve evidence'
    Write-Output 'PASS: accepted rollout, retained backup, authenticated version, origin, PID and heartbeat checks, rejected signature and digest, interrupted trial, committed cleanup, invalid backup'
} finally { Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue }
