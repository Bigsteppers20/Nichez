# Local development

Everything runs on your machine: Supabase (via the Supabase CLI and Docker)
and MinIO (an S3-compatible stand-in for Cloudflare R2). No cloud accounts.

## Prerequisites

- Docker Desktop, running
- Node.js (the Supabase CLI runs through `npx supabase`)
- JDK 21 for the backend (see `CLAUDE.md`)
- For `scripts/*.sh` only: `bash`, `curl`, `jq`

## First run

```powershell
Copy-Item infra/.env.example infra/.env     # then edit the values
./scripts/dev-up.ps1
./scripts/seed-users.ps1
```

On Linux/macOS/WSL use `cp`, `./scripts/dev-up.sh` and `./scripts/seed-users.sh`.

Stop with `./scripts/dev-down.ps1` (or `.sh`). Data is kept between runs.

## Ports

| Port | Service | Phone needs it? |
|---|---|---|
| 8080 | Spring Boot backend (`/api/v1`) | **yes** |
| 54321 | Supabase API gateway: Auth, REST, Storage, Realtime | **yes** |
| 9000 | MinIO S3 API (bucket `media`) | **yes** (media upload/download) |
| 9001 | MinIO web console | no |
| 54322 | Postgres (`postgres` / `postgres`) | no |
| 54323 | Supabase Studio | no |
| 54324 | Mailpit, catches all outgoing auth emails | no |
| 54327 | Supabase analytics (logs) | no |
| 54320 | Shadow database, used only by `supabase db diff` | no |
| 8083 | Edge Functions inspector, used only when debugging functions | no |

`npx supabase status` prints the live URLs and local keys.

## Test users

`scripts/seed-users` creates these through the local Auth admin API. Phones are
confirmed. Each phone signs in with a fixed OTP from `[auth.sms.test_otp]` in
`supabase/config.toml`; no SMS is ever sent.

| Email | Phone | OTP | Role |
|---|---|---|---|
| creator@test.local | +1 202$1555 0101 | 101010 | |
| viewer@test.local | +1 202$1555 0102 | 202020 | |
| viewer2@test.local | +1 202$1555 0103 | 303030 | |
| admin@test.local | +1 202$1555 0104 | 404040 | `app_metadata.role = admin` |

Email + password sign-in also works; the password is `LocalDev-Passw0rd!`
unless you set `SEED_PASSWORD` before running the script. Re-running the script
is safe: existing users are updated, not duplicated.

The script refuses to run unless the Supabase API it finds is on `localhost`
or `127.0.0.1`.

> **Never run `npx supabase config push` with this `config.toml`.** It would
> copy the test OTP numbers (a login backdoor) and the fake SMS provider to
> the linked hosted project. Configure the hosted project in its dashboard.

## Testing endpoints in Swagger

Start the backend with `./mvnw.cmd spring-boot:run` from `backend/` (it uses the `local`
profile) and open **http://localhost:8080/swagger-ui.html**. The raw OpenAPI document is at
`/v3/api-docs`.

Swagger exists only on the `local` profile (`springdoc.swagger-ui.enabled` in
`application-local.yml`). In any other profile, including `prod`, these paths answer 401 like
everything else that is not public. The API's own routes need a token even with Swagger on.

To call an endpoint that needs a token, sign in as a test user and paste the token into Authorize.

1. Get a token by email and password straight from the local Auth. This copies it to the clipboard:

   ```powershell
   $k = (npx supabase status -o json | ConvertFrom-Json).ANON_KEY
   (Invoke-RestMethod -Method Post "http://127.0.0.1:54321/auth/v1/token?grant_type=password" `
       -Headers @{ apikey = $k } -ContentType "application/json" `
       -Body '{"email":"creator@test.local","password":"LocalDev-Passw0rd!"}').access_token | Set-Clipboard
   ```

   Use another test user's email for a second person. Run it from the repo root.
2. Click **Authorize** at the top of the Swagger page and paste. Do not type `Bearer`; Swagger adds it.
   The token is kept across page reloads. It does expire, so repeat step 1 when calls start
   returning `TOKEN_EXPIRED`.

Phone sign-in works too: call `POST /api/v1/auth/phone/start` with
`{ "dialCode": "+1", "nationalNumber": "202 555 0101" }`, then `/verify` with the `challengeId` and
that number's code from the Test users table, and paste `session.accessToken`.

The test numbers are in the fictional 555-01xx range under the real 202 area code on purpose: the
backend validates numbers with libphonenumber, which rejects a made-up area code such as `+1 555 ...`
(`INVALID_PHONE`) before the request ever reaches Auth.

## Reaching the stack from a phone

The phone needs ports **8080**, **54321** and **9000**. In the Flutter app,
point the API base URL, the Supabase URL and media URLs at the host the phone
can reach, which depends on how it connects:

### Android emulator

Use **`10.0.2.2`**, the emulator's alias for your laptop's `localhost`:

```
http://10.0.2.2:8080     backend
http://10.0.2.2:54321    Supabase
http://10.0.2.2:9000     MinIO
```

### USB-connected phone (adb reverse)

Forward the phone's own ports to the laptop. The phone then uses `127.0.0.1`:

```
adb reverse tcp:8080 tcp:8080
adb reverse tcp:54321 tcp:54321
adb reverse tcp:9000 tcp:9000
adb reverse --list          # check
```

