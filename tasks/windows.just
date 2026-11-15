# Use Windows' built-in PowerShell, including on systems without Git Bash/pwsh.
[windows]
set shell := ['powershell.exe', '-NoLogo', '-NoProfile', '-NonInteractive', '-Command']
[windows]
set script-interpreter := ['powershell.exe', '-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File']

# Docker Desktop handles Windows bind-mount ownership; no Unix id command.
[windows]
export LOCAL_UID := '1000'
[windows]
export LOCAL_GID := '1000'

[windows]
_forward := '& "$env:EXOTICA_ROOT/tasks/windows.ps1" just --justfile "$env:EXOTICA_ROOT/justfile"'
[windows]
_arguments := '@args'
[windows]
_forward_status := 'exit $LASTEXITCODE'

# Run any Compose command (optionally use a standalone v2+ binary).
[windows]
[positional-arguments]
[script]
[extension('.ps1')]
compose *args:
    & "$env:EXOTICA_ROOT/tasks/windows.ps1" compose @args
    exit $LASTEXITCODE
