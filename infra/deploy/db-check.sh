#!/usr/bin/env bash
#
# Answers one question: can this computer reach the hosted database, with TLS
# verified, and does that database have the tables and columns this API needs?
#
# It uses psql from the official Postgres image, so it proves the database
# independently of the API. It is read-only: nothing is created, altered or
# deleted. Run it before `docker compose up`, so a connection problem is not
# confused with an application problem.
#
#   bash infra/deploy/db-check.sh
#
# With --cleanup it instead removes the throwaway smoke-* accounts left by
# infra/smoke/auth-smoke.sh (that one writes to the database):
#
#   bash infra/deploy/db-check.sh --cleanup
#
# It reads .env for DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD and
# SUPABASE_CA_CERT_PATH, and never prints the password or the full URL.

set -euo pipefail

cd "$(dirname "$0")/../.."

POSTGRES_IMAGE="${POSTGRES_IMAGE:-postgres:16}"

passed=0
warnings=0
failed=0

section() { printf '\n== %s\n' "$1"; }
pass() { passed=$((passed + 1)); printf '  ok      %s\n' "$1"; }
warn() { warnings=$((warnings + 1)); printf '  warn    %s\n' "$1"; }
fail() { failed=$((failed + 1)); printf '  FAILED  %s\n' "$1"; }

summary() {
    printf '\n%s passed, %s warnings, %s failed\n' "$passed" "$warnings" "$failed"
    if [ "$failed" -eq 0 ]; then
        printf 'The database is reachable with verified TLS and has the expected schema.\n'
        printf 'Next: docker compose up --build -d && docker compose ps\n'
        exit 0
    fi
    printf 'Fix the failures above before starting the API. Nothing was changed in the database.\n'
    exit 1
}

section "Configuration"

if [ -f .env ]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
    pass "Read .env"
else
    warn "No .env in this directory; using environment variables as they are"
fi

missing=""
for name in DATABASE_URL DATABASE_USER DATABASE_PASSWORD SUPABASE_CA_CERT_PATH; do
    if [ -z "$(eval "printf '%s' \"\${$name:-}\"")" ]; then
        missing="$missing $name"
    fi
done
if [ -n "$missing" ]; then
    fail "Not set:$missing"
    summary
fi
pass "All four database variables are set"

if [ ! -f "$SUPABASE_CA_CERT_PATH" ]; then
    fail "The CA certificate is not a file: $SUPABASE_CA_CERT_PATH"
    summary
elif [ ! -r "$SUPABASE_CA_CERT_PATH" ]; then
    fail "The CA certificate is not readable: $SUPABASE_CA_CERT_PATH"
    summary
elif ! grep -q 'BEGIN CERTIFICATE' "$SUPABASE_CA_CERT_PATH"; then
    fail "$SUPABASE_CA_CERT_PATH is not a PEM certificate"
    summary
else
    pass "CA certificate present and readable"
fi

case "$DATABASE_URL" in
    jdbc:postgresql://*) ;;
    *) fail "DATABASE_URL must start with jdbc:postgresql://"; summary ;;
esac

if printf '%s' "$DATABASE_URL" | grep -q '[?@]'; then
    fail "DATABASE_URL must not contain credentials or query parameters"
    summary
fi

rest="${DATABASE_URL#jdbc:postgresql://}"
host_port="${rest%%/*}"
database_name="${rest#*/}"
if [ -z "$host_port" ] || [ "$database_name" = "$rest" ] || [ -z "$database_name" ]; then
    fail "DATABASE_URL must look like jdbc:postgresql://host:port/database"
    summary
fi
database_host="${host_port%%:*}"
database_port="${host_port##*:}"
if [ "$database_port" = "$database_host" ]; then
    database_port="5432"
fi
pass "Parsed host, port $database_port and database $database_name without printing them"

if [ "$database_port" = "6543" ]; then
    warn "Port 6543 is the transaction pooler; this long-lived Java pool expects 5432 (session or direct)"
fi

if ! command -v docker > /dev/null 2>&1; then
    fail "docker is not installed or not on PATH"
    summary
fi
pass "docker is available"

cert_path="$(realpath "$SUPABASE_CA_CERT_PATH" 2> /dev/null || printf '%s' "$SUPABASE_CA_CERT_PATH")"
conninfo="host=$database_host port=$database_port user=$DATABASE_USER dbname=$database_name"
conninfo="$conninfo sslmode=verify-full sslrootcert=/certs/ca.crt"

