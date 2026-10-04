# Cloud setup checklist

Step-by-step setup for every external service the backend depends on. Work
through the sections in order: later sections reference values from earlier ones.

Every value you need to keep is marked like this:

> **Copy →** `ENV_VAR_NAME` — what the value looks like

All variables end up in `backend/.env` locally (copy it from
[`backend/.env.example`](../backend/.env.example) — never commit it) and in the
hosting platform's secret store in production. Values marked **(dashboard only)**
are entered into a third-party console and never go into the backend environment.

Throughout this document:

- `<ref>` = your Supabase project reference (20 lowercase letters)
- `<account_id>` = your Cloudflare account ID
- `<region>` = the region you pick for each service

> **Tip — pick regions close together.** Put Supabase, Cloud Run (or the Oracle
> instance) in the same geographic area. R2 is global, but you can set a location
> hint when creating the bucket.

---

## (a) Supabase project with Google sign-in

### a1. Create the project

- [ ] Sign in at <https://supabase.com/dashboard> and click **New project**.
- [ ] Choose the organisation, a project name, and a **region** near your users.
- [ ] Generate a strong **database password** and store it in your password manager
      immediately — the dashboard will not show it again (it can only be reset).

> **Copy →** `DB_PASSWORD` — the database password you just generated

- [ ] Wait for provisioning to finish, then open **Project Settings → General**.

> **Note:** the **Project ID** shown there is `<ref>`. You need it below.

### a2. API URL

- [ ] Open **Project Settings → Data API** (or the **Connect** button at the top of the dashboard).

> **Copy →** `SUPABASE_URL` — `https://<ref>.supabase.co`

The `anon` / publishable key is for the Flutter app only. **Do not** put the
`service_role` / secret key into the backend environment; the backend does not
need it.

### a3. JWT signing keys (JWKS)

The backend verifies Supabase access tokens against the project's public keys,
so the project must use **asymmetric** signing keys. Legacy HS256 shared-secret
projects do not publish verification keys in the JWKS endpoint.

- [ ] Open **Project Settings → JWT Keys**.
- [ ] If the project is still on the **Legacy JWT secret**, click
      **Migrate JWT secret**, then create a standby key using **ECC (P-256)**
      (recommended) or RSA, and **Rotate keys** so it becomes the current key.
- [ ] Verify the endpoint returns at least one key:
      `curl https://<ref>.supabase.co/auth/v1/.well-known/jwks.json`

> **Copy →** `SUPABASE_JWKS_URI` — `https://<ref>.supabase.co/auth/v1/.well-known/jwks.json`
>
> **Copy →** `SUPABASE_JWT_ISSUER` — `https://<ref>.supabase.co/auth/v1`

To double-check the issuer, sign in once from the app, decode an access token
(locally — never paste real tokens into a website) and confirm the `iss` claim
matches exactly, with no trailing slash.

### a4. Database connection string (JDBC)

- [ ] Click **Connect** at the top of the dashboard → **Connection string** tab.
- [ ] Choose **Session pooler**. Use this rather than *Direct connection*: the
      direct host is IPv6-only unless you buy the IPv4 add-on, and Cloud Run /
      many home networks only have IPv4 egress. Avoid the *Transaction pooler*
      (port 6543) for Spring/Hibernate — it does not support prepared statements
      reliably.
- [ ] Note the host (e.g. `aws-0-<region>.pooler.supabase.com`), port `5432`,
      database `postgres` and user `postgres.<ref>`.
- [ ] Rewrite it as a JDBC URL — host and database only, **no** credentials in the URL:

> **Copy →** `DB_URL` — `jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require`
>
> **Copy →** `DB_USERNAME` — `postgres.<ref>`
>
> (`DB_PASSWORD` was copied in step a1.)

> **Security note:** the `postgres` role bypasses RLS. That is acceptable for
> initial development, but before launch the backend should connect as a
> dedicated, least-privilege role. Creating that role is a schema change, so it
> must land as a new migration in `supabase/migrations/` (CLAUDE.md rule 1).

### a5. Google OAuth client (in Google Cloud)

Google sign-in needs OAuth clients in a Google Cloud project. You can reuse the
project from section (c) or use a separate one.

- [ ] Go to <https://console.cloud.google.com/auth/overview> (**Google Auth Platform**)
      and select the project.
