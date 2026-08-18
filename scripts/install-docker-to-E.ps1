# Installs Docker Desktop with everything pointed at E: instead of C:.
#
# Why: C: has repeatedly filled up during this project and corrupted Docker's WSL data
# disk. The three --*-data-root / --installation-dir flags below are set at install time
# rather than through Settings afterwards, because the data disk is created on first
# launch — configuring it after the fact means it lands on C: once first, which is the
# exact failure being avoided.
#
# Run it: right-click this file and pick "Run with PowerShell", or from any shell:
#   powershell.exe -ExecutionPolicy Bypass -File "E:\NEW SUPPLY\scripts\install-docker-to-E.ps1"
# It re-launches itself elevated if needed, so a UAC prompt is expected.

$ErrorActionPreference = 'Stop'

$installer = 'E:\Docker Desktop Installer-latest.exe'
$installDir = 'E:\Docker'
$wslDataRoot = 'E:\DockerData\wsl'
$winDataRoot = 'E:\DockerData\windows'

# Self-elevate: the installer writes to Program Files-equivalent locations and registers a
# service, neither of which is possible unelevated.
$identity = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $identity.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host 'Not elevated - relaunching with UAC. Approve the prompt.' -ForegroundColor Yellow
    Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath
    )
    return
}

if (-not (Test-Path $installer)) {
    throw "Installer not found at $installer"
}

Write-Host "Installer : $installer" -ForegroundColor Cyan
Write-Host "  version : $((Get-Item $installer).VersionInfo.ProductVersion)" -ForegroundColor Cyan
Write-Host "Install to: $installDir" -ForegroundColor Cyan
Write-Host "WSL disk  : $wslDataRoot" -ForegroundColor Cyan
Write-Host ''
Write-Host 'This takes several minutes and may not print anything while it works.' -ForegroundColor Yellow

# install (not the default GUI flow) so the flags are honoured, and -Wait so this script
# does not return before the install has actually finished.
#
# --always-run-service sets com.docker.service to start automatically. Without it every
# later Docker start raises its own UAC prompt, which defeats the point of approving one
# now. It is the reason this is an all-users install rather than --user: per-user mode
# cannot register the service, and per the docs switching modes later means a full
# uninstall and reinstall.
$argList = @(
    'install',
    '--accept-license',
    '--always-run-service',
    "--installation-dir=$installDir",
    "--wsl-default-data-root=$wslDataRoot",
    "--windows-containers-default-data-root=$winDataRoot"
)

$proc = Start-Process -FilePath $installer -ArgumentList $argList -Wait -PassThru
Write-Host ''
Write-Host "Installer exit code: $($proc.ExitCode)" -ForegroundColor $(if ($proc.ExitCode -eq 0) { 'Green' } else { 'Red' })

if ($proc.ExitCode -eq 0) {
    Write-Host 'Done. Do NOT launch Docker Desktop yet - tell Claude, which will verify the' -ForegroundColor Green
    Write-Host 'paths are really on E: before first start creates the data disk.' -ForegroundColor Green
} else {
    Write-Host 'Install did not succeed. Report the exit code above.' -ForegroundColor Red
}

Write-Host ''
Read-Host 'Press Enter to close'
