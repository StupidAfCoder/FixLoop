# Operations runbook

**Status: nothing is deployed.** The gate in the project bible (G1: a public,
working health URL plus register and login against the deployed API) is not met.
This file is the plan of record for meeting it, written so that whoever does the
deploy does not have to rediscover the reasons behind each step.

Related: ADR-003 (which database each environment uses, and how migrations are
applied) and the root README (running the stack locally).

## Environments

| Environment | Database | TLS | Flyway | Who uses it |
| --- | --- | --- | --- | --- |
| Local (`local` profile) | PostgreSQL on the developer's machine | off | runs, may create the schema | everyone, every day |
| CI | disposable PostgreSQL in a job, and a TLS-enabled one for the hardened path | both paths tested | runs on the disposable one only | GitHub Actions |
| Hosted | Supabase PostgreSQL | `sslmode=verify-full` with the project CA | **never runs in the application**; migrations are applied deliberately | the deployed API |

## What is known about the hosted database

Recorded here so nobody re-derives it, and so the limits of the knowledge are
visible:

- A **read-only inspection** on 2026-11-02 found the application tables present
  but **no visible `flyway_schema_history`**. Flyway has therefore never run
  against it, and applying `V1__init.sql` by hand would collide with tables that
  already exist.
- The same inspection found **three public analytics views** with anonymous
  SELECT and owner-run behaviour. They may expose aggregate information through
  Supabase's public REST schema; external REST access was never tested. Review
  this with the database owner before real data goes anywhere near it.
- **A connector inspection is not a JDBC test.** The first real verification of
  the application's TLS connection happens in the environment that runs it.
- The hosted database has never been baselined, seeded or policy-changed by this
  team. `infra/seed/seed.sql` is for disposable databases only.

## Proving the database connection

`infra/deploy/db-check.sh` answers "is the database reachable, encrypted and
shaped the way the API expects" without starting the API. It connects with
`psql` from the Postgres image using the same CA certificate and
`sslmode=verify-full`, then checks the tables and columns the code queries. It is
read-only and prints no secrets. Run it before `docker compose up`, so a
connection failure is not mistaken for an application failure, and again from
the deployment host before pointing real users at it: a local success does not
prove the deployed environment's egress path.

## Before the first deploy

These are not optional, and most of them are somebody else's decision:

1. **Rotate the database credential that was committed to Git earlier.** Removing
   it from the current tree does not remove it from history.
2. **Decide the database story** — ADR-003. It must be accepted, not merely
   written, because it decides whether the hosted database is the only one or
   one of two.
3. **Agree what to do about the hosted schema.** A read-only inspection on
   2026-11-02 found that the application tables exist but no `flyway_schema_history`
   is visible, which means Flyway has never run there and `V1__init.sql` must not
   be applied to it. Before any migration: review schema equivalence with
   `V1__init.sql`, confirm backups, and agree who applies migrations. Until then
   `spring.flyway.enabled` stays `false` and nothing is baselined.
4. **Provision the platform** (host, region, a public HTTPS hostname) and put the
   secrets in its secret store, not in a file in the repository.
5. **Confirm the allowed origins** — the Flutter web build's origin, if it is
   used. Leave the list empty if only the Android client calls the API.

## Secrets and settings

| Variable | Where it comes from | Notes |
| --- | --- | --- |
| `JWT_SECRET` | `openssl rand -hex 32` | At least 32 bytes. Changing it logs every user out; that is the correct emergency response to a leak. |
| `JWT_ISSUER` | The deployed hostname, e.g. `https://fixloop-api.example` | Must be a URI. Tokens issued under one issuer are not accepted under another. |
| `DATABASE_URL` | The platform's database host, port and name only | No user, no password, no query string. |
| `DATABASE_USER`, `DATABASE_PASSWORD` | The database provider | Never in the URL, never in Git. |
| `DATABASE_SSL_ROOT_CERT` | The provider's CA certificate | Path inside the container. |
| `ALLOWED_EMAIL_DOMAINS` | The college's domain(s) | Bare lowercase domains, comma separated. |
| `ALLOWED_WEB_ORIGINS` | The web client's origin | Empty refuses all cross-origin browser calls. Never `*`. |
| `TRUST_FORWARDED_FOR` | `true` only behind a proxy you control | Otherwise a client can choose its own rate-limit key. |
| `RATE_LIMIT_ENABLED` | `true` | `false` is for a single local run, never a deployment. |
| `ATTACHMENT_STORAGE_DIR` | A path on a persistent volume | Uploaded photographs. Blank means the container's temporary directory, so they vanish on restart. `ATTACHMENT_MAX_BYTES` defaults to 8 MB. |

