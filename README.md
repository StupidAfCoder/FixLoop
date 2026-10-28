# Fix Loop

A campus complaint and resolution tracking system. This repository is an early
**foundation**, based on the supplied [project bible](docs/bible/Fix_Loop_Project_Bible_v0.2.md).
It is not yet a working complaint system and must not be used with real users
or real complaints.

## What exists today

- Java 21 / Spring Boot 3 API, public health checks, Swagger UI and a
  deny-by-default security configuration.
- Local PostgreSQL 16 and API containers. Flyway applies the existing `V1`
  migration to a **fresh local database**; the original SQL has not been edited.
- Optional local MinIO and Mailpit containers for future storage/email work,
  plus a small, explicitly synthetic reference-data seed.
- A Flutter 3.41.9 / Dart 3.11.5 login **placeholder** for Android and web.
  Its controls are disabled; no credentials are collected.
- Tests and GitHub Actions configuration for the API, database and Flutter shell.

**Not implemented:** registration, login, JWTs, complaint endpoints, routing,
workflow, authorization by role/ownership, uploads, notifications, a real API
client, or a public deployment. The existing tables alone do not enforce all
those rules. The starter is not production-ready.

## Important: exposed database credential

An apparent Supabase database password was present in a tracked configuration
file. The file has been removed from the current working tree, but the password
**remains in Git history**. Treat it as compromised: someone with access to the
Supabase project should rotate the database password immediately, review access,
and update any legitimate deployments through their secret manager. Do not
share the new credential in chat or commit it. Removing history later does not
replace rotation. No Supabase connection is needed for this setup.

## Run locally with Docker

Requirements: Docker Desktop (or Docker Engine) with the Compose v2 plugin,
Git, and an internet connection for the first image build. Java and Maven do
**not** need to be installed locally to start the API. If Docker is not yet
installed, install and start Docker Desktop/Engine first, then check
`docker --version` and `docker compose version`.

From the repository root:

1. Copy `.env.example` to `.env` (`cp .env.example .env` on macOS/Linux;
   `Copy-Item .env.example .env` in PowerShell).
2. Edit `.env` locally and set **`POSTGRES_PASSWORD`** to a new, non-empty,
   local-only password. For example, `openssl rand -hex 24` generates one on
   systems with OpenSSL. Keep the password private and do not reuse a Supabase
   credential. The blank database/user values default to `fixloop`; port
   settings can stay blank. `.env` is ignored by Git.
3. Start the stack:

   ```sh
   docker compose up --build -d
   docker compose ps
   ```

   The first build downloads dependencies, runs the Java unit tests, waits for
   PostgreSQL to become healthy, and applies the migration. Check progress with
   `docker compose logs -f api` (Ctrl+C stops viewing logs, not the stack).
4. Open or query:

   - <http://localhost:8080/api/v1/health> — process liveness only.
   - <http://localhost:8080/actuator/health> — includes the database; returns
     `UP` when ready and a 503 when an essential health check fails.
   - <http://localhost:8080/swagger-ui/index.html> — generated API docs.
   - <http://localhost:8080/v3/api-docs> — generated OpenAPI JSON.

If you set `API_PORT`, use that port instead of 8080. Only health and API docs
are public; all other paths are denied. The database and API bind to localhost
by default. If the team later needs a phone on the LAN to reach the API,
`API_BIND_ADDRESS=0.0.0.0` opts in to exposing it on the host; do this only on a
trusted network and configure authorization and CORS before connecting a real
client. The Flutter placeholder does not call the API yet.

`docker compose down` stops the stack **without deleting database data**. If a
local password is changed after the database was first created, the existing
volume still has the old password: restore the old value or, only for disposable
local data, reset with `docker compose down --volumes`. That command permanently
deletes the local database volume. Do not use it against a system with data you
need.

### Optional: synthetic reference data

After the API has started and Flyway has finished, this command inserts a few
**made-up** departments, locations, categories, routing rules and SLA examples:

```sh
docker compose exec -T db sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < infra/seed/seed.sql
```

The insert-only script is repeatable on a fresh local database. It creates no
users or complaints and does not implement routing; it is not real campus data.
Do not run it against Supabase or an existing real database.

### Optional: local storage and email tools

Set `MINIO_ROOT_USER` (at least 3 characters) and `MINIO_ROOT_PASSWORD` (at
least 8 characters) in `.env` using unique local-only values, then run:

```sh
docker compose -f docker-compose.yml -f infra/compose-tools.yml up -d
```

MinIO's console is at <http://localhost:9001>; Mailpit's inbox is at
<http://localhost:8025> (SMTP port 1025). These services bind to localhost, are
not connected to the API yet, and are for local development only. To stop the
full stack without deleting its volumes:

```sh
docker compose -f docker-compose.yml -f infra/compose-tools.yml down
```

## Flutter on your computer

Your Flutter **3.41.9 / Dart 3.11.5** is the target version. See
[apps/mobile/README.md](apps/mobile/README.md) for the one-time command that
generates the standard Android and web host projects with your installed SDK,
then runs analysis, tests and the placeholder. The generated host projects are
not committed yet. This screen cannot log in or submit complaints.

## Testing

- `docker compose build api` builds the JAR and runs the Java unit tests.
- With Java 21, Maven 3.9+ and a running Docker daemon installed locally:
  `mvn -f apps/api/pom.xml verify`. This also runs the Testcontainers
  integration tests against a **new disposable PostgreSQL 16 container**; they
  fail rather than silently skipping if Docker is unavailable.
- In `apps/mobile`, after the one-time Flutter setup: `flutter analyze` and
  `flutter test`.
- CI is configured to run these checks, validate Compose, build and smoke-test
  the API container, and build Flutter web.

## Database migration rules

`apps/api/src/main/resources/db/migration/V1__init.sql` is the existing schema
and has intentionally been left unchanged. Flyway is configured **not** to
baseline a non-empty database automatically and not to clean databases. Never
edit an applied migration: use a reviewed, forward-only `V2`, `V3`, etc.

The Supabase database's current state and Flyway history are unknown. **Do not
point this starter at it yet**; first inspect the actual schema and migration
history with someone who has access and agree on a safe migration plan. In
particular, V1 does not yet contain the bible's optional `share_identity`
column or `REPORTER_REVEALED` event type. These and other gaps need a reviewed
follow-up migration and authorization tests before those features can work.

## Project layout

```text
apps/api/           Spring Boot application, tests and Dockerfile
apps/mobile/        Flutter source and widget test (host files generated locally)
docs/bible/         Supplied project bible (v0.2)
docs/openapi/       Small contract for the endpoints that exist today
infra/seed/         Optional synthetic reference data
infra/compose-tools.yml  Optional MinIO and Mailpit
```

Keep secrets and real student data out of the repository. See
[AI_USAGE.md](AI_USAGE.md) for the required AI-assistance log.
