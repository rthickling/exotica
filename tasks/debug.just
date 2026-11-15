# Optional IDE debugging support; application/test workflows still need only Docker + just.
import 'debug-unix.just'
import 'debug-windows.just'

# Install and verify a native Temurin 21 JDK without installing host Python.
debug-jdk: (compose "run" "--rm" "--no-deps" "tools" "tasks/setup_jdk.py" "--os" os() "--arch" arch()) _debug-check-jdk

# Stop only Exotica containers; leave host processes and database data intact.
debug-clean:
    just compose stop app pricer ui
    just _debug-stop-db

# Prepare the JDK and reusable debug database; never kill host port owners.
debug-deps: debug-jdk
    just compose stop app pricer ui
    just _debug-db

# Verify debug setup helpers without downloading or changing the host JDK.
test-debug:
    just compose run --rm --no-deps tools -m unittest discover -s tasks -p 'test_*.py' -v
