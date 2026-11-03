#!/usr/bin/env bash
# Check the environment of a deployment before it starts, instead of after.
#
#   bash infra/deploy/preflight.sh                     # a hosted deployment
#   bash infra/deploy/preflight.sh --allow-local       # a local or CI run
#   bash infra/deploy/preflight.sh --behind-proxy      # a trusted reverse proxy sets X-Forwarded-For
#
# Reads the same variable names the API reads (see .env.example) and never
# prints a secret value. Exit codes: 0 acceptable, 1 a check failed, 2 usage.
#
# Deliberately stricter than Spring Boot, which starts happily with a blank
# signing key or a rate limiter switched off. Those defaults are convenient on a
# laptop and are exactly the mistakes that are invisible in production.

set -uo pipefail

allow_local=false
behind_proxy=false
rate_limit_off_allowed=false

for arg in "$@"; do
    case "$arg" in
        --allow-local) allow_local=true ;;
        --behind-proxy) behind_proxy=true ;;
        --allow-rate-limit-off) rate_limit_off_allowed=true ;;
        -h|--help)
            sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *)
            printf 'unknown option: %s\n' "$arg" >&2
            exit 2 ;;
    esac
done

passed=0
warned=0
failed=0

pass() { passed=$((passed + 1)); printf 'PASS %s\n' "$1"; }
warn() { warned=$((warned + 1)); printf 'WARN %s\n' "$1"; }
fail() { failed=$((failed + 1)); printf 'FAIL %s\n' "$1"; }

is_set() { [ -n "${1:-}" ]; }

# Byte length, so a 32-character passphrase of multibyte text is not mistaken
# for 32 bytes of key material.
byte_length() {
    printf '%s' "$1" | LC_ALL=C wc -c | tr -d '[:space:]'
}

printf 'Fix Loop deployment preflight\n'
mode="hosted"
$allow_local && mode="local"
printf '  mode: %s\n\n' "$mode"

# --- JWT_SECRET -------------------------------------------------------------
if is_set "${JWT_SECRET:-}"; then
    secret_bytes=$(byte_length "$JWT_SECRET")
    if [ "$secret_bytes" -ge 32 ]; then
        pass "JWT_SECRET is set ($secret_bytes bytes)"
    else
        fail "JWT_SECRET is only $secret_bytes bytes; HS256 needs at least 32"
    fi

    case "$JWT_SECRET" in
        change-me*|changeme*|secret*|password*|test*|example*|fixloop*)
            fail "JWT_SECRET looks like a placeholder value" ;;
    esac

    if $allow_local; then
        :
    else
        # Guard the obvious reuse of a value that is also tracked in the repo.
        if [ -f .env.example ] && grep -Fqx "JWT_SECRET=$JWT_SECRET" .env.example 2>/dev/null; then
            fail "JWT_SECRET is the value published in .env.example"
        fi
    fi
else
    fail "JWT_SECRET is not set; the API would sign tokens with an ephemeral key and log everyone out on every restart"
fi

# --- JWT_ISSUER -------------------------------------------------------------
issuer="${JWT_ISSUER:-https://fixloop.local}"
if printf '%s' "$issuer" | grep -Eq '^https?://[^[:space:]]+$'; then
    case "$issuer" in
        https://*) pass "JWT_ISSUER is a URI ($issuer)" ;;
        http://*)
            if $allow_local; then
                pass "JWT_ISSUER is a plain-HTTP URI ($issuer), acceptable in local mode"
            else
                fail "JWT_ISSUER is plain HTTP ($issuer); a hosted deployment should publish an https issuer"
            fi ;;
    esac
else
    fail "JWT_ISSUER must be a URI such as https://api.example.edu.in (Spring parses the iss claim as a URL)"
fi

# --- DATABASE_URL, USER, PASSWORD ------------------------------------------
db_url="${DATABASE_URL:-}"
if is_set "$db_url"; then
    if printf '%s' "$db_url" | grep -Eq '^jdbc:postgresql://[^/?]+/[^?]+$'; then
        pass "DATABASE_URL has the expected shape (host, optional port, database)"
    else
        fail "DATABASE_URL must look like jdbc:postgresql://host:5432/database, with no credentials or options"
    fi

    if printf '%s' "$db_url" | grep -Eq '^jdbc:postgresql://[^/]*@'; then
        fail "DATABASE_URL contains credentials; set DATABASE_USER and DATABASE_PASSWORD instead"
    fi

    case "$db_url" in
        *\?*) fail "DATABASE_URL has a query string; TLS and options belong in configuration, not the URL" ;;
    esac

    case "$db_url" in
        *localhost*|*127.0.0.1*)
            if $allow_local; then
                pass "DATABASE_URL points at the local machine, expected in local mode"
            else
                warn "DATABASE_URL points at the local machine in a hosted run"
            fi ;;
    esac
