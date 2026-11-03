# ADR-003: Two databases, one schema policy

- **Status:** Proposed. Needs sign-off from Yash (@StupidAfCoder) and Dushyant.
- **Date:** 2026-11-02
- **Supersedes:** nothing. Extends ADR-002, which chose PostgreSQL and versioned
  Flyway migrations but said nothing about which database people develop
  against.
- **Raised by:** the database hardening in pull request #2, which makes the
  hosted database safe but leaves no supported way to run the API locally.

## Context

Two facts now pull in opposite directions.

The hosted database is a Supabase PostgreSQL instance whose schema **predates
Flyway history**. Nobody knows whether `flyway_schema_history` exists there, so
automatic migrations could apply `V1__init.sql` to a database that already has
those tables, or skip migrations it thinks are applied. Pull request #2 handles
that correctly: `spring.flyway.enabled=false`, `sslmode=verify-full` with a
downloaded CA certificate, credentials kept out of the JDBC URL, and a
`DatabaseConnectionSafety` guard that refuses to start if any of that is
relaxed. The guard exempts one profile, `integration-test`.

That protection has a cost. With it in place:

- the API cannot start against a locally installed PostgreSQL, because a default
  install has no TLS;
- Flyway never runs, so a fresh database has no tables at all;
- `docker-compose.yml` no longer includes a `db` service, so `docker compose up`
  alone cannot produce a working API;
- every developer needs Supabase credentials and a certificate file before they
  can run a single request.

The team still needs to iterate quickly for the next three weeks, and the
identity smoke script needs to run repeatedly against a throwaway database —
it registers accounts and revokes sessions, which is exactly what should not
happen to shared data.

## Decision

Run **two explicitly named environments** against **one schema policy**.

1. **`local` profile** — a disposable PostgreSQL on the developer's machine,
   `sslmode=disable`, Flyway enabled. This is the default for development and
   for the smoke script. Never used by a deployment.
2. **Everything else (default config, CI, hosted)** — unchanged from pull
   request #2: `sslmode=verify-full`, Flyway disabled in the running
   application, and the startup guard active.
3. **CI tests both paths.** The existing hardened path stays as it is (TLS
   database, pre-created schema, no Flyway history). A second job boots the API
   with the `local` profile against a plain disposable database and runs the
   identity smoke script, so the path developers actually use is also covered.
4. **Migrations are applied deliberately, never by a running deployment.**
   Locally and in CI, Flyway runs and is free to create the schema from empty.
   Hosted, `V1` is already applied or presupposed; every later change is a
   forward-only `V2+`, reviewed and applied with an explicit
   `mvn flyway:migrate` against a checked-out plan, with the resulting
   `flyway_schema_history` recorded in the runbook. `V1__init.sql` is never
   edited again.

The guard's exemption list is exactly `integration-test` and `local`:

```java
@Profile("!integration-test & !local")
```

A misspelled or invented profile must still fail closed, so a test asserts that
an unknown profile name does not bypass the check.

Sketch of the profile (in `application-local.yml`, not in the default file):

```yaml
spring:
  datasource:
    url: ${DATABASE_URL:jdbc:postgresql://localhost:5432/fixloop}
    username: ${DATABASE_USER:fixloop}
    password: ${DATABASE_PASSWORD:}
    hikari:
      data-source-properties:
        sslmode: disable
  flyway:
    enabled: true
```

## Consequences

**Good.** Register, log in, run the smoke script and iterate in seconds without
a network hop or a secret. Test data stays disposable, so security tests can be
destructive. The hosted database keeps a verifiable TLS connection and no
surprise migrations. Anyone reading the config can see which environment they
are in, because it is a named profile rather than an environment variable that
happens to be unset.

**Costs.** Two configurations to keep honest, which is why CI must exercise
both. The `local` profile is a deliberate hole in the guard; it is a hole with
a name, an allow-list of one, and a test, but it is still a hole. Documentation
has to say plainly that `local` against a hosted database is forbidden.

**Failure mode to watch.** Someone runs `--spring.profiles.active=local` against
the Supabase URL to "make the API start". Mitigation: the `local` profile logs
one `WARN` line at startup naming the database host and stating that TLS
verification is off and Flyway is enabled, so the mistake is visible in the
first ten lines of the log.

## Alternatives considered

- **Everyone develops against the shared Supabase database.** Simplest config,
  and the closest match to production. Rejected: the smoke script would create
  and revoke real sessions in shared data, five registrations per hour per IP
  would throttle normal testing, and every machine would need the database
  password. It also makes "did my change break it, or did someone else's?" a
  daily question three weeks before a deadline.
- **Local only; add the hosted path when deployment happens.** Rejected: the
  deployment gate (G1) is already overdue, and discovering the TLS and
  migration-history problems on deploy day is precisely the risk pull request #2
  removes.
- **Give up on Flyway and manage the hosted schema by hand.** Rejected: the
  bible requires versioned migrations, the project is graded on them, and
  hand-managed schemas are how the current unknown state came about.

## Follow-up work

| Item | Owner | Notes |
| --- | --- | --- |
| Add `application-local.yml`, extend the guard exemption, add the fail-closed test | Dev | small, reviewable, lands after pull request #2 |
| Restore a `db` service in `docker-compose.yml` under a `local` profile | Dev | optional for anyone with a native PostgreSQL |
| Second CI job running the smoke script against a local-profile API | Dev | also removes the current approval friction for fork pull requests |
| Confirm the hosted `flyway_schema_history` state against Supabase | Yash | needs dashboard access; blocks the first `V2` |
| README development section: one supported local path, no alternatives | Dev | replaces the current Compose-and-native split |
