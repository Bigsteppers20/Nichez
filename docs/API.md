> **Source:** copied from `C:\Nichez\design\API.md` in the Nichez Flutter repo on 2026-10-03. The Flutter repo's copy is the original.

# Nichez V1 — API surface

Every endpoint the 49 built screens need, derived from what each screen
renders and what its callbacks report outward.

The screens are pure presentational widgets: they take data as constructor
params and emit intent through callbacks. Nothing in `lib/features/*/presentation`
fetches. Wiring one up means calling an endpoint below and passing the result
in — the layout never changes.

Conventions used throughout:

- Cursor pagination: `?cursor=<opaque>&limit=<n>` → `{ items: [...], nextCursor: string|null }`.
  `lib/core/network/cursor_page.dart` already models this.
- Counts the UI shows compactly ("12.4k") are sent as plain integers; the
  client formats them (`lib/core/utils/number_format.dart`).
- Relative times ("2h", "40m") are **pre-formatted by the server** in list
  payloads — the design never shows an absolute timestamp in a feed. Send
  `timeAgo: "2h"` alongside `createdAt` so the client can re-derive if needed.
- All authenticated calls carry the session bearer token
  (`lib/core/network/interceptors/auth_interceptor.dart`).

---

## 0 · Service

Served under `/api/v1`. Errors on every endpoint follow [API_ERRORS.md](API_ERRORS.md).

| Method | Path | Used by | Notes |
|---|---|---|---|
| `GET` | `/ping` | Uptime checks, client startup | **Public.** No token. → `{ status: "ok", version }` |

---

## 1 · Auth (FR-1)

| Method | Path | Used by | Notes |
|---|---|---|---|
| `POST` | `/auth/phone/start` | A2 Log in | **Public, rate limited.** `{ dialCode, nationalNumber }` → `{ challengeId, resendAfterSeconds }`. Identical response whether or not the number has an account |
| `POST` | `/auth/phone/verify` | A3 Verify OTP | **Public, rate limited.** `{ challengeId, code }` → `{ session, isNewUser }`. A wrong code returns 400 `WRONG_CODE` with `attemptsLeft` — J3 renders the counter from it |
| `POST` | `/auth/phone/resend` | A3 | **Public, rate limited.** `{ challengeId }` → `{ resendAfterSeconds }` |
| `POST` | `/auth/phone/call` | J3 | **Not implemented (decision 12).** The voice fallback is not built; the route does not exist |
| `POST` | `/auth/apple` | A1, A2 | `{ identityToken }` → `{ session, isNewUser }` |
| `POST` | `/auth/google` | A1, A2 | `{ idToken }` → `{ session, isNewUser }` |
| `POST` | `/auth/logout` | C5, J5 | |
| `GET` | `/auth/session` | app boot | Drives `AuthGuard` |

`session` — `{ accessToken, refreshToken, expiresAt }`. `expiresAt` is an ISO-8601
UTC instant: when the access token stops working. Every endpoint that returns a
`session` uses this shape.

Phone sign-in rules (all limits are config, `auth.sms.*`):

- `dialCode` is `+234` or `234`; `nationalNumber` may carry spaces, dashes or a leading `0`. Both are parsed to E.164 server-side.
- Only countries in `auth.sms.allowed-countries` are accepted.
- At most 5 SMS per phone and 20 per IP per hour (`/start` and `/resend` both count), and a 60 s cooldown between sends of one challenge. Over a limit: 429 `RATE_LIMITED` with `Retry-After`.
- A code is valid for 5 minutes from the last send. 3 wrong codes lock the phone for 30 minutes: `/verify` answers 423 `LOCKED` and `/start` and `/resend` answer 429, both with `Retry-After`. The third wrong code itself still answers 400 with `attemptsLeft: 0`.
- A wrong code is `{ code: "WRONG_CODE", attemptsLeft, ... }` in the standard error body ([API_ERRORS.md](API_ERRORS.md)); the field is `code`, not `error`.

**Attempt limiting matters to the UI.** J3 shows "2 tries left", so
`attemptsLeft` must come back on a wrong code, not just a 401.

---

## 2 · Onboarding (FR-2, FR-3, FR-4)

