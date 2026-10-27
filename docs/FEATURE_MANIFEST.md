# Nichez Feature Manifest

Status: plan. Every screen in the Flutter app is built, but all of them run on `lib/dev/sample_data.dart`. Only sign-in (Google/Apple via Supabase) is wired to a real backend, and only on prod. This document lists what has to exist on the server for each screen to work for real, in the order worth building it.

Derived from the app as it stood on 2026-10-23: 45 routes, 15 features, and every `on…` action on every screen. Companion documents `earnings.md`, `reels.md` and `live.md` live in the Flutter repo, not here.

## About this document

- **`docs/API.md` is the contract.** It owns paths, request bodies and responses under `/api/v1` (CLAUDE.md rule 9). The paths below are written as `/v1/...` to show intent; where they differ from `API.md`, `API.md` wins.
- **Nothing below is marked *Proposed* yet.** Per rule 9, an endpoint that is not in `API.md` may be built only once it is marked **Proposed** here, and must then be added to `API.md`. Mark the areas you want green-lit.
- This is a plan, not a status board. What exists today is in [Built so far](#built-so-far).

## Where this plan differs from the repo

Written against the app, so a few of its assumptions differ from CLAUDE.md and `API.md`. Until the owner decides otherwise, CLAUDE.md and `API.md` govern.

| Topic | This plan says | Repo says | Needs a decision? |
|---|---|---|---|
| Where logic runs | Plain reads/writes go through PostgREST behind RLS; logic goes in Edge Functions (`fn`) | One Spring Boot service under `/api/v1`; authorization in the service layer (rule 4) | Yes: does the client ever call PostgREST directly? |
| Media | Supabase Storage for images; Mux or Cloudflare Stream for video | Cloudflare R2 for images and video, Cloudflare Worker for playback | Yes |
| Reward threshold | Monetisation check at 100k qualifying views | Reward at 10,000 streams | Yes |
| JSON casing | `snake_case` (`my_reaction`, `hls_url`, `next_cursor`) | `camelCase` (`myReaction`, `nextCursor`), rule 10 | No: camelCase |
| Error body | `{ error: { code, message } }` | `API_ERRORS.md`: `code` at the top level | No: `API_ERRORS.md` |
| Handle rules | 3–24 chars, `[a-z0-9._]`, lower case | 3–30 chars, `a-zA-Z0-9_.`, stored as typed, compared ignoring case | Yes |
| Minimum niches | At least 1 | At least 3 (`PUT /me/niches`, and `onboarded_at`) | Yes |
| Handle check | `GET /handles/{handle}/availability` | `GET /handles/check?handle=` | No: `API.md` |
| Suggested creators | `GET /onboarding/suggested-creators` | `GET /creators/suggested` | No: `API.md` |
| Phone OTP | Direct Supabase Auth calls (`/auth/v1/otp`, `/verify`) | The backend wraps them: `/api/v1/auth/phone/{start,verify,resend}` | No: `API.md` |
| Post delete | Not listed | `DELETE /posts/:id` is built and `API.md` cites it as *Proposed* in this manifest | Added to §3.6 below |
| Money | Integers in kobo | `BIGINT` minor units plus a currency code (rule 2) | No: the same thing; every money column also carries `currency` |

## Built so far

Endpoints in `backend/` today:

| Endpoint | Notes |
|---|---|
| `GET /api/v1/ping` | Public |
| `POST /api/v1/auth/phone/{start,verify,resend}` | Public, rate limited |
| `GET /api/v1/handles/check` | |
| `PUT /api/v1/me/handle` | |
| `POST /api/v1/uploads/sign` | Presigned direct-to-R2 upload |
| `POST /api/v1/posts` · `GET /api/v1/posts/{id}` · `DELETE /api/v1/posts/{id}` | Post creation, detail, soft delete with a 30-day media purge job |
| `GET /api/v1/niches/{id}/stats` | |

Documented in `API.md` but not built: `/auth/logout`, `/auth/session`, the rest of onboarding (`/niches/taxonomy`, `/me/niches`, `/creators/suggested`, follow/unfollow), feed, reactions, comments, explore, search, profile, safety, communities, activity, sharing.

Tables in `supabase/migrations/`: `phone_challenges`, `phone_otp_sends`, `audit_events` (append-only), `profiles`, `niche_groups`, `niches`, `user_niches`, `niche_moderators`, `follows`, `reserved_handles`, `media`, `posts`, `post_tags`, `media_processing_jobs`.

Every feature package from the repo layout exists. Most contain only `package-info.java` so far.

## Planned: schema registry

A table in Supabase that logs the database schema of each service, so the schemas are recorded in one place.

A **service** here is an API area: a group of endpoints under `/api/v1` that receive requests and send payloads, as grouped in §3 below (3.1 Auth & session … 3.18 App config). It is not a UI feature, a Java package, or a third-party provider (Supabase Auth, the video service, FCM and so on; their schemas are not ours). The backend is still one Spring Boot deployable, so a service is a logical boundary, not a separately deployed process.

Not designed yet, and no migration exists. When it lands it follows rule 1 (a new timestamped file in `supabase/migrations/`) and gets RLS like every other table.

---

# The plan

## 1. Architecture

```
 Flutter app (Android / iOS / web)
   Riverpod · go_router · feature folders: domain / data / application / presentation
        │
        │  HTTPS (JSON)  ·  WebSocket (realtime)  ·  direct uploads
        ▼
 Supabase  (prod branch only — dev never reaches it)
   ├─ Auth ............ phone OTP, Google, Apple; JWT on every request
   ├─ Postgres ........ all data; row-level security on every table
   ├─ PostgREST/RPC ... simple reads and writes the RLS rules already protect
   ├─ Edge Functions .. anything with logic, secrets or third parties
   ├─ Storage ......... avatars, covers, community icons, images
   ├─ Realtime ........ notifications badge, comments, live chat, upload status
   └─ Cron jobs ....... view roll-ups, trending, earnings, payouts
        │
        ├─ Video service (Mux or Cloudflare Stream): upload, transcode, HLS, thumbnails
        ├─ Live service (LiveKit, Agora, Mux…): broadcast + viewer tokens
        ├─ Push: Firebase Cloud Messaging (Android, web) and APNs (iOS)
        ├─ SMS for OTP (Twilio, Termii…)
        ├─ Payouts + bank resolution (Paystack or Flutterwave) · KYC (Dojah, Smile ID…)
        ├─ Ads (AdMob or similar) — the revenue behind earnings
        └─ PostHog (product analytics) · Sentry (crashes) — already in the app
```

**How to read the endpoint lists.** They are written as one REST API under `/v1` so the contract is clear. On Supabase, the plain reads and writes become tables/views behind RLS (no custom code). Anything marked **fn** needs an Edge Function, because it has business rules, secrets, third parties, or touches other people's rows. (See [Where this plan differs from the repo](#where-this-plan-differs-from-the-repo): in this repo that logic lives in the Spring Boot service.)

**Priority.** P0 = needed for a first public release · P1 = soon after · P2 = later. Mostly the order follows what the screens already show.

## 2. Conventions

| Topic | Rule |
|---|---|
| Auth | `Authorization: Bearer <supabase JWT>` on every call. Anonymous calls only for config and public share links. |
| IDs | UUIDs. Handles are unique, case-insensitive, `[a-z0-9._]`, 3–24 chars. |
| Lists | Cursor pagination: `?cursor=…&limit=20` → `{ items: [...], next_cursor }`. Never page numbers (the feed is endless and shifts). |
| Counts | Returned as integers; the app formats them ("1.2k"). Counters are denormalised and updated by triggers, not counted per request. |
| "Mine" flags | Every post/comment/user carries the viewer's state: `my_reaction`, `saved`, `following`, `joined`… so a screen never needs a second call. |
| Time | ISO 8601 UTC. The app shows "2h" itself. |
| Media | URLs are signed/CDN URLs returned by the API; videos are HLS (`hls_url`) plus a `poster_url`. |
| Money | Integers in kobo. Never floats. |
| Errors | `{ error: { code, message } }` with stable codes (`handle_taken`, `not_member`, `rate_limited`…); the app maps codes to copy. |
| Idempotency | Create/payment calls accept `Idempotency-Key` so a retry on a bad network doesn't double-post or double-pay. |
| Rate limits | Per user on writes (comments, reports, follows, OTP sends). |

## 3. Endpoints by area

### 3.1 Auth & session — screens A1–A3, signed-in welcome · P0

Mostly provided by Supabase Auth; listed so nothing is missed.

| Method & path | What it does | Screen |
|---|---|---|
| `POST /auth/v1/otp` | Send an SMS code to a phone number (+234…) | A2 Log in · Send code |
| `POST /auth/v1/verify` | Check the code, return a session | A3 Verify · Resend / Call instead |
| `POST /auth/v1/token?grant_type=id_token` | Sign in with a Google or Apple ID token (already built on prod) | A1 Welcome |
| `POST /auth/v1/token?grant_type=refresh_token` | Refresh the session | everywhere |
| `POST /auth/v1/logout` | Sign out | Settings · Log out |
| `GET /v1/me` | The signed-in user: profile, onboarding progress (`has_handle`, `has_niches`, `has_photo`) — decides where the app lands after sign-in | Signed-in welcome, router guard |

### 3.2 Onboarding — profile photo, A4 username, A5 niches, A6 creators · P0

| Method & path | What it does | Screen |
|---|---|---|
| `GET /v1/handles/{handle}/availability` | Is this handle free? Debounced as the person types | A4 Claim your handle |
| `PUT /v1/me/handle` **fn** | Claim it (atomic; `handle_taken` if lost a race) | A4 · Continue |
| `POST /v1/uploads/image` **fn** | Signed URL for an avatar / cover / icon upload | Profile photo, Edit profile, Create community |
| `PUT /v1/me/avatar` · `DELETE /v1/me/avatar` | Set or remove the profile photo | Profile photo · Remove |
| `GET /v1/niches/taxonomy` | The niche groups and options, incl. which groups are single-choice (Religion) | A5 Pick niches |
| `PUT /v1/me/niches` **fn** | Save picks (≥1; enforces single-choice groups) | A5 · Continue |
| `GET /v1/onboarding/suggested-creators` | Creators to follow for the chosen niches | A6 |
| `POST /v1/me/follows` | Follow several at once | A6 · Enter |

### 3.3 Home feed — B1, stories, post actions, share sheet · P0

| Method & path | What it does | Screen |
|---|---|---|
| `GET /v1/feed?cursor=` **fn** | The personalised, niche-filtered feed. Each item is a full post card (author, media, counts, reaction breakdown, `my_reaction`, `saved`, sensitive + reason) | B1 · scroll, pull to refresh, load more |
| `GET /v1/stories` | The story row: people you follow with new stories, who's live, seen/unseen | B1 story row |
| `POST /v1/views` **fn** | Batched playback reports `{post_id, watched_ms}`; the server decides what qualifies (see `earnings.md`) | every video shown |
| `PUT /v1/posts/{id}/reaction` · `DELETE …` | Set / clear your reaction (one of 6) | B1 heart, long-press palette; E1 |
| `POST /v1/posts/{id}/reshares` | Reshare to your followers | B1 rail |
| `PUT /v1/posts/{id}/save` · `DELETE …` | Save (optionally into a collection) / unsave | B1 bookmark, Undo toast; E1 |
| `POST /v1/posts/{id}/shares` | Count a share-out (`link`, `send`, `cross_post`, `save_image`) — feeds the share count | E5 Share sheet |
| `GET /v1/posts/{id}/share-link` | A public link (`nichez.app/p/…`) for Copy link | Post actions, Share sheet |
| `POST /v1/posts/{id}/cross-post` **fn** | Share into another niche | Share sheet · Share to another niche |
| `POST /v1/posts/{id}/not-interested` | Downrank similar posts | Post actions |
| `GET /v1/me/share-targets` | People for the Send-to row | Share sheet |

### 3.4 Post detail & analytics — E1, analytics sheet · P0 (earnings parts P1)

| Method & path | What it does | Screen |
|---|---|---|
| `GET /v1/posts/{id}` | One post with everything the card shows, plus `top_comment` | E1 Post detail, shared links |
| `GET /v1/posts/{id}/analytics` | Views, views this week / last week, engagement, reaction breakdown, reshares; for the owner also earning progress and earnings | E1 analytics, analytics sheet, Profile · Analytics |
| `GET /v1/links/{code}` (public) | Resolve a share link to a post (app deep link + web fallback) | opening a shared link |

### 3.5 Comments — B2 · P0

| Method & path | What it does | Screen |
|---|---|---|
| `GET /v1/posts/{id}/comments?sort=top&cursor=` | Threaded comments with scores and `is_mine` | B2 Comments |
| `POST /v1/posts/{id}/comments` | Add a comment or a reply (`parent_id`) | B2 composer, replying-to |
| `PUT /v1/comments/{id}/reaction` · `DELETE …` | React to a comment | B2 |
| `DELETE /v1/comments/{id}` | Delete your own comment | B2 |
| Realtime `comments:post_id` | New comments appear while open | B2 |

### 3.6 Creating posts — C1 Create, G1–G4, reels · P0 (stories, reels P1)

| Method & path | What it does | Screen |
|---|---|---|
| `POST /v1/uploads/media` **fn** | Start an upload: returns a direct, resumable upload URL from the video service (or Storage for images) | C1 → G3 Uploading |
| `GET /v1/me/post-targets` | Niches/communities you can post into | G2 Caption & tag |
| `GET /v1/users/search?q=` | People to tag | G2 · Tag people |
| `POST /v1/posts` **fn** | Publish: `{media_ids[], caption, niche_ids[], audience, comments_enabled, tagged_user_ids[], kind: post}`. Runs safety checks before it's visible | G2 · Post |
| `GET /v1/posts/{id}/status` + Realtime | `processing` → `ready` / `rejected` for the progress screen | G3 Uploading → G4 Published |
| `DELETE /v1/posts/{id}` | Delete (author) or remove (niche moderator, admin) a post. Not in the original plan; built, and cited by `API.md` as *Proposed* | ConfirmDialog |
| `GET /v1/me/drafts` · `POST` · `DELETE /v1/drafts/{id}` | Drafts (the Drafts tile shows a count) | C1 Drafts |
| `POST /v1/reels` **fn** | Publish a reel: source clips + the edit list (trims, sound, text, cover); starts a server render (see `reels.md`) | Reel editor · Next |
| `POST /v1/uploads/sound` **fn** | Upload a sound picked from the phone for a reel | Reel editor · Audio |
| `POST /v1/stories` **fn** | Post a story `{media_id, hidden_from_user_ids[], allow_replies, allow_sharing}`; expires after 24h | C1 Story mode |
| `GET /v1/users/{id}/stories` · `POST /v1/stories/{id}/views` | Watch someone's stories; mark seen | story row → viewer (not built yet) |
| `GET /v1/templates` | Templates tile | C1 Templates · P2 |

### 3.7 Live — Go live, live room, ended · P1 (see `live.md`)

| Method & path | What it does | Screen |
|---|---|---|
| `POST /v1/live` **fn** | Create a live: settings (archive, hidden-from) → returns the broadcaster token from the live service | Go live |
| `POST /v1/live/{id}/start` · `POST /v1/live/{id}/end` **fn** | On air / off air; end returns the summary (duration, peak viewers, joined, comments, hearts) | Live room → Ended |
| `GET /v1/live/now` | Who's live now (LIVE badges in the story row) | B1 story row |
| `POST /v1/live/{id}/join` **fn** | Viewer token for watching | viewer screen (not built yet) |
| `POST /v1/live/{id}/comments` · `POST /v1/live/{id}/hearts` | Chat and hearts | Live room |
| Realtime `live:id` | Viewer count, joins, comments, hearts to everyone in the room | Live room |
| `GET /v1/me/live-archive` | Saved lives | archive (not built yet) |
| Webhook from the live service **fn** | Recording ready, stream ended unexpectedly | — |

### 3.8 Explore & search — B3, F1–F3 · P0

| Method & path | What it does | Screen |
|---|---|---|
| `GET /v1/explore` | Trending niches (members, joined today) + a grid of recent memes | B3 Explore |
| `GET /v1/search/suggestions?q=` | Type-ahead: niches, sub-threads, people, past queries | F1 Search |
| `GET /v1/search?q=&type=&filters=` | Results (posts, niches, people) | F2 Results |
| `GET /v1/niches/promoted?q=` | What to show when nothing matches (create or join a niche) | F3 No results |

### 3.9 Communities (niches) — D1–D4, S2, I1–I4 · P0 (management P1)

| Method & path | What it does | Screen |
|---|---|---|
| `GET /v1/communities?tab=joined\|discover&q=` | Browse and search | D1 Discover communities |
| `GET /v1/communities/{id}` | Profile: name, emoji/icon, cover, description, members, private?, your membership state | D2 Community profile |
| `GET /v1/communities/{id}/posts?cursor=` | Its posts | D2 |
| `POST /v1/communities/{id}/membership` · `DELETE …` | Join (or request, if private) / leave / withdraw a request | D1, D2 Join · I2 Withdraw |
| `POST /v1/communities` **fn** | Create: name, icon, description, privacy, home niche | D3 → D4 → S2 Empty niche |
| `GET /v1/communities/{id}/members?role=` | Members and roles | I1 Members & roles |
| `PATCH /v1/communities/{id}/members/{user_id}` · `DELETE …` **fn** | Change a role (owner/mod/member), remove | I1 member actions |
| `GET /v1/communities/{id}/requests` · `POST …/{user_id}/approve\|deny` **fn** | Join requests for private communities | I2 / mod tools |
| `POST /v1/communities/{id}/invites` · `GET …/invite-link` | Invite people in the app, or by link/SMS | I4 Invite friends |
| `GET /v1/communities/{id}/moderation/queue` | Reported posts waiting for a decision | I3 Moderation queue |
| `POST /v1/reports/{id}/verdict` **fn** | `keep` · `remove` · `ban` | I3 |

### 3.10 Profiles & follows — H1–H3, profile tabs · P0

| Method & path | What it does | Screen |
|---|---|---|
| `GET /v1/users/{id_or_handle}` | Profile header: name, handle, bio, avatar, cover, verified, counts, niches, following | H1 Profile, your Profile |
| `GET /v1/users/{id}/posts?cursor=` | Posts grid with view counts | Profile · Posts / Media |
| `POST /v1/users/{id}/follow` · `DELETE …` | Follow / unfollow | H1, H2, A6, E1 Follow |
| `GET /v1/users/{id}/followers?tab=followers\|following\|mutual&q=` | Lists with search | H2 Followers |
| `PATCH /v1/me` | Name, bio, handle, niches | H3 Edit profile, Settings |
| `PUT /v1/me/cover` | Cover image | H3 |
| `GET /v1/me/analytics` | Totals and per-video performance, most-viewed first; earnings when eligible | Profile · Analytics tab |

### 3.11 Saved & collections — C3, Saved tab · P1

| Method & path | What it does | Screen |
|---|---|---|
| `GET /v1/me/saved?cursor=` | Everything you've saved | Profile · Saved |
| `GET /v1/me/collections` · `POST` · `PATCH /v1/collections/{id}` · `DELETE …` | Your collections (create, rename, delete) | C3 Collections · New |
| `GET /v1/collections/{id}/posts` | One collection | C3 |
| `PUT /v1/collections/{id}/posts/{post_id}` · `DELETE …` | Add / remove a post | Post actions · Save to collection |

### 3.12 Notifications & push — Activity tab, push prompt · P0

| Method & path | What it does | Screen |
|---|---|---|
| `GET /v1/notifications?cursor=` | Reactions, follows, reshares, comments, mentions; unread flags | Activity |
| `POST /v1/notifications/read-all` · `POST /v1/notifications/{id}/read` | Mark read | Activity · Mark all read |
| Realtime `notifications:user_id` | Unread badge updates live | nav bar badge |
| `POST /v1/devices` · `DELETE /v1/devices/{token}` | Register / remove a push token (FCM/APNs) | after the push prompt, on log out |
| `GET /v1/me/notification-settings` · `PATCH …` | Which pushes you get | Settings · Push |
| Server → FCM/APNs **fn** | Send the pushes (batched, quiet hours) | — |

### 3.13 Safety & moderation — E3/E4, J2, H4 · P0

| Method & path | What it does | Screen |
|---|---|---|
| `GET /v1/report-reasons` | The reasons list | E3 Report |
| `POST /v1/reports` **fn** | Report a post, comment, user or community | E3 → E4 Report received |
| `POST /v1/users/{id}/mute` · `DELETE …` | Mute (their posts stop showing) | Post actions, E4 Mute author, H4 Unmute |
| `POST /v1/users/{id}/block` · `DELETE …` | Block | H4 Unblock |
| `GET /v1/me/muted` · `GET /v1/me/blocked` | The lists | H4 Muted & blocked |
| `GET /v1/me/content-preferences` · `PATCH …` | Sensitive-content filter (the J2 veil) | J2 · Content preferences |
| Automated checks **fn** | Scan uploads (nudity, violence, spam) before publishing; sets `sensitive` + reason | — |

### 3.14 Settings & account — C5, J5 · P0 (data export P1)

| Method & path | What it does | Screen |
|---|---|---|
| `GET /v1/me/settings` · `PATCH …` | Server-side settings (dark mode stays on the phone) | C5 Settings |
| `POST /v1/me/data-export` **fn** | Email a download of your data | J5 · Download my data |
| `POST /v1/me/deactivate` | Hide the account until next sign-in | J5 · Deactivate |
| `DELETE /v1/me` **fn** | Delete the account and its content (after a grace period) — required by both app stores | J5 · Delete |

### 3.15 KYC — identity verification · P1 (before the first withdrawal)

Confirms a real person behind an account before any money leaves Nichez: government ID (BVN or NIN), a selfie that matches it, and a bank account in the same name. Done through a Nigerian KYC provider (Dojah, Smile ID, Prembly…); Nichez never sees or stores more than it needs.

**When it's asked for:** only when someone first tries to withdraw (or a brand deal needs paying), not at sign-up — see `earnings.md`. Sign-in with Google or Apple is separate and needs no KYC.

| Method & path | What it does | Screen |
|---|---|---|
| `GET /v1/me/kyc` | Status: `not_started` · `pending` · `verified` · `failed` · `needs_review`, the reason if it failed, and what's still missing | Wallet, Withdraw |
| `POST /v1/me/kyc/sessions` **fn** | Start a verification with the provider; returns a session/token for its SDK or web flow (ID capture, selfie, liveness) | KYC start |
| `POST /v1/me/kyc/identity` **fn** | Submit BVN or NIN, name and date of birth; the server checks it against the provider's registry lookup | KYC · ID step |
| `POST /v1/me/kyc/selfie` **fn** | Upload the selfie / liveness capture (if the provider's SDK doesn't send it directly) | KYC · selfie step |
| `POST /v1/me/kyc/bank-account` **fn** | Add the payout account; the server resolves the account name (Paystack/Flutterwave) and checks it matches the verified ID name | KYC · bank step |
| `POST /v1/me/kyc/resubmit` **fn** | Try again after a failure (limited attempts per day) | KYC · failed state |
| Provider webhook `POST /v1/webhooks/kyc` **fn** | The provider reports the result; the server updates the status and notifies the user | — |
| Admin: `GET /v1/admin/kyc?status=needs_review` · `POST /v1/admin/kyc/{id}/approve\|reject` **fn** | Manual review of borderline cases | back office |

Rules the server enforces:

- One verified identity per person: the same BVN/NIN can't verify two accounts (store a salted hash of the number, not the number).
- The bank account name must match the verified name; a change of bank account re-runs the match.
- Payouts (`POST /v1/me/payouts`) are refused until status is `verified`.
- Keep only what the law and the provider require; the raw ID images stay with the provider. Deleting an account deletes Nichez's KYC record after any legally required retention period.

Tables: `kyc_records` (user, status, provider reference, ID number hash, verified name, verified_at, failure reason) · `bank_accounts` (user, bank code, account number last 4, resolved name, name_matches, verified_at).

### 3.16 Earnings, wallet & payouts · P1 (see `earnings.md`)

| Method & path | What it does | Screen |
|---|---|---|
| `GET /v1/me/wallet` | Pending, available, paid totals | wallet (not built yet) |
| `GET /v1/me/wallet/ledger?cursor=` | Every credit and payout | wallet history |
| `GET /v1/me/earnings?month=` | Per-video earnings for a month | Profile · Analytics |
| `GET /v1/banks` | Bank list for the bank step of KYC | KYC · bank step |
| `POST /v1/me/payouts` · `GET /v1/me/payouts` **fn** | Withdraw (₦2,000 creator / ₦1,000 viewer minimum; KYC must be verified — §3.15) and history | withdraw |
| `GET /v1/me/brand-deals` · `POST /v1/brand-deals/{id}/accept\|decline` | Brand deals | P2 |
| Payout provider webhook **fn** | Transfer succeeded / failed → ledger | — |

### 3.17 Direct messages · P2

The Message button on profiles and the Send-to row in the share sheet point here; neither has a screen yet.

| Method & path | What it does |
|---|---|
| `GET /v1/conversations` · `POST /v1/conversations` | Inbox; start a chat |
| `GET /v1/conversations/{id}/messages?cursor=` · `POST …` | Read and send (text, shared posts) |
| Realtime `conversation:id` | Live delivery and read receipts |

### 3.18 App config · P0

| Method & path | What it does |
|---|---|
| `GET /v1/config` (public) | Minimum app version (force update), feature flags (e.g. Live on/off), ad settings, links to terms/privacy |

## 4. Background jobs and webhooks

| Job | When | What it does |
|---|---|---|
| Feed ranking | continuous | Builds each user's candidate feed from their niches, follows, engagement, freshness; removes muted/blocked |
| View roll-up | every few minutes | Qualifies raw view events, updates post counters and daily stats |
| Monetisation check | with the roll-up | Marks posts that cross 100k qualifying views; freezes their early viewers |
| Trending niches | hourly | "Joined today", trending order for Explore |
| Story expiry | hourly | Hides stories after 24 h (archived if the setting is on) |
| Notification fan-out | on events | Writes notification rows, sends pushes (batched, respects settings) |
| Video processing webhook | on upload | Video service → ready / rejected, poster, duration; triggers safety scan |
| Reel render | on publish | Renders the edit list into one video (see `reels.md`) |
| Monthly earnings | month end | Ad revenue ÷ views → per-video earnings → ledger (see `earnings.md`) |
| Holdback release | daily | Moves credits from pending to available after 30 days |
| Payout webhook | on transfer | Marks payouts paid / failed |
| KYC webhook | on provider result | Updates the KYC status, notifies the user, unblocks payouts |
| Account deletion | daily | Deletes accounts past their grace period, incl. media |

## 5. Data tables (summary)

`profiles` · `handles` · `niches` (+ groups) · `user_niches` · `follows` · `posts` · `post_media` · `post_niches` · `post_tags` · `reactions` · `reshares` · `saves` · `collections` · `collection_posts` · `shares` · `comments` · `comment_reactions` · `stories` · `story_views` · `story_hidden_from` · `communities` · `community_members` · `community_requests` · `community_invites` · `reports` · `moderation_actions` · `mutes` · `blocks` · `notifications` · `devices` · `notification_settings` · `content_preferences` · `drafts` · `live_sessions` · `live_comments` · `view_events` · `post_daily_views` · `early_viewers` · `revenue_periods` · `post_earnings` · `wallets` · `ledger_entries` · `kyc_records` · `bank_accounts` · `payouts` · `brand_deals` · `conversations` · `messages`

Earnings tables are specified column by column in `earnings.md`.

## 6. Suggested build order

1. **Accounts:** `/me`, handle claim, avatar upload, taxonomy, niches, suggested creators, follows, config. The app becomes signable-into end to end.
2. **Posting and the feed:** media upload + processing, `POST /posts`, feed, post detail, reactions, saves, views, share links. Real content flows.
3. **Conversation:** comments, notifications + push, profiles, followers.
4. **Discovery:** explore, search, communities (join, browse, create).
5. **Safety:** reports, mute/block, content preferences, moderation queue, automated scans, account deletion. Needed before any public launch — the stores require reporting, blocking and deletion.
6. **Creator tools:** analytics, collections, drafts, stories, reels render.
7. **Live** (after choosing a live service).
8. **Money:** views qualification → earnings → wallet → KYC (identity, selfie, bank-name match) → payouts.
9. **Later:** direct messages, brand deals, templates.