- [ ] **Branding:** set app name, support email, and developer contact email.
      Add `<ref>.supabase.co` under **Authorized domains**.
- [ ] **Audience:** choose **External**. Leave it in *Testing* and add test users
      until you are ready to publish.
- [ ] **Data access:** add scopes `openid`, `.../auth/userinfo.email`,
      `.../auth/userinfo.profile`. Nothing else.
- [ ] **Clients → Create client → Web application:**
  - Authorized JavaScript origins: your web app origin(s), if any.
  - Authorized redirect URIs: `https://<ref>.supabase.co/auth/v1/callback`

> **Copy (dashboard only) →** Web **Client ID** and **Client secret** — pasted into
> Supabase in step a6, not into the backend.

- [ ] For native Google sign-in in the Flutter app, also create:
  - an **Android** client (package name + SHA-1 of each signing key: debug, upload, and Play App Signing)
  - an **iOS** client (bundle ID)

> **Copy (dashboard only) →** the Android and iOS **Client IDs** — for step a6 and
> for the Flutter app's config. They have no secret.

### a6. Enable Google in Supabase

- [ ] In Supabase open **Authentication → Sign In / Providers → Google** and enable it.
- [ ] **Client IDs:** paste the **Web** client ID first, then the Android and iOS
      client IDs, comma-separated.
- [ ] **Client Secret (for OAuth):** paste the Web client secret.
- [ ] Leave **Skip nonce checks** off unless the iOS native flow requires it.
- [ ] Save.

### a7. Redirect URLs

