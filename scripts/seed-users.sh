#!/usr/bin/env bash
# Create the four local test users through the local Supabase Auth admin API.
#
#   creator@test.local  +1 555-555-0101   (OTP 101010)
#   viewer@test.local   +1 555-555-0102   (OTP 202020)
#   viewer2@test.local  +1 555-555-0103   (OTP 303030)
#   admin@test.local    +1 555-555-0104   (OTP 404040)  app_metadata.role = admin
#
# Phones are confirmed; the OTP codes come from [auth.sms.test_otp] in supabase/config.toml.
# Email+password sign-in uses $SEED_PASSWORD, default "LocalDev-Passw0rd!".
# Safe to re-run: existing users are updated in place.
#
# Refuses to run unless the Supabase API is on localhost / 127.0.0.1.
# Requires: curl, jq.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

for tool in curl jq; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }
done

# --- Read API URL and service_role key from the local stack ---------------
if ! status_env="$(npx supabase status -o env 2>/dev/null)"; then
  echo "supabase status failed. Is the local stack running? (scripts/dev-up.sh)" >&2
  exit 1
fi
read_status() { printf '%s\n' "$status_env" | sed -n "s/^$1=\"\{0,1\}\([^\"]*\)\"\{0,1\}$/\1/p" | head -n1; }
API_URL="$(read_status API_URL)"
SERVICE_KEY="$(read_status SERVICE_ROLE_KEY)"
if [ -z "$API_URL" ] || [ -z "$SERVICE_KEY" ]; then
  echo "Could not read API_URL / SERVICE_ROLE_KEY from supabase status." >&2
  exit 1
fi

# --- Safety: local only ---------------------------------------------------
api_host="$(printf '%s' "$API_URL" | sed -E 's#^[a-zA-Z]+://([^/:]+).*#\1#')"
if [ "$api_host" != "localhost" ] && [ "$api_host" != "127.0.0.1" ]; then
  echo "Refusing to run: API host is '$api_host'. seed-users only runs against localhost or 127.0.0.1." >&2
  exit 1
fi

PASSWORD="${SEED_PASSWORD:-LocalDev-Passw0rd!}"
ADMIN_USERS="$API_URL/auth/v1/admin/users"
AUTH=(-H "apikey: $SERVICE_KEY" -H "Authorization: Bearer $SERVICE_KEY")
EXISTING=""

seed_user() {
  local email="$1" phone="$2" is_admin="$3" body code id
  body="$(jq -n --arg email "$email" --arg pw "$PASSWORD" --arg phone "$phone" --argjson admin "$is_admin" '
    {email: $email, password: $pw, email_confirm: true, phone: $phone, phone_confirm: true}
    + (if $admin then {app_metadata: {role: "admin"}} else {} end)')"

  code="$(curl -sS -o /tmp/seed-users.$$ -w '%{http_code}' -X POST "$ADMIN_USERS" \
    "${AUTH[@]}" -H 'Content-Type: application/json' -d "$body")"
  if [ "$code" = "200" ] || [ "$code" = "201" ]; then
    echo "created  $email"
  elif [ "$code" = "422" ]; then
    # Already exists: find it and bring it back in line.
    if [ -z "$EXISTING" ]; then
      EXISTING="$(curl -sS --fail "${AUTH[@]}" "$ADMIN_USERS?page=1&per_page=1000")"
    fi
    id="$(printf '%s' "$EXISTING" | jq -r --arg email "$email" '.users[] | select(.email == $email) | .id' | head -n1)"
    if [ -z "$id" ]; then
      echo "$email: create returned 422 but no user with that email exists (phone already used by another user?)" >&2
      exit 1
    fi
    curl -sS --fail -o /dev/null -X PUT "$ADMIN_USERS/$id" \
      "${AUTH[@]}" -H 'Content-Type: application/json' -d "$body"
    echo "updated  $email"
  else
    echo "Creating $email failed (HTTP $code): $(cat /tmp/seed-users.$$)" >&2
    rm -f /tmp/seed-users.$$
    exit 1
  fi
  rm -f /tmp/seed-users.$$
}

seed_user creator@test.local 15555550101 false
seed_user viewer@test.local  15555550102 false
seed_user viewer2@test.local 15555550103 false
seed_user admin@test.local   15555550104 true

echo
echo "Done. Sign in with email + password, or phone + the OTP from supabase/config.toml."