| Method | Path | Used by | Notes |
|---|---|---|---|
| `GET` | `/handles/check?handle=` | A4 Username | → `{ available: bool, suggestions: string[] }`. Needs a signed-in user; 60 per minute per user (429 `RATE_LIMITED`). A handle that breaks a rule is `available: false`, not an error. `suggestions` is empty when available, otherwise 3 free alternatives (the input plus digits or niche words). Debounce client-side; the screen has `HandleStatus.checking` for the in-flight state |
| `PUT` | `/me/handle` | A4 | `{ handle }` → `{ handle }`. Same rules as the check. 400 `HANDLE_INVALID`, 400 `HANDLE_RESERVED`, 409 `HANDLE_TAKEN`. Sets `onboarded_at` once the user has a handle and at least 3 niches |
| `GET` | `/niches/taxonomy` | A5 Pick niches | → `NicheGroup[]`: `{ name, strict, options: [{ id, label }] }`. **`strict: true`** makes the group single-choice (Religion). The client enforces it, the server must too |
| `PUT` | `/me/niches` | A5, C5, H3 | `{ nicheIds: string[] }` — min 3 |
| `GET` | `/creators/suggested` | A6 | → `SuggestedCreator[]`: `{ id, name, handle, niche, imageUrl, following }`. `niche` is *why* they're suggested |
| `POST` | `/users/:id/follow` | A6, H1, H2, E1 | |
| `DELETE` | `/users/:id/follow` | same | |

Handle rules (`HandleRules`):

- 3 to 30 characters from `a-z A-Z 0-9 _ .`, with at least one letter or digit. Stored as typed, compared ignoring case.
- Reserved handles (table `reserved_handles`) are refused in any letter case.
- A **lookalike** of a reserved handle or of a verified account's handle is refused too. Both sides are folded to a skeleton: lower case; `0`→`o`; `1`, `i`, `l`→`l`; `5`→`s`; dots and underscores removed; `rn`→`m`. So `ad.min`, `adm1n` and `adrnin` all collide with `admin`. A verified user's own handle is not compared against itself.

---

## 3 · Feed (FR-5)

| Method | Path | Used by | Notes |
|---|---|---|---|
| `GET` | `/feed?niche=&cursor=&limit=` | B1 Home feed | → `FeedPost[]` (shape below) |
| `GET` | `/feed/niches` | B1 tabs, S1 | The niche tab strip |
| `GET` | `/niches/:id/feed` | S2 Empty niche | Empty result drives the first-post prompt |

`FeedPost` payload — matches `lib/features/feed/domain/feed_post.dart`:

```jsonc
{
  "id": "p1",
  "author": { "id": "u1", "name": "Jessica Marshal", "handle": "pivotmaster", "avatarUrl": null },
  "caption": "spent 3 hours making a pivot table do one thing. worth it.",
  "niche": "Excel Pain",
  "mediaUrl": "https://…",
  "isVideo": false,
  "hasAudio": true,          // drives the "tap for audio" chip
  "timeAgo": "2h",
  "reactions": 1200,
  "comments": 88,
  "reshares": 34,
  "myReaction": "laugh",     // null | love|laugh|fire|cry|clap|dead
  "saved": false,
  "sensitive": false,        // true → J2 veil
  "sensitiveReason": null    // "Graphic injury clip flagged by Matchday mods."
}
```

**`myReaction` must be per-viewer.** The feed rail, the B2 reaction bar and
the S3 palette all render from it, and the client applies reactions
optimistically — so the value has to be authoritative on the next fetch.

---

## 4 · Reactions & comments (FR-6)

| Method | Path | Used by | Notes |
|---|---|---|---|
| `PUT` | `/posts/:id/reaction` | B1, B2, E1, S3 | `{ kind }` — one of the six. Replaces any existing reaction |
| `DELETE` | `/posts/:id/reaction` | same | Clears it |
| `GET` | `/posts/:id/comments?cursor=` | B2 Comments | → `Comment[]` |
| `POST` | `/posts/:id/comments` | B2 | `{ body, parentId? }` — `parentId` makes it a reply |
| `PUT` | `/comments/:id/reaction` | B2 | |
| `GET` | `/posts/:id` | E1 Post detail | → post + `topComment` |

`Comment` — matches `lib/features/comments/domain/comment.dart`:

```jsonc
{
  "id": "c1",
  "authorName": "Jessica M.",
  "body": "the 4 years part is too real.",
  "timeAgo": "1h",
  "avatarUrl": null,
  "score": 90,        // null renders the "score —" dash for new comments
  "isReply": false,
  "isMine": false     // drives the emerald bubble
}
```

---

## 5 · Explore & search (FR-7)

