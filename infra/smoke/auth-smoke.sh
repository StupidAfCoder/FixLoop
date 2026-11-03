#!/usr/bin/env bash
#
# Local smoke test for the identity endpoints.
#
# Use it against a stack running on your own computer, before you merge:
#
#   docker compose up -d --wait api
#   bash infra/smoke/auth-smoke.sh
#
# Or against any base URL:
#
#   bash infra/smoke/auth-smoke.sh http://127.0.0.1:8080
#
# It creates one throwaway student account per run (see the cleanup note at the
# end of the output). Requires curl and either jq or python3 to read JSON.
#
# Exit codes: 0 all checks passed, 1 a check failed, 2 the API is not reachable.

set -euo pipefail

BASE_URL="${1:-${SMOKE_BASE_URL:-http://127.0.0.1:8080}}"
DOMAIN="${SMOKE_DOMAIN:-bmu.edu.in}"
EMAIL="smoke-$(date +%s)-$$@${DOMAIN}"
PASSWORD="campus-lamp-2026"
NEW_PASSWORD="library-window-2027"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
BODY="$TMP_DIR/body"

passed=0
failed=0

step() {
    printf '\n== %s\n' "$1"
}

note() {
    printf '   %s\n' "$1"
}

expect_status() {
    local expected="$1" actual="$2" label="$3"
    if [ "$expected" = "$actual" ]; then
        printf 'PASS %s (HTTP %s)\n' "$label" "$actual"
        passed=$((passed + 1))
    else
        printf 'FAIL %s: expected HTTP %s, got %s\n' "$label" "$expected" "$actual"
        failed=$((failed + 1))
        if [ "$actual" = "429" ]; then
            note "Rate limited. Wait five minutes, or set RATE_LIMIT_ENABLED=false in .env and restart the API."
        fi
        if [ -s "$BODY" ]; then
            note "body: $(tr -d '\n' < "$BODY" | cut -c1-300)"
        fi
    fi
}

expect_field() {
    local expected="$1" actual="$2" label="$3"
    if [ "$expected" = "$actual" ]; then
        printf 'PASS %s\n' "$label"
        passed=$((passed + 1))
    else
        printf 'FAIL %s: expected "%s", got "%s"\n' "$label" "$expected" "$actual"
        failed=$((failed + 1))
    fi
}

expect_different() {
    local first="$1" second="$2" label="$3"
    if [ -n "$first" ] && [ -n "$second" ] && [ "$first" != "$second" ]; then
        printf 'PASS %s\n' "$label"
        passed=$((passed + 1))
    else
        printf 'FAIL %s: values were equal or empty\n' "$label"
        failed=$((failed + 1))
    fi
}

# request METHOD PATH [JSON_BODY] [BEARER_TOKEN] -> prints the status code
request() {
    local method="$1" path="$2" body="${3:-}" token="${4:-}"
    local args=(-sS -o "$BODY" -w '%{http_code}' -X "$method" "$BASE_URL$path")
    if [ -n "$body" ]; then
        args+=(-H 'Content-Type: application/json' -d "$body")
    fi
    if [ -n "$token" ]; then
        args+=(-H "Authorization: Bearer $token")
    fi
    curl "${args[@]}"
}

# json_field NAME -> prints the string value of a top-level field, or ""
json_field() {
    if command -v jq >/dev/null 2>&1; then
        jq -r --arg key "$1" 'if type == "object" then (.[$key] // "" | tostring) else "" end' "$BODY"
    elif command -v python3 >/dev/null 2>&1; then
        python3 -c '
import json, sys
try:
    value = json.load(sys.stdin).get(sys.argv[1], "")
    print("" if value is None else str(value))
except Exception:
    print("")
' "$1" < "$BODY"
    else
        sed -n 's/.*"'"$1"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$BODY" | head -n 1
    fi
}

printf 'Fix Loop identity smoke test against %s\n' "$BASE_URL"
note "account: $EMAIL"

step "The API is reachable"
status="$(request GET /api/v1/health || true)"
if [ "$status" = "000" ] || [ -z "$status" ]; then
    printf 'FAIL the API did not answer on %s\n' "$BASE_URL"
    note "Start it with: docker compose up -d --wait db api"
    exit 2
fi
expect_status 200 "$status" "GET /api/v1/health"

step "A student can register"
status="$(request POST /api/v1/auth/register \
    "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"fullName\":\"Smoke Test\"}")"
expect_status 201 "$status" "POST /api/v1/auth/register"
expect_field "STUDENT" "$(json_field role)" "the new account has the STUDENT role"
if [ -s "$BODY" ] && grep -qi 'passwordHash\|"password"' "$BODY"; then
    printf 'FAIL the registration response contains password material\n'
    failed=$((failed + 1))
else
    printf 'PASS the registration response contains no password material\n'
    passed=$((passed + 1))
fi

step "Registration rules are enforced"
status="$(request POST /api/v1/auth/register \
    "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"fullName\":\"Smoke Test\"}")"
expect_status 409 "$status" "a duplicate address is refused"
expect_field "EMAIL_ALREADY_REGISTERED" "$(json_field code)" "the duplicate error code is stable"

status="$(request POST /api/v1/auth/register \
    "{\"email\":\"smoke-$RANDOM@gmail.com\",\"password\":\"$PASSWORD\",\"fullName\":\"Smoke Test\"}")"
