import 'tasks/platform.just'
import 'tasks/debug.just'

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
ci: test-debug test journeys health checks checkpoint

# Record the current source hashes in evidence/checkpoint.json.
checkpoint:
    just compose run --rm --no-deps tools scripts/checkpoint.py