## The preflight check

Before every deploy, run the configuration check against exactly the values the
deployment will receive:

```bash
JWT_SECRET=... JWT_ISSUER=... DATABASE_URL=... \
DATABASE_USER=... DATABASE_PASSWORD=... \
ALLOWED_EMAIL_DOMAINS=... ALLOWED_WEB_ORIGINS=... \
bash infra/deploy/preflight.sh --behind-proxy

# local or CI runs instead:
bash infra/deploy/preflight.sh --allow-local
```

It exits non-zero on anything that would misbehave in production — a blank or
placeholder signing key, credentials inside the JDBC URL, a wildcard origin, the
rate limiter switched off, `TRUST_FORWARDED_FOR` set without a proxy. It prints
no secret values. It is deliberately stricter than Spring Boot, which will start
happily with several of those mistakes in place.

## Deploy

1. Build the image from a commit that passed CI (`apps/api/Dockerfile` runs the
   fast tests as part of the build).
2. Set the variables above in the platform's secret store.
3. Run the preflight check against those values.
4. Deploy, then confirm in order:
   - `GET /actuator/health` returns `{"status":"UP"}`;
   - `GET /api/v1/health` returns the application's own health payload;
   - `/v3/api-docs` and Swagger UI are reachable, or are switched off
     deliberately (`springdoc.api-docs.enabled=false`) — they are on by default.
5. Run the identity smoke script against the deployment **only with the database
   owner's agreement**, because it creates and revokes real accounts:

   ```bash
   bash infra/smoke/auth-smoke.sh https://fixloop-api.example
   ```

   Prefer a staging deployment for this. On the shared database, five
   registrations per hour per address will throttle it.
6. Watch the first ten minutes of logs. The startup lines say which database host
   was used and whether TLS verification is on — worth reading every time.

## Migrations

Migrations are applied deliberately, never by a running deployment (ADR-003).

- `V1__init.sql` is already applied and is never edited. Further changes are
  forward-only `V2`, `V3`, and so on.
- Apply them with an explicit command against a database you named out loud,
  after reviewing the SQL, at a time when a failure can be fixed.
- Confirm `flyway_schema_history` afterwards and record the version in this file.
- There is no down-migration path. A mistake is corrected by a new forward
  migration, not by reverting one.

## Rollback

The application rolls back; the database does not.

1. Redeploy the previous image tag. The schema is unchanged, so this is safe as
   long as the old code tolerates the current schema.
2. If a migration caused the failure, do not revert it — write and review a
   forward migration that repairs the state.
3. If a bad deploy wrote bad data, restore from backup only with the owner's
   explicit approval, and record what was lost.

## Backups and monitoring

- Confirm the provider's automated backups are on, and that a restore has been
  tested at least once — an untested backup is a hope, not a backup.
- Point an external uptime check at `/actuator/health`. It is cheap and it is the
  difference between finding out from a user and finding out from a monitor.
- Keep the deploy log: commit SHA, who deployed, when, and which migration
  version was current.

## Open items

| Item | Owner | Blocks |
| --- | --- | --- |
| Rotate the exposed database credential | Dev + database owner | everything |
| Accept ADR-003 | Yash, Dushyant | hosted configuration |
| Review hosted schema equivalence with `V1__init.sql`, and the analytics views' access | Dev + database owner | first `V2`, baselining |
| Choose and provision the hosting platform | Dev | the deploy itself |
| Q1: does `bmu.edu.in` email verification exist, and is it required? | Dev | registration policy in production |
| Verify the first JDBC connection from the deployment environment | Dev | goes live |
| Decide who may apply a hosted migration, and when | All | any `V2` |