| Method | Path | Used by | Notes |
|---|---|---|---|
| `GET` | `/explore/trending` | B3 | → `{ id, name, members, joinedToday }[]` — the design pairs total with today's growth |
| `GET` | `/explore/recent?cursor=` | B3 | Media URLs for the grid |
| `GET` | `/search/suggest?q=` | F1 | → `{ title, subtitle, kind }[]`, `kind` ∈ `niche\|subThread\|person\|query`. **Order matters**: niches, then people, then raw queries |
| `GET` | `/search?q=&type=&cursor=` | F2 | `type` ∈ `top\|memes\|niches\|people` → `{ promotedNiche?, results: [...] }` |
| `POST` | `/niches` | F3 No results | `{ name }` — the empty search converts into niche creation |
| `POST` | `/niches/:id/join` | F2, B3 | |

---

## 6 · Posting (FR-8)

| Method | Path | Used by | Notes |
|---|---|---|---|
| `POST` | `/uploads/sign` | C1, G3 | → `{ uploadUrl, mediaId }` for a direct-to-storage PUT |
| `PUT` | `<uploadUrl>` | C1, G3 | Progress drives the in-well bar (C1) and the dot spinner (G3) |
| `POST` | `/posts` | C1, G2 | `{ mediaId, caption, nicheId, audience, allowComments, taggedUserIds }`. `audience` is `everyone` (the default) or `niche_members` (only members of the niche it is posted to). `caption` is at most 2200 characters |
| `GET` | `/me/niches` | C1, G2, D4 | The "POST TO NICHE" chips |
| `GET` | `/niches/:id/stats` | G4 Published | → `{ postsToday, freshWindow }` — G4 shows real reach, so don't fake these. Rules below. 404 `NICHE_NOT_FOUND` |
| `DELETE` | `/posts/:id` | `ConfirmDialog` (no screen wired yet) | **Proposed** (FEATURE_MANIFEST). → 204, no body. The author deletes; a moderator of the post's niche or an admin removes. Anyone else, an unknown id, and a post already deleted or removed: 404 `POST_NOT_FOUND`. Rules below |

`GET /niches/:id/stats` rules:

- `postsToday` — published posts in the niche over the **last 24 hours** (rolling, not since midnight). Deleted, removed and still-processing posts don't count. The same for every viewer.
- `freshWindow` — how long a new post can expect to stay at the top of Fresh, worded to finish G4's "Yours is at the top of Fresh for …". It is the average gap between posts over the same 24 hours (24 h ÷ `postsToday`): `"the next minute"`, `"the next 8 minutes"`, `"the next 35 minutes"` (rounded to 5 from 15 minutes up), `"the next hour"`, `"the next 12 hours"`, or `"the next day"` when the niche had at most one post.
- Cached per niche for 60 s (`Cache-Control: private, max-age=60`). Deleting or removing a published post refreshes its niche at once.

`DELETE /posts/:id` rules:

- A soft delete: the post and its media rows stay. The author's delete sets status `deleted`; a moderator's or admin's sets `removed`. An author who also moderates the niche still deletes. Admin means `app_metadata.role = "admin"` in the token.
- Allowed while the post is `processing`, `published` or `failed`.
- The author's `postCount` drops by one if the post was published.
- Every delete or removal is written to the audit trail (who, in what role, which post).
- The stored files of a deleted or removed post are erased **30 days** after the takedown by a daily job. Until then a removal can be reviewed.

Upload is **non-blocking by design**: G3 says "keep scrolling, we'll finish in
the background". The endpoint should tolerate the app being backgrounded, and
notify on completion (see §10).

---

## 7 · Profile & graph (FR-9, FR-12)

| Method | Path | Used by | Notes |
|---|---|---|---|
| `GET` | `/users/:handle` | C2, H1 | → `UserProfile` |
| `GET` | `/users/:id/posts?tab=&cursor=` | C2, H1 | `tab` ∈ `posts\|media\|saved` |
| `GET` | `/users/:id/followers?filter=&q=&cursor=` | H2 | `filter` ∈ `followers\|following\|mutual` |
| `PUT` | `/me/profile` | H3 Edit profile | `{ name, handle, bio }` |
| `POST` | `/me/avatar` | H3 | |
| `GET` | `/me/collections` | C3 | → collection names + counts |
| `GET` | `/me/collections/:id?cursor=` | C3 | |
| `POST` | `/me/collections` | C3 | `{ name }` |
| `PUT` | `/posts/:id/save` | B1, C3, E1, E2 | `{ collectionId? }` |
| `DELETE` | `/posts/:id/save` | same | Backs the toast's Undo |

`UserProfile` — `{ id, name, handle, bio, avatarUrl, coverUrl, verified, followers, following, postCount, niches: string[], isFollowing, isYou }`.
The stats strip derives the niche count from `niches.length`.

---

## 8 · Safety & moderation (FR-11)