else
    fail "DATABASE_URL is not set"
fi

is_set "${DATABASE_USER:-}" && pass "DATABASE_USER is set" || fail "DATABASE_USER is not set"
is_set "${DATABASE_PASSWORD:-}" && pass "DATABASE_PASSWORD is set" || fail "DATABASE_PASSWORD is not set"

# --- ALLOWED_EMAIL_DOMAINS --------------------------------------------------
domains="${ALLOWED_EMAIL_DOMAINS:-bmu.edu.in}"
if is_set "$domains"; then
    bad_domain=false
    old_ifs=$IFS
    IFS=','
    set -f          # a "*" entry must reach the check, not expand to filenames
    for domain in $domains; do
        trimmed=$(printf '%s' "$domain" | tr -d '[:space:]')
        if ! printf '%s' "$trimmed" | grep -Eq '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$'; then
            fail "ALLOWED_EMAIL_DOMAINS contains '$trimmed', which is not a bare lowercase domain (no @, no spaces)"
            bad_domain=true
        fi
    done
    set +f
    IFS=$old_ifs
    $bad_domain || pass "ALLOWED_EMAIL_DOMAINS is a list of bare domains"
else
    fail "ALLOWED_EMAIL_DOMAINS is empty; nobody could register"
fi

# --- ALLOWED_WEB_ORIGINS ----------------------------------------------------
origins="${ALLOWED_WEB_ORIGINS:-}"
if is_set "$origins"; then
    bad_origin=false
    old_ifs=$IFS
    IFS=','
    set -f
    for origin in $origins; do
        trimmed=$(printf '%s' "$origin" | tr -d '[:space:]')
        case "$trimmed" in
            '*')
                fail "ALLOWED_WEB_ORIGINS is '*'; that allows any website to call the API with the user's session"
                bad_origin=true ;;
            https://*)
                ;;
            http://localhost*|http://127.0.0.1*)
                if ! $allow_local; then
                    fail "ALLOWED_WEB_ORIGINS contains '$trimmed'; plain-HTTP localhost is only for local mode"
                    bad_origin=true
                fi ;;
            *)
                fail "ALLOWED_WEB_ORIGINS contains '$trimmed'; use an explicit https origin"
                bad_origin=true ;;
        esac
    done
    set +f
    IFS=$old_ifs
    $bad_origin || pass "ALLOWED_WEB_ORIGINS is a list of explicit origins"
else
    warn "ALLOWED_WEB_ORIGINS is empty; browser clients from any origin will be refused (correct if the API is only called by the Android app)"
fi

# --- ATTACHMENT_STORAGE_DIR -------------------------------------------------
if is_set "${ATTACHMENT_STORAGE_DIR:-}"; then
    pass "ATTACHMENT_STORAGE_DIR is set"
else
    if $allow_local; then
        warn "ATTACHMENT_STORAGE_DIR is empty; photographs go to a temporary directory, which is fine for a local run"
    else
        fail "ATTACHMENT_STORAGE_DIR is not set; uploaded photographs would live in the container's temporary directory and disappear on restart"
    fi
fi

# --- Behaviour switches -----------------------------------------------------
if [ "${RATE_LIMIT_ENABLED:-true}" = "false" ]; then
    if $rate_limit_off_allowed; then
        warn "RATE_LIMIT_ENABLED=false; accepted only because --allow-rate-limit-off was given"
    else
        fail "RATE_LIMIT_ENABLED=false; password guessing and registration abuse become unthrottled"
    fi
else
    pass "RATE_LIMIT_ENABLED is on"
fi

if [ "${REGISTRATION_ENABLED:-true}" = "false" ]; then
    warn "REGISTRATION_ENABLED=false; new students cannot sign up through the API"
fi

if [ "${TRUST_FORWARDED_FOR:-false}" = "true" ]; then
    if $behind_proxy; then
        pass "TRUST_FORWARDED_FOR is on, and --behind-proxy was declared"
    else
        fail "TRUST_FORWARDED_FOR=true without --behind-proxy; any client could choose its own rate-limiting key"
    fi
else
    if $behind_proxy; then
        warn "Running behind a proxy but TRUST_FORWARDED_FOR is off; every request will share the proxy's address and hit one rate-limit bucket"
    else
        pass "TRUST_FORWARDED_FOR is off"
    fi
fi

# --- Summary ----------------------------------------------------------------
printf '\n----------------------------------------\n'
printf 'passed: %d   warnings: %d   failed: %d\n' "$passed" "$warned" "$failed"
printf '%s\n' '----------------------------------------'

if [ "$failed" -gt 0 ]; then
    printf 'Do not deploy with these failures outstanding.\n'
    exit 1
fi

if [ "$warned" -gt 0 ]; then
    printf 'Acceptable, but read the warnings above before deploying.\n'
else
    printf 'No warnings.\n'
fi