- [ ] Open **Authentication → URL Configuration**.
- [ ] **Site URL:** your production web URL (or the app's deep link if mobile-only).
- [ ] **Redirect URLs:** add the Flutter deep link (e.g.
      `com.yourcompany.app://login-callback`) and any web origins. Use exact URLs;
      avoid broad wildcards in production.

### a8. Link the local CLI

- [ ] `npx supabase login`
- [ ] `npx supabase link --project-ref <ref>` (prompts for the DB password from a1)

---

## (b) Cloudflare account, private R2 bucket, bucket-scoped token

### b1. Account and R2

- [ ] Sign up / sign in at <https://dash.cloudflare.com>. Enable **two-factor authentication** on the account.
- [ ] Open **R2 Object Storage** in the sidebar and complete the R2 subscription
      (a payment method is required even on the free tier).
- [ ] On the R2 overview page, find **Account ID** (right-hand panel, or in the
      account home URL `dash.cloudflare.com/<account_id>`).

> **Copy →** `R2_ACCOUNT_ID` — 32 hex characters

### b2. Create the private bucket

- [ ] **R2 → Create bucket.**
- [ ] Name it (lowercase, digits, hyphens), e.g. `nichez-media-prod`.
- [ ] Location: **Automatic**, or a location hint near your users.
- [ ] Default storage class: **Standard**.
- [ ] After creation, open the bucket → **Settings** and confirm it stays private:
  - **Public Development URL (r2.dev):** *Disabled*
  - **Custom Domains:** *none*

  Video is served only through the Cloudflare Worker using a short-lived
  playback token, never via a public bucket URL.

> **Copy →** `R2_BUCKET` — the bucket name, e.g. `nichez-media-prod`

- [ ] *(Only if the app uploads directly to R2 with presigned URLs)* bucket
      **Settings → CORS policy:** allow `PUT` from your web origins only.
      Native mobile clients do not need CORS.

### b3. API token scoped to that bucket only

- [ ] **R2 → Overview → Manage API tokens** (labelled *API Tokens* / *Manage R2 API Tokens*).
- [ ] **Create Account API token** (survives if your user leaves the account;
      a *User API token* works too).
- [ ] Token name: e.g. `backend-<bucket>-rw`.
- [ ] Permissions: **Object Read & Write**. (Not *Admin Read & Write* — that
      can create and delete buckets.)
- [ ] Specify bucket(s): **Apply to specific buckets only** → select your bucket.
      Do **not** choose *Apply to all buckets*.
- [ ] TTL: forever, or a fixed expiry if you will rotate it on a schedule.
- [ ] Client IP filtering: optional (Cloud Run egress IPs are not static unless
      you add Cloud NAT; Oracle instances have a stable public IP).
- [ ] Create. The secret is shown **once**.

> **Copy →** `R2_ACCESS_KEY_ID` — **Access Key ID** (32 hex chars)
>
> **Copy →** `R2_SECRET_ACCESS_KEY` — **Secret Access Key** (64 hex chars)

Ignore the *Token value* field on that page; the S3-compatible client uses the
key ID and secret. The S3 endpoint is derived from the account ID:
`https://<account_id>.r2.cloudflarestorage.com`.

### b4. Playback worker

The Worker reads the bucket through an **R2 binding** in `wrangler.toml`; it
does not use the API token above.

- [ ] Generate a playback signing secret (at least 32 random bytes):
      `openssl rand -base64 48`

> **Copy →** `PLAYBACK_TOKEN_SECRET` — the generated string. The backend signs
> playback tokens with it and the Worker verifies them, so **both must hold the
> same value**.

- [ ] Store the same value in the Worker:
      `cd edge-worker && npx wrangler secret put PLAYBACK_TOKEN_SECRET`
- [ ] Deploy the Worker (`npx wrangler deploy`) and note its URL — the
      `workers.dev` URL, or a custom route such as `https://play.example.com`.

> **Copy →** `WORKER_BASE_URL` — e.g. `https://playback.<subdomain>.workers.dev` (no trailing slash)

---

## (c) Google Cloud: Cloud Run and Artifact Registry

### c1. Project and billing

- [ ] Install the gcloud CLI and run `gcloud auth login`.
- [ ] Create the project at <https://console.cloud.google.com/projectcreate>
      (or `gcloud projects create <project-id>`).
- [ ] Link a **billing account** (**Billing → Link a billing account**). Cloud Run
      has a free tier but requires billing.
- [ ] Set a **budget alert** (**Billing → Budgets & alerts**) — e.g. $10/month.
- [ ] `gcloud config set project <project-id>`

> **Note (not an env var):** `<project-id>` — used in image paths and deploy commands.

### c2. Enable APIs

```
gcloud services enable run.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com
```

Secret Manager is not strictly required, but it is how secrets reach Cloud Run
as environment variables without appearing in the service config.

### c3. Artifact Registry repository

```
gcloud artifacts repositories create backend \
  --repository-format=docker \
  --location=<region> \
  --description="Backend container images"

gcloud auth configure-docker <region>-docker.pkg.dev
```

> **Note (not an env var):** image path
> `<region>-docker.pkg.dev/<project-id>/backend/api:<tag>`

### c4. Runtime service account

```
gcloud iam service-accounts create backend-runtime \
  --display-name="Backend Cloud Run runtime"
```

Grant it nothing else for now; secret access is granted per secret below.
Do not run the service as the default Compute Engine service account (it has
broad Editor rights).

### c5. Store secrets

Create one secret per sensitive value. Read from stdin so values do not land in
shell history:

```
printf '%s' "<value>" | gcloud secrets create DB_PASSWORD --data-file=-
gcloud secrets add-iam-policy-binding DB_PASSWORD \
  --member="serviceAccount:backend-runtime@<project-id>.iam.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"
```

Repeat for: `DB_PASSWORD`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`,
`PLAYBACK_TOKEN_SECRET`. (Put the value in the clipboard and use
`read -rs V && printf '%s' "$V" | gcloud secrets create …` if you prefer not
to type it on the command line at all.)

### c6. Deploy

```
gcloud run deploy backend \
  --image=<region>-docker.pkg.dev/<project-id>/backend/api:<tag> \
  --region=<region> \
  --service-account=backend-runtime@<project-id>.iam.gserviceaccount.com \
  --allow-unauthenticated \
  --set-env-vars=DB_URL=...,DB_USERNAME=...,SUPABASE_URL=...,SUPABASE_JWKS_URI=...,SUPABASE_JWT_ISSUER=...,R2_ACCOUNT_ID=...,R2_BUCKET=...,WORKER_BASE_URL=...,CORS_ALLOWED_ORIGINS=... \
  --set-secrets=DB_PASSWORD=DB_PASSWORD:latest,R2_ACCESS_KEY_ID=R2_ACCESS_KEY_ID:latest,R2_SECRET_ACCESS_KEY=R2_SECRET_ACCESS_KEY:latest,PLAYBACK_TOKEN_SECRET=PLAYBACK_TOKEN_SECRET:latest
```

`--allow-unauthenticated` only disables Google's IAM gate in front of the
service; the backend itself still requires a valid Supabase JWT on every
non-public endpoint.

`CORS_ALLOWED_ORIGINS` contains commas, so for that one use a custom delimiter:
`--set-env-vars='^@^CORS_ALLOWED_ORIGINS=https://a.example,https://b.example'`
or an `--env-vars-file`.

> **Copy (for the Flutter app) →** the service URL printed by the deploy,
> `https://backend-<hash>-<region-code>.a.run.app`

---

## (c-alt) Oracle Cloud Always Free

Use this instead of Cloud Run if you want zero hosting cost. You manage the VM,
OS updates, TLS and restarts yourself.

### o1. Account

- [ ] Sign up at <https://signup.cloud.oracle.com>. A card is required for
      identity verification.
- [ ] Choose the **home region** carefully — it **cannot be changed**, and
      Always Free compute is only available there.
- [ ] Enable MFA for the console user.

### o2. Network

- [ ] **Networking → Virtual cloud networks → Start VCN Wizard → Create VCN with
      Internet Connectivity.**
- [ ] In the public subnet's **Security List**, add ingress rules for TCP **80**
      and **443** from `0.0.0.0/0`. Restrict TCP **22** to your own IP if possible.
      Do **not** open the application port (8080) publicly.

### o3. Compute instance

- [ ] **Compute → Instances → Create instance.**
- [ ] Image: **Canonical Ubuntu 24.04** (or Oracle Linux).
- [ ] Shape: **Ampere → VM.Standard.A1.Flex** (Always Free eligible; e.g.
      2 OCPU / 12 GB is plenty). If you get *Out of capacity*, retry later or in
      another availability domain. `VM.Standard.E2.1.Micro` (AMD, 1 GB RAM) is
      also free but tight for a JVM.
- [ ] Upload your SSH **public** key.
- [ ] Note the instance's **public IP**; point a DNS A record (e.g.
      `api.example.com`) at it.