expect_status 400 "$status" "a domain outside the allow-list is refused"
expect_field "EMAIL_DOMAIN_NOT_ALLOWED" "$(json_field code)" "the domain error code is stable"

status="$(request POST /api/v1/auth/register \
    "{\"email\":\"smoke-$RANDOM@$DOMAIN\",\"password\":\"short\",\"fullName\":\"Smoke Test\"}")"
expect_status 400 "$status" "a short password is refused"
expect_field "VALIDATION_FAILED" "$(json_field code)" "the validation error code is stable"

step "Login failures are indistinguishable"
status="$(request POST /api/v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"wrong-password-here\"}")"
expect_status 401 "$status" "a wrong password is refused"
wrong_password_code="$(json_field code)"
wrong_password_message="$(json_field message)"

status="$(request POST /api/v1/auth/login "{\"email\":\"nobody-$RANDOM@$DOMAIN\",\"password\":\"$PASSWORD\"}")"
expect_status 401 "$status" "an unknown address is refused"
expect_field "$wrong_password_code" "$(json_field code)" "both failures share one code"
expect_field "$wrong_password_message" "$(json_field message)" "both failures share one message"

step "A valid login returns a usable access token"
status="$(request POST /api/v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")"
expect_status 200 "$status" "POST /api/v1/auth/login"
access_token="$(json_field accessToken)"
refresh_token="$(json_field refreshToken)"
expect_field "Bearer" "$(json_field tokenType)" "the token type is Bearer"
expect_field "900" "$(json_field expiresInSeconds)" "the access token lives 15 minutes"
if [ -n "$access_token" ]; then
    printf 'PASS an access token was issued\n'
    passed=$((passed + 1))
else
    printf 'FAIL no access token was issued\n'
    failed=$((failed + 1))
fi

step "The token is required and identifies the caller"
status="$(request GET /api/v1/me)"
expect_status 401 "$status" "GET /api/v1/me without a token"
status="$(request GET /api/v1/me "" "not-a-real-token")"
expect_status 401 "$status" "GET /api/v1/me with a forged token"
status="$(request GET /api/v1/me "" "$access_token")"
expect_status 200 "$status" "GET /api/v1/me with a valid token"
expect_field "$EMAIL" "$(json_field email)" "it returns the caller's own account"

step "Refresh rotates and replay is detected"
status="$(request POST /api/v1/auth/refresh "{\"refreshToken\":\"$refresh_token\"}")"
expect_status 200 "$status" "POST /api/v1/auth/refresh"
rotated_token="$(json_field refreshToken)"
expect_different "$refresh_token" "$rotated_token" "the refresh token was rotated"

status="$(request POST /api/v1/auth/refresh "{\"refreshToken\":\"$refresh_token\"}")"
expect_status 401 "$status" "replaying the old refresh token is refused"
status="$(request POST /api/v1/auth/refresh "{\"refreshToken\":\"$rotated_token\"}")"
expect_status 401 "$status" "the whole session family was revoked (reuse detection)"

step "Logout ends a session"
status="$(request POST /api/v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")"
expect_status 200 "$status" "a second login succeeds"
second_refresh="$(json_field refreshToken)"
status="$(request POST /api/v1/auth/logout "{\"refreshToken\":\"$second_refresh\"}")"
expect_status 204 "$status" "POST /api/v1/auth/logout"
status="$(request POST /api/v1/auth/refresh "{\"refreshToken\":\"$second_refresh\"}")"
expect_status 401 "$status" "the logged-out refresh token no longer works"

step "Changing the password retires the old one"
status="$(request POST /api/v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")"
expect_status 200 "$status" "a third login succeeds"
change_token="$(json_field accessToken)"
status="$(request POST /api/v1/auth/change-password \
    "{\"currentPassword\":\"not-the-password\",\"newPassword\":\"$NEW_PASSWORD\"}" "$change_token")"
expect_status 400 "$status" "a wrong current password is refused"
status="$(request POST /api/v1/auth/change-password \
    "{\"currentPassword\":\"$PASSWORD\",\"newPassword\":\"$NEW_PASSWORD\"}" "$change_token")"
expect_status 204 "$status" "POST /api/v1/auth/change-password"
status="$(request POST /api/v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")"
expect_status 401 "$status" "the old password no longer works"
status="$(request POST /api/v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"$NEW_PASSWORD\"}")"
expect_status 200 "$status" "the new password works"

step "Domain endpoints stay closed"
status="$(request GET /api/v1/complaints)"
expect_status 401 "$status" "an unauthenticated call to a future endpoint is refused"

printf '\n%s\n' '----------------------------------------'
printf 'passed: %s   failed: %s\n' "$passed" "$failed"
printf '%s\n' '----------------------------------------'

printf '\n'
cat <<'TEXT'
Cleanup (optional, local data only): the smoke run created the account printed
at the top. It has no complaints, so removing every smoke-* account is safe.

  Running the API with Docker Compose:
    bash infra/deploy/db-check.sh --cleanup

  Running the API against a PostgreSQL you installed yourself:
    psql -U fixloop -d fixloop -f infra/smoke/cleanup.sql

Leaving the accounts in place is harmless; repeated runs simply add rows.

TEXT

if [ "$failed" -gt 0 ]; then
    exit 1
fi
exit 0
