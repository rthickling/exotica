import 'tasks/platform.just'

# Build and start the complete stack; wait for healthy services.
up:
    just compose up --build -d --wait

# Build the application, pricer and UI images (including Angular type checks).
build:
    just compose build app pricer ui

# Stop services while preserving the database.
down:
    just compose down --remove-orphans

# Delete this project's synthetic database and stop services.
reset:
    just compose down --volumes --remove-orphans

# Show service status.
status:
    just compose ps

# Show recent logs (optionally choose services).
[positional-arguments]
[script]
[extension('.ps1')]
logs *services:
    {{_forward}} compose logs --tail=100 {{_arguments}}
    {{_forward_status}}

# Check running HTTP services and the UI's application proxy inside Compose.
health:
    just compose run --rm --no-deps tools scripts/health.py

# Start the stack and exercise end-to-end journeys; retain evidence in the checkout.
journeys evidence='evidence/latest': up (compose "run" "--rm" "--no-deps" "tools" "scripts/journeys.py" "--base-url" "http://app:8080" "--evidence" evidence)

# Inspect persisted database records.
inspect-db:
    just compose exec -T db psql -U lab -d lab -f /inspect-db.sql

# Run Python unit tests using the container's pinned dependencies.
test-pricer:
    just compose run --build --rm --no-deps pricer-test -m unittest discover -s . -p test_engine.py -v

# Regenerate numerical evidence in the checkout.
checks:
    just compose run --build --rm --no-deps pricer-test checks.py

# Run Gradle in a JDK container with an isolated, healthy test database.
[positional-arguments]
[script]
[extension('.ps1')]
gradle *args:
    {{_forward}} compose -f compose.test.yaml run --build --rm gradle {{_arguments}}
    {{_forward_status}}

# Package a JAR in app/build/libs without starting the test database.
build-app:
    just compose -f compose.test.yaml run --build --rm --no-deps gradle bootJar

# Run all Java tests, including the real PostgreSQL integration tests.
test-app:
    just gradle clean test

# Start the isolated test database (no published host port).
test-db-up:
    just compose -f compose.test.yaml up -d --wait db

# Stop the test project and delete its expendable database.
test-db-down:
    just compose -f compose.test.yaml down --volumes --remove-orphans

# Alias for the complete Java suite, which always includes database tests.
test-integration: test-app

# Run browser tests against the healthy stack; retain user-owned reports.
test-ui: up
    just compose run --build --rm --no-deps ui-test

# Run Python, Java and browser tests. The isolated test database is kept for reuse.
test: test-pricer test-app test-ui

# Run the same complete verification used by GitHub Actions.
ci: test journeys health checks checkpoint

# Record the current source hashes in evidence/checkpoint.json.
checkpoint:
    just compose run --rm --no-deps tools scripts/checkpoint.py

# Materialize the pinned Temurin 21 JDK for host debugging (Cursor Java extension).
[unix]
[script]
debug-jdk:
    cd "$EXOTICA_ROOT"
    jdk_dir='.tools/jdk-21'
    if [ -x "$jdk_dir/bin/java" ]; then
        echo "Host JDK ready: $jdk_dir ($("$jdk_dir/bin/java" -version 2>&1 | head -1))"
        exit 0
    fi
    image='eclipse-temurin:21-jdk@sha256:0a3f329821dcadf54b2a3033a15e2e9f007069a9db43d7f51897e6b80e726b07'
    mkdir -p .tools
    echo "Extracting $image to $jdk_dir ..."
    cid=$(docker create "$image")
    docker cp "$cid:/opt/java/openjdk" .tools/jdk-21-tmp
    docker rm "$cid" >/dev/null
    rm -rf "$jdk_dir"
    mv .tools/jdk-21-tmp "$jdk_dir"
    echo "Host JDK ready: $jdk_dir ($("$jdk_dir/bin/java" -version 2>&1 | head -1))"

[windows]
[script]
[extension('.ps1')]
debug-jdk:
    Set-Location -LiteralPath $env:EXOTICA_ROOT
    $jdkDir = '.tools/jdk-21'
    if (Test-Path -LiteralPath "$jdkDir/bin/java.exe") {
        $ver = & "$jdkDir/bin/java.exe" -version 2>&1 | Select-Object -First 1
        Write-Output "Host JDK ready: $jdkDir ($ver)"
        exit 0
    }
    $image = 'eclipse-temurin:21-jdk@sha256:0a3f329821dcadf54b2a3033a15e2e9f007069a9db43d7f51897e6b80e726b07'
    New-Item -ItemType Directory -Force -Path .tools | Out-Null
    Write-Output "Extracting $image to $jdkDir ..."
    $cid = docker create $image
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    docker cp "${cid}:/opt/java/openjdk" .tools/jdk-21-tmp
    if ($LASTEXITCODE -ne 0) { docker rm $cid | Out-Null; exit $LASTEXITCODE }
    docker rm $cid | Out-Null
    if (Test-Path -LiteralPath $jdkDir) { Remove-Item -Recurse -Force $jdkDir }
    Move-Item .tools/jdk-21-tmp $jdkDir
    $ver = & "$jdkDir/bin/java.exe" -version 2>&1 | Select-Object -First 1
    Write-Output "Host JDK ready: $jdkDir ($ver)"

# Stop host debug listeners and remove the disposable debug database.
[unix]
[script]
debug-clean:
    cd "$EXOTICA_ROOT"
    free_port() {
        port=$1
        pids=''
        if command -v fuser >/dev/null 2>&1; then
            pids=$(fuser "${port}/tcp" 2>/dev/null || true)
        fi
        if [ -z "${pids:-}" ] && command -v lsof >/dev/null 2>&1; then
            pids=$(lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
        fi
        if [ -z "${pids:-}" ]; then
            pids=$(ss -ltnp 2>/dev/null | awk -v p=":$port" '
                index($4, p) && $1 ~ /LISTEN/ {
                    while (match($0, /pid=[0-9]+/)) {
                        pid = substr($0, RSTART + 4, RLENGTH - 4)
                        print pid
                        $0 = substr($0, RSTART + RLENGTH)
                    }
                }' | sort -u)
        fi
        if [ -n "${pids:-}" ]; then
            echo "Stopping listeners on port $port: $pids"
            kill $pids 2>/dev/null || true
            sleep 0.4
            kill -9 $pids 2>/dev/null || true
            if command -v fuser >/dev/null 2>&1; then
                fuser -k -KILL "${port}/tcp" >/dev/null 2>&1 || true
            fi
        fi
    }
    free_port 4200
    free_port 8080
    free_port 8090
    just compose stop app pricer ui >/dev/null 2>&1 || true
    if docker container inspect exotica-debug-db >/dev/null 2>&1; then
        docker rm -f exotica-debug-db >/dev/null
        echo 'Removed exotica-debug-db.'
    fi
    echo 'Debug cleanup done: ports 4200/8080/8090 freed; Compose app/pricer/ui stopped.'

[windows]
[script]
[extension('.ps1')]
debug-clean:
    Set-Location -LiteralPath $env:EXOTICA_ROOT
    function Free-Port([int]$Port) {
        $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        foreach ($connection in $connections) {
            $processId = $connection.OwningProcess
            if ($processId -and $processId -ne 0) {
                Write-Output "Stopping listeners on port ${Port}: $processId"
                Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
            }
        }
    }
    Free-Port 4200
    Free-Port 8080
    Free-Port 8090
    & "$env:EXOTICA_ROOT/tasks/windows.ps1" just --justfile "$env:EXOTICA_ROOT/justfile" compose stop app pricer ui
    docker container inspect exotica-debug-db *> $null
    if ($LASTEXITCODE -eq 0) {
        docker rm -f exotica-debug-db | Out-Null
        Write-Output 'Removed exotica-debug-db.'
    }
    Write-Output 'Debug cleanup done: ports 4200/8080/8090 freed; Compose app/pricer/ui stopped.'

# Free host debug ports and ensure Postgres is reachable on localhost:55432.
[unix]
[script]
debug-deps:
    cd "$EXOTICA_ROOT"
    just debug-clean
    just debug-jdk
    image='postgres:15@sha256:c961aa287d8698297cb26cdfadfbe9fd2cbaf77e53cfffe9636e8d8a1e4d842c'
    docker run -d --name exotica-debug-db \
        -e POSTGRES_DB=lab \
        -e POSTGRES_USER=lab \
        -e POSTGRES_PASSWORD=lab-local-only \
        -p 127.0.0.1:55432:5432 \
        "$image" >/dev/null
    i=0
    while [ "$i" -lt 30 ]; do
        if docker exec exotica-debug-db pg_isready -U lab -d lab >/dev/null 2>&1; then
            echo 'Debug dependencies ready: Postgres on 127.0.0.1:55432; host JDK in .tools/jdk-21; listeners cleared.'
            echo 'Reload the Cursor window once so Java picks up .tools/jdk-21, then start compound "Debug stack".'
            exit 0
        fi
        i=$((i + 1))
        sleep 1
    done
    echo 'Postgres on 55432 did not become ready.' >&2
    exit 1

[windows]
[script]
[extension('.ps1')]
debug-deps:
    Set-Location -LiteralPath $env:EXOTICA_ROOT
    & "$env:EXOTICA_ROOT/tasks/windows.ps1" just --justfile "$env:EXOTICA_ROOT/justfile" debug-clean
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & "$env:EXOTICA_ROOT/tasks/windows.ps1" just --justfile "$env:EXOTICA_ROOT/justfile" debug-jdk
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $image = 'postgres:15@sha256:c961aa287d8698297cb26cdfadfbe9fd2cbaf77e53cfffe9636e8d8a1e4d842c'
    docker run -d --name exotica-debug-db `
        -e POSTGRES_DB=lab `
        -e POSTGRES_USER=lab `
        -e POSTGRES_PASSWORD=lab-local-only `
        -p 127.0.0.1:55432:5432 `
        $image | Out-Null
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    for ($i = 0; $i -lt 30; $i++) {
        docker exec exotica-debug-db pg_isready -U lab -d lab *> $null
        if ($LASTEXITCODE -eq 0) {
            Write-Output 'Debug dependencies ready: Postgres on 127.0.0.1:55432; host JDK in .tools/jdk-21; listeners cleared.'
            Write-Output 'Reload the Cursor window once so Java picks up .tools/jdk-21, then start compound "Debug stack".'
            exit 0
        }
        Start-Sleep -Seconds 1
    }
    Write-Error 'Postgres on 55432 did not become ready.'
    exit 1