> **Note:** Ampere is **ARM64**. Build the container image for `linux/arm64`
> (`docker buildx build --platform linux/arm64 …`) or run the JAR directly on a
> JDK 21 ARM build.

### o4. Host setup

- [ ] Open the OS firewall as well — Oracle's Ubuntu images ship with
      restrictive iptables rules in addition to the security list:
      ```
      sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
      sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
      sudo netfilter-persistent save
      ```
- [ ] Enable unattended security upgrades (`sudo dpkg-reconfigure unattended-upgrades`).
- [ ] Install JDK 21 (or Docker) and a TLS reverse proxy (Caddy obtains
      certificates automatically): `reverse_proxy localhost:8080`.

### o5. Environment on the host

- [ ] Create `/etc/nichez-backend/backend.env` with every variable from
      `backend/.env.example` and real values. Lock it down:
      ```
      sudo chown root:root /etc/nichez-backend/backend.env
      sudo chmod 600 /etc/nichez-backend/backend.env
      ```
- [ ] Reference it from the systemd unit (`EnvironmentFile=/etc/nichez-backend/backend.env`)
      or from Docker (`--env-file`). Run the service as a dedicated non-root user.
- [ ] If you enabled R2 client-IP filtering in b3, add the instance's public IP there.

> **Copy (for the Flutter app) →** `https://api.example.com`

---

## Variable summary

| Variable | Source step | Secret? |
|---|---|---|
| `DB_URL` | a4 | no |
| `DB_USERNAME` | a4 | no |
| `DB_PASSWORD` | a1 | **yes** |
| `SUPABASE_URL` | a2 | no |
| `SUPABASE_JWKS_URI` | a3 | no |
| `SUPABASE_JWT_ISSUER` | a3 | no |
| `R2_ACCOUNT_ID` | b1 | no |
| `R2_ACCESS_KEY_ID` | b3 | **yes** |
| `R2_SECRET_ACCESS_KEY` | b3 | **yes** |
| `R2_BUCKET` | b2 | no |
| `PLAYBACK_TOKEN_SECRET` | b4 | **yes** (also set in the Worker) |
| `WORKER_BASE_URL` | b4 | no |
| `CORS_ALLOWED_ORIGINS` | your web origins | no |

Never paste secret values into chat, tickets, logs or commit messages. If one
leaks, rotate it at the source (Supabase password reset, new R2 token, new
playback secret in both backend and Worker) and update the secret store.
