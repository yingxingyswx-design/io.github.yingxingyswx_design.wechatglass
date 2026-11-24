param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Message,
    # Marks the snapshot directory with the -important suffix (milestones).
    [switch]$Important
)

$ErrorActionPreference = 'Stop'
$dev = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = 'C:\git\wechat_chat_ui'
if (!(Test-Path (Join-Path $repo 'backup.ps1'))) {
    throw "backup repo not found: $repo"
}

# Mirror the source tree into the backup repo.
$excludeDirs = @(
    (Join-Path $dev 'app\build'),
    (Join-Path $dev 'app\.gradle'),
    (Join-Path $dev 'app\.gradle-build')
)
foreach ($dir in 'app', 'docs', 'gradle') {
    $src = Join-Path $dev $dir
    if (!(Test-Path $src)) { continue }
    robocopy $src (Join-Path $repo $dir) /MIR /XD @excludeDirs /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed: $dir ($LASTEXITCODE)" }
}
$rootFiles = @(
    'build.ps1', 'README.md', 'BACKUP.md', 'HANDOFF.md', 'VERSION.md',
    'THIRD_PARTY_NOTICES.md', '.gitignore', 'debug.keystore',
    'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
    'gradlew', 'gradlew.bat'
)
foreach ($file in $rootFiles) {
    $src = Join-Path $dev $file
    if (Test-Path $src) { Copy-Item $src (Join-Path $repo $file) -Force }
}
Copy-Item (Join-Path $dev 'publish.ps1') (Join-Path $repo 'publish.ps1') -Force

# Commit + snapshot in the backup repo (same flow as before).
$backup = Join-Path $repo 'backup.ps1'
$backupArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $backup, '-Message', $Message)
if ($Important) { $backupArgs += '-Important' }
& powershell @backupArgs
exit $LASTEXITCODE
