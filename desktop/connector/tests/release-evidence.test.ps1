$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot '../Test-ConnectorRelease.ps1'
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw ($errors -join [Environment]::NewLine) }
foreach ($name in @('Get-EvidenceHash','Assert-EvidenceSignature','Assert-EvidenceTask','Assert-EvidenceConnection',
                    'Assert-EvidenceTransition','Assert-EvidenceReceipt')) {
    $node = $ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$true)
    if (-not $node) { throw ('Missing evidence test target '+$name) }
    . ([ScriptBlock]::Create($node.Extent.Text))
}
function Assert-Rejected([scriptblock]$Action,[string]$ExpectedError) {
    try { & $Action } catch {
        if ($_.Exception.Message -like $ExpectedError) { return }
        throw
    }
    throw 'Unexpected acceptance of invalid evidence.'
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('relay-evidence-'+[Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($root)
try {
    $exe = Join-Path $root 'signed-fixture.exe'
    [IO.File]::WriteAllText($exe,'fixture')
    $fileHash = (Get-FileHash -LiteralPath $exe).Hash.ToLowerInvariant()
    $cert = [byte[]](1,2,3)
    $publisher = Get-EvidenceHash $cert
    $script:certificateResponse = @{Status='Valid';SignerCertificate=@{RawData=$cert};TimeStamperCertificate=@{fixture=$true}}
    function Get-AuthenticodeSignature([string]$LiteralPath) { $script:certificateResponse }
    $artifact = @{sha256=$fileHash;size=(Get-Item -LiteralPath $exe).Length}
    Assert-EvidenceSignature $exe $artifact $publisher
    Assert-Rejected { Assert-EvidenceSignature $exe $artifact ('f'*64) } '*approved trusted publisher*'
    $script:certificateResponse.TimeStamperCertificate = $null
    Assert-Rejected { Assert-EvidenceSignature $exe $artifact $publisher } '*timestamp*'
    $artifact.sha256 = '1'*64
    Assert-Rejected { Assert-EvidenceSignature $exe $artifact $publisher } '*differs from the approved release*'

    $sid = 'S-1-5-21-1000000-1000000-1000000-1001'
    $launcher = 'C:\Windows\System32\wscript.exe'
    $arguments = '//B //Nologo "C:\Users\fixture\.hermes\relay-connector-hidden.vbs"'
    $task = @{State='Ready';Principal=@{UserId=$sid;RunLevel='Limited';LogonType='Interactive'};
        Actions=@(@{Execute=$launcher;Arguments=$arguments});
        Triggers=@(@{CimClass=@{CimClassName='MSFT_TaskLogonTrigger'};Enabled=$true},
            @{CimClass=@{CimClassName='MSFT_TaskTimeTrigger'};Enabled=$true;Repetition=@{Interval='PT1M'}})}
    Assert-EvidenceTask $task $sid $launcher $arguments
    $task.Triggers[1].Repetition.Interval = 'PT10M'
    Assert-Rejected { Assert-EvidenceTask $task $sid $launcher $arguments } '*Scheduled startup*'
    $task.Triggers[1].Repetition.Interval = 'PT1M'
    $task.Principal.RunLevel = 'Highest'
    Assert-Rejected { Assert-EvidenceTask $task $sid $launcher $arguments } '*Scheduled startup*'

    $relay = 'wss://relay.example.test/ws'
    $status = @{state='connected';configured_url=$relay;node_version='1.2.0';privilege='user';pid=1234;started_at=200;updated_at=250}
    Assert-EvidenceConnection $status '1.2.0' $relay 255 150
    Assert-Rejected { Assert-EvidenceConnection $status '1.2.0' $relay 345 150 } '*No fresh authenticated status*'
    Assert-Rejected { Assert-EvidenceConnection $status '1.2.0' $relay 255 203 } '*No fresh authenticated status*'
    Assert-Rejected { Assert-EvidenceConnection $status '1.2.0' 'wss://other.example.test' 255 150 } '*No fresh authenticated status*'

    $device = '1'*64
    $baseline = @{installation_sha256=$device;boot_at=100;daemon_started_at=115;observed_at=130}
    $after = @{installation_sha256=$device;boot_at=150;daemon_started_at=200;observed_at=255}
    Assert-EvidenceTransition $after $baseline $true
    $after.boot_at = 100
    Assert-Rejected { Assert-EvidenceTransition $after $baseline $true } '*does not prove a restart*'
    Assert-EvidenceTransition $after $baseline $false
    $after.installation_sha256 = '2'*64
    Assert-Rejected { Assert-EvidenceTransition $after $baseline $false } '*does not prove a restart*'

    $after.installation_sha256 = $device
    $baseline.observed_at = 0
    Assert-Rejected { Assert-EvidenceTransition $after $baseline $false } '*does not prove a restart*'

    $id = 'a'*32
    $package = @{version='1.2.0';node=@{sha256=$fileHash}}
    $receipt = @{rollout_id=$id;attempt_id=('b'*32);state='applied';version='1.2.0';sha256=$fileHash;completed_at=240}
    Assert-EvidenceReceipt $receipt $package $id 'applied' 130 255
    Assert-Rejected { Assert-EvidenceReceipt $receipt $package $id 'applied' 240 255 } '*No matching terminal receipt*'
    Assert-Rejected { Assert-EvidenceReceipt $receipt $package ('c'*32) 'applied' 130 255 } '*No matching terminal receipt*'
    Assert-Rejected { Assert-EvidenceReceipt $receipt $package $id 'rolled_back' 130 255 } '*No matching terminal receipt*'
    $receipt.state = 'rolled_back'
    Assert-EvidenceReceipt $receipt $package $id 'rolled_back' 130 255
    Write-Output 'PASS: signed bytes and timestamp, limited periodic startup, fresh connection, reboot attribution, and applied or rolled-back receipts'
} finally { Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue }
