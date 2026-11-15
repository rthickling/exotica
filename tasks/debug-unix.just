[unix]
[private]
[script]
_debug-check-jdk:
    "$EXOTICA_ROOT/.tools/jdk-21/bin/java" -version
    echo 'Native JDK verified. Use .tools/jdk-21 as the IDE Java runtime.'

[unix]
[private]
[script]
_debug-db:
    cd "$EXOTICA_ROOT"
    if docker container inspect exotica-debug-db >/dev/null 2>&1; then
        docker start exotica-debug-db >/dev/null
    else
        docker run -d --name exotica-debug-db \
            -e POSTGRES_DB=lab -e POSTGRES_USER=lab -e POSTGRES_PASSWORD=lab-local-only \
            -p 127.0.0.1:55432:5432 \
            postgres:15@sha256:c961aa287d8698297cb26cdfadfbe9fd2cbaf77e53cfffe9636e8d8a1e4d842c >/dev/null
    fi
    i=0
    while [ "$i" -lt 30 ]; do
        if docker exec exotica-debug-db pg_isready -U lab -d lab >/dev/null 2>&1; then
            echo 'Debug database ready on 127.0.0.1:55432; existing data preserved.'
            echo 'Stop any previous IDE debug session/tasks before starting Debug stack. Host port owners are never killed.'
            exit 0
        fi
        i=$((i + 1))
        sleep 1
    done
    echo 'Debug database did not become ready. Check port 55432 and container logs.' >&2
    exit 1

[unix]
[private]
[script]
_debug-stop-db:
    if docker container inspect exotica-debug-db >/dev/null 2>&1; then
        docker stop exotica-debug-db >/dev/null
    fi
    echo 'Exotica containers stopped; debug database data and all host processes preserved.'