| Method | Path | Used by | Notes |
|---|---|---|---|
| `GET` | `/niches/:id/report-reasons` | E3 | Reasons **mirror the niche rulebook** — "Off-topic for Excel Pain" is niche-specific. Falls back to `ReportReason.defaultsFor()` client-side |
| `POST` | `/posts/:id/report` | E3 | `{ reasonId, note? }` → E4. Server should hide the post from *this* user immediately |
| `POST` | `/users/:id/mute` / `DELETE` | E2, E4, H4 | Mute ≠ block: muted users still see you |
| `POST` | `/users/:id/block` / `DELETE` | H4 | |
| `GET` | `/me/restrictions?type=` | H4 | `type` ∈ `muted\|blocked\|hiddenNiches` → `{ name, reason, avatarUrl }[]` |
| `PUT` | `/me/preferences/auto-hide-reported` | H4 | |
| `POST` | `/posts/:id/not-interested` | E2 | Feed-ranking signal |
| `GET` | `/niches/:id/mod-queue?tab=&cursor=` | I3 | `tab` ∈ `reported\|requests\|resolved` → `ReportItem[]` |
| `POST` | `/reports/:id/verdict` | I3 | `{ verdict }` ∈ `keep\|remove\|ban`. Swipe-left sends `remove` |

`ReportItem` — `{ id, reason, reportCount, authorHandle, excerpt, mediaUrl, severe }`.
`severe: true` turns the reason badge red (reasons targeting a person).

---

## 9 · Communities (FR-13–15, V2)

| Method | Path | Used by | Notes |
|---|---|---|---|
| `GET` | `/communities/discover?cursor=` | D1 | → `Community[]` |
| `GET` | `/communities/:id` | D2, I2 | + `onlineCount`, `memberFaces`, pinned post |
| `POST` | `/communities` | D3, D4 | `{ name, description, emoji, isPrivate, homeNicheId }` — **every community needs a home niche** |
| `POST` | `/communities/:id/join` | D1, D2 | Public → joined; private → `{ status: "requested" }` (I2) |
| `DELETE` | `/communities/:id/join` | I2 | Withdraw request |
| `GET` | `/communities/:id/members?filter=&cursor=` | I1 | `filter` ∈ `all\|mods\|requests`; each member carries `role` ∈ `founder\|moderator\|member` |
| `GET` | `/communities/:id/rules` | I2 | Ordered strings — the private gate sells with these |
| `GET` | `/niches/:id/invite-link` | I4 | → `{ url }` e.g. `nichez.app/e/excel-pain` |
| `GET` | `/contacts/match` | I4 | `{ hashedPhones[] }` → `{ name, onNichez }[]` |
| `POST` | `/niches/:id/invite` | I4 | |

---

## 10 · Activity & notifications (FR-10, FR-11)

| Method | Path | Used by | Notes |
|---|---|---|---|
| `GET` | `/me/activity?filter=&cursor=` | C4 | `filter` ∈ `all\|follows\|reactions\|mentions` |
| `POST` | `/me/activity/read` | C4 | "Mark all read" |
| `PUT` | `/me/push-token` | J4 | |
| `PUT` | `/me/preferences/push` | C5 | |

`ActivityItem` — `{ id, actorName, message, timeAgo, kind, avatarUrl, unread }`.
`kind` ∈ `reaction\|follow\|reshare\|comment\|mention` picks the avatar badge
colour. `message` is a **continuation of the name** — "reacted to your pivot
table meme", no leading capital.

**Push copy rule (FR-10):** the notification title is the **niche name**, not
"Nichez" — J4 exists to specify this. `{ title: "Excel Pain", body: "Mara Klein reacted 🔥 to your post" }`.

---

## 11 · Sharing (FR-7)

| Method | Path | Used by | Notes |
|---|---|---|---|
| `GET` | `/me/share-targets` | E5 | Recent DM recipients for the "SEND TO" row |
| `POST` | `/posts/:id/send` | E5 | `{ userIds }` |
| `POST` | `/posts/:id/reshare` | B1, E5 | `{ nicheId }` — cross-post into another niche |
| `GET` | `/posts/:id/link` | E2, E5 | Shareable URL |

---

## Build order

If you want the app usable end to end as fast as possible:

1. **§1 Auth** + **§2 Onboarding** — unlocks A1→A6, the whole cold start.
2. **§3 Feed** + **§4 Reactions/comments** — B1, B2, E1. This is the product.
3. **§6 Posting** — C1/G1–G4 closes the create loop.
4. **§7 Profile** — C2, C3, H1–H3.
5. **§5 Explore/search**, **§10 Activity**, **§8 Safety**, then **§9 Communities** (V2).