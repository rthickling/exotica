[windows]
[private]
[script]
[extension('.ps1')]
_debug-check-jdk:
    $ErrorActionPreference = 'Stop'
    & "$env:EXOTICA_ROOT/.tools/jdk-21/bin/java.exe" -version
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Output 'Native JDK verified. Use .tools/jdk-21 as the IDE Java runtime.'

[windows]
[private]
[script]
[extension('.ps1')]
_debug-db:
    Set-Location -LiteralPath $env:EXOTICA_ROOT
    docker container inspect exotica-debug-db *> $null
    if ($LASTEXITCODE -eq 0) {
        docker start exotica-debug-db | Out-Null
    } else {
        docker run -d --name exotica-debug-db `
            -e POSTGRES_DB=lab -e POSTGRES_USER=lab -e POSTGRES_PASSWORD=lab-local-only `
            -p 127.0.0.1:55432:5432 `
            postgres:15@sha256:c961aa287d8698297cb26cdfadfbe9fd2cbaf77e53cfffe9636e8d8a1e4d842c | Out-Null
    }
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    for ($i = 0; $i -lt 30; $i++) {
        docker exec exotica-debug-db pg_isready -U lab -d lab *> $null
        if ($LASTEXITCODE -eq 0) {
            Write-Output 'Debug database ready on 127.0.0.1:55432; existing data preserved.'
            Write-Output 'Stop any previous IDE debug session/tasks before starting Debug stack. Host port owners are never killed.'
            exit 0
        }
        Start-Sleep -Seconds 1
    }
    Write-Error 'Debug database did not become ready. Check port 55432 and container logs.'
    exit 1

[windows]
[private]
[script]
[extension('.ps1')]
_debug-stop-db:
    docker container inspect exotica-debug-db *> $null
    if ($LASTEXITCODE -eq 0) {
        docker stop exotica-debug-db | Out-Null
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    Write-Output 'Exotica containers stopped; debug database data and all host processes preserved.'
    exit 0
