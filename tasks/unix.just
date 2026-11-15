[unix]
set shell := ['sh', '-cu']
[unix]
set script-interpreter := ['sh', '-eu']

# Keep reports, caches and evidence owned by the invoking Linux/macOS user.
[unix]
export LOCAL_UID := `id -u`
[unix]
export LOCAL_GID := `id -g`

[unix]
_forward := 'exec just --justfile "$EXOTICA_ROOT/justfile"'
[unix]
_arguments := '"$@"'
[unix]
_forward_status := ''

# Run any Compose command (optionally use a standalone v2+ binary).
[unix]
[positional-arguments]
[script]
compose *args:
    cd "$EXOTICA_ROOT"
    if [ -n "${DOCKER_COMPOSE_BIN:-}" ]; then
        exec "$DOCKER_COMPOSE_BIN" "$@"
    fi
    exec docker compose "$@"