Forwards are lost when the cable is unplugged or adb restarts; run them again.
With this method the phone needs no Wi-Fi and no firewall changes.

### Phone on the same Wi-Fi

1. Find the laptop's LAN IP: `ipconfig` → *Wireless LAN adapter Wi-Fi* →
   *IPv4 Address* (e.g. `192.168.1.23`). The phone uses
   `http://192.168.1.23:8080`, and the same IP for `:54321` and `:9000`.
2. Make sure Windows treats the Wi-Fi network as **Private**
   (Settings → Network & internet → Wi-Fi → your network → Private).
3. Allow inbound connections on those three ports, Private networks only, in an
   **elevated** PowerShell:

   ```powershell
   New-NetFirewallRule -DisplayName "Nichez local dev" -Direction Inbound `
     -Protocol TCP -LocalPort 8080,54321,9000 -Action Allow -Profile Private
   ```

   Remove it when you are done:
   `Remove-NetFirewallRule -DisplayName "Nichez local dev"`

Never do this on public Wi-Fi. While the rule is active, anyone on the same
network can reach the local stack.

### Things that trip people up

- **Cleartext HTTP on Android.** The local stack is plain `http://`. Android 9+
  blocks cleartext traffic unless the app's debug build allows it (network
  security config or `usesCleartextTraffic` in the debug manifest).
- **Presigned media URLs carry their host in the signature.** A URL signed
  for `localhost:9000` fails on the phone. The backend must sign with the host
  the phone uses (`10.0.2.2`, `127.0.0.1` with adb reverse, or the LAN IP).
- **The IP changes.** The laptop's LAN IP can change between networks or DHCP
  renewals; check `ipconfig` again if the phone suddenly cannot connect.

## Pointing the backend at the local stack

`backend/.env` currently targets the hosted Supabase project. For a fully
local run, use these values (they are the Supabase CLI's fixed local defaults,
not secrets):

```
DB_URL=jdbc:postgresql://127.0.0.1:54322/postgres
DB_USERNAME=postgres
DB_PASSWORD=postgres
SUPABASE_URL=http://127.0.0.1:54321
SUPABASE_JWKS_URI=http://127.0.0.1:54321/auth/v1/.well-known/jwks.json
SUPABASE_JWT_ISSUER=http://127.0.0.1:54321/auth/v1
R2_ACCESS_KEY_ID=<MINIO_SERVICE_ACCESS_KEY from infra/.env>
R2_SECRET_ACCESS_KEY=<MINIO_SERVICE_SECRET_KEY from infra/.env>
R2_BUCKET=media
```

Check that local Auth publishes a signing key:
`curl http://127.0.0.1:54321/auth/v1/.well-known/jwks.json`. If `keys` is
empty, local Auth is signing with the legacy shared secret and the backend
cannot verify its tokens. Generate a key with
`npx supabase gen signing-key --algorithm ES256`, set
`signing_keys_path = "./signing_keys.json"` under `[auth]` in
`supabase/config.toml`, and restart Supabase. `signing_keys.json` is gitignored.

The backend's S3 client does not yet have a setting for a custom endpoint
(`http://localhost:9000`); that lands with the `media` feature.

## Storage without MinIO

The local Supabase stack already has an S3-compatible endpoint, so uploads work without running MinIO.
This is the option when something else owns port 9000. Create a private bucket once:

```powershell
$s = npx supabase status -o json | ConvertFrom-Json
Invoke-RestMethod -Method Post "http://127.0.0.1:54321/storage/v1/bucket" `
    -Headers @{ apikey = $s.SERVICE_ROLE_KEY; Authorization = "Bearer $($s.SERVICE_ROLE_KEY)" } `
    -ContentType "application/json" -Body '{"id":"media","name":"media","public":false}'
```

Then start the backend with these set (environment variables win over `backend/.env`):

| Variable | Value from `npx supabase status -o json` |
|---|---|
| `STORAGE_ENDPOINT` and `STORAGE_PUBLIC_ENDPOINT` | `STORAGE_S3_URL` (`http://127.0.0.1:54321/storage/v1/s3`) |
| `STORAGE_REGION` | `S3_PROTOCOL_REGION` (`local`) |
| `STORAGE_ACCESS_KEY` | `S3_PROTOCOL_ACCESS_KEY_ID` |
| `STORAGE_SECRET_KEY` | `S3_PROTOCOL_ACCESS_KEY_SECRET` |
| `STORAGE_BUCKET` | `media` |

Sign, `PUT` and `POST /posts` all work against it. Uploaded posts stay `processing` until the media
worker publishes them; nothing runs that locally yet.

## Reset everything

**Database only**, the usual case:

```
npx supabase db reset
```

This drops the local database, re-applies every file in `supabase/migrations/`
in order, and runs `supabase/seed.sql` if present. Auth users are wiped too,
so run `scripts/seed-users` again afterwards.

**Everything, including MinIO objects and all Supabase volumes:**

```
docker compose -f infra/docker-compose.yml --env-file infra/.env down -v
npx supabase stop --no-backup
./scripts/dev-up.ps1
./scripts/seed-users.ps1
```

`down -v` deletes the `minio-data` volume, and `--no-backup` deletes Supabase's
local volumes. Both are irreversible. The next `dev-up` recreates the `media`
bucket and service user.