if [ "${1:-}" = "--cleanup" ]; then
    section "Removing the throwaway smoke accounts (this writes)"
    if docker run --rm -i \
        -v "$cert_path:/certs/ca.crt:ro" \
        -v "$(realpath infra/smoke):/smoke:ro" \
        -e PGPASSWORD="$DATABASE_PASSWORD" \
        "$POSTGRES_IMAGE" psql "$conninfo" -X -v ON_ERROR_STOP=1 -f /smoke/cleanup.sql; then
        printf '\nSmoke accounts removed. Nothing else was touched.\n'
        exit 0
    fi
    printf '\nCleanup failed; the accounts are harmless but still there.\n'
    exit 1
fi

query="
select 'version|' || current_setting('server_version');
select 'database|' || current_database();
select 'account|' || current_user;
select 'tls|' || coalesce((select ssl::text from pg_stat_ssl where pid = pg_backend_pid()), 'unknown');
select 'table|' || table_name from information_schema.tables
  where table_schema = 'public'
    and table_name in ('users', 'settings', 'refresh_tokens', 'attachments', 'complaints', 'departments')
  order by 1;
select 'column|' || table_name || '.' || column_name from information_schema.columns
  where table_schema = 'public'
    and ((table_name = 'users' and column_name in ('email', 'password_hash', 'must_change_password', 'active'))
      or (table_name = 'refresh_tokens' and column_name in ('token_hash', 'replaced_by', 'expires_at'))
      or (table_name = 'settings' and column_name = 'value'))
  order by 1;
select 'view|' || table_name from information_schema.views
  where table_schema = 'public' order by 1;
select 'history|' || count(*) from information_schema.tables
  where table_schema = 'public' and table_name = 'flyway_schema_history';
"

section "Connection through psql (independent of the API)"

note() { printf '          %s\n' "$1"; }

output=""
if output="$(docker run --rm \
    -v "$cert_path:/certs/ca.crt:ro" \
    -e PGPASSWORD="$DATABASE_PASSWORD" \
    "$POSTGRES_IMAGE" psql "$conninfo" -X -v ON_ERROR_STOP=1 -tA -c "$query" 2>&1)"; then
    pass "Connected and authenticated with sslmode=verify-full"
else
    fail "Could not connect"
    printf '%s\n' "$output" | sed 's/^/          /' | head -20
    cat <<'HINTS' | sed 's/^ *//; /^$/d; s/^/          /'

          password authentication failed  -> the database password in .env is not current
          SSLHandshake / PKIX / hostname  -> the CA file or the host does not match this endpoint
          could not translate host name   -> wrong host, or no IPv6 for a direct connection (use the session pooler)
          Connection timed out            -> network, firewall, or the project is paused in the dashboard
HINTS
    summary
fi

value_of() { printf '%s\n' "$output" | sed -n "s/^$1|//p" | head -1; }

server_version="$(value_of version)"
database_name_actual="$(value_of database)"
account="$(value_of account)"
tls="$(value_of tls)"

note "server $server_version, database $database_name_actual, account $account"
if [ "$tls" = "true" ]; then
    pass "The session is encrypted"
else
    fail "The session is not encrypted (ssl=$tls); verify-full should make this impossible"
fi

section "Schema this API needs"

for table in users settings refresh_tokens; do
    if printf '%s\n' "$output" | grep -qx "table|$table"; then
        pass "Table $table exists"
    else
        fail "Table $table is missing; registration and login will fail"
    fi
done

for table in departments attachments complaints; do
    if printf '%s\n' "$output" | grep -qx "table|$table"; then
        pass "Table $table exists"
    else
        warn "Table $table is missing; only needed by parts not built yet"
    fi
done

expect_column() {
    if printf '%s\n' "$output" | grep -qx "column|$1"; then
        pass "Column $1 exists"
    else
        fail "Column $1 is missing; the code that uses it will fail"
    fi
}

expect_column "users.email"
expect_column "users.password_hash"
expect_column "users.must_change_password"
expect_column "users.active"
expect_column "refresh_tokens.token_hash"
expect_column "refresh_tokens.expires_at"
expect_column "settings.value"

section "Migration state"

if [ "$(value_of history)" = "1" ]; then
    warn "flyway_schema_history exists: someone has baselined this database. Do not run V1 again."
else
    pass "No flyway_schema_history, so Flyway has never run here (matches the runbook)"
fi

views="$(printf '%s\n' "$output" | sed -n 's/^view|//p' | tr '\n' ' ')"
if [ -n "$views" ]; then
    warn "Public views visible: $views- review who may SELECT them from the public REST API"
else
    pass "No public views"
fi

summary
