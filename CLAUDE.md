# CLAUDE.md

Standing brief for this repository. Read this before making changes.

## Project

Backend for **Nichez**, a niche-based meme social network: users post images and
short videos into niches. Creators earn a reward at 10,000 streams, paid into
an in-app wallet.

The client is a Flutter app (Riverpod, go_router, Dio) at `C:\Nichez`.
Expected scale at launch is roughly 1,000 users, so prefer clarity and
correctness over premature scaling work. Data security and money handling
correctness are the two highest priorities in this codebase.

## Stack

- **Language/runtime:** Java 21
- **Framework:** Spring Boot 3.x
- **Build:** Maven wrapper (`mvnw` / `mvnw.cmd`) — always use the wrapper, never a system `mvn`
- **Auth:** Supabase Auth (Google login)
- **Database:** Supabase Postgres
- **Media storage (images and video):** Cloudflare R2
- **Playback:** Cloudflare Worker
- **Hosting:** Google Cloud Run or Oracle Cloud Always Free

### Local Java toolchain

This machine has JDK 11, 21 and 24 installed. Build with **21**:

```
C:\Program Files\Java\jdk-21     (21.0.6 LTS)
```

`JAVA_HOME` is already set to that path. Note that `C:\Program Files\Java\jdk-11\bin`
is also on `PATH`; `java` currently resolves to 21 only because the Oracle javapath
shim precedes it. If a build reports the wrong version, set `JAVA_HOME` explicitly
rather than reordering `PATH`.

## Repo layout

| Path | Contents |
|---|---|
| `backend/` | Spring Boot application, base package `app.nichez` |
| `edge-worker/` | Cloudflare Worker, TypeScript |
| `supabase/migrations/` | SQL migrations — owns the schema and all RLS policies |
| `http/` | REST Client `.http` files, one request per endpoint |
| `docs/` | `API.md` (API contract), `FEATURE_MANIFEST.md`, `SETUP.md` |

## Backend package layout

Under `app.nichez`:

- `config` — Spring configuration
- `security` — JWT verification, principal resolution, role extraction
- `common` — error types, the shared response envelope
- One package per feature: `auth`, `profile`, `niche`, `media`, `post`, `feed`,
  `interaction`, `graph`, `collection`, `search`, `activity`, `safety`,
  `sharing`, `account`, `stream`, `wallet`, `settlement`, `admin`

Each feature package contains `controller`, `service`, `repository`, `entity`, `dto`.

## Rules

These are not suggestions. Follow them on every change.

1. **Schema changes only via new timestamped files in `supabase/migrations/`.**
   JPA runs with `ddl-auto=validate`. Never edit a migration that has been applied.
2. **Money is `BIGINT` minor units plus a currency code.** Never `float`, never
   `double`, never a `BigDecimal` constructed from a `double`.
3. **The ledger table is append-only.** Every balance change goes through
   `WalletService`, in a single transaction that writes the ledger row first.
4. **Every endpoint requires a valid Supabase JWT** unless it is explicitly listed
   as public. Authorization checks live in the service layer — not only in controllers.
5. **Admin role is read only from the JWT `app_metadata` claim.** Never from
   `user_metadata`, which is client-writable.
6. **Secrets come from environment variables only.** Never hardcode them. Never log
   secrets, tokens, or full JWTs.
7. **Every new endpoint ships with:** input validation, the standard error format,
   an integration test, and a matching request in `http/`.
8. **Prefer small, reviewable changes.** Explain any deviation from these rules
   before making it.
9. **The API contract is `docs/API.md`, served under `/api/v1`.** Paths, requests
   and responses must match it. Endpoints marked *Proposed* in
   `docs/FEATURE_MANIFEST.md` may be built, and must be added to `docs/API.md`
   when they are. Never invent any other endpoint.
10. **Response conventions.** Lists are `{ items, nextCursor }`. Counts are plain
    integers. `timeAgo` is pre-formatted server-side and sent with `createdAt`
    alongside it. Per-viewer flags (`isMine`, `isYou`, `isFollowing`,
    `myReaction`, `saved`) are computed from the authenticated session, never
    taken from the client.
11. **Privacy between users.** Blocks are two-way and apply in every query.
    Mutes are one-way. A phone number never appears in a response about
    another user.

## How to run locally

> Placeholders — fill in as the pieces land.

### Prerequisites

- JDK 21 (see above)
- Docker Desktop (required by the Supabase CLI for local database work)
- Node.js (for `edge-worker/` and the Supabase CLI via `npx`)

### Environment

Copy `.env.example` to `.env` and fill in:

```
TODO: SUPABASE_URL=
TODO: SUPABASE_JWT_SECRET=
TODO: SUPABASE_DB_URL=
TODO: R2_ACCOUNT_ID=
TODO: R2_ACCESS_KEY_ID=
TODO: R2_SECRET_ACCESS_KEY=
TODO: R2_BUCKET=
```

### Database

```
npx supabase start          # TODO: confirm local stack vs. linked remote
npx supabase db push        # apply migrations
```

### Backend

```
TODO: cd backend && ./mvnw spring-boot:run
```

### Edge worker

```
TODO: cd edge-worker && npm run dev
```

### Tests

```
TODO: cd backend && ./mvnw verify
```
