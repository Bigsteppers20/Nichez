# API errors

Every failure, on every endpoint, uses one body: an RFC 9457 `ProblemDetail`
served as `application/problem+json`.

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed.",
  "code": "VALIDATION_FAILED",
  "requestId": "7c0f6a52-9f0e-4d57-8a38-5f7a1c1e2b11",
  "errors": [{ "field": "name", "message": "size must be between 0 and 5" }]
}
```

- **`code`** is the stable contract. Clients branch on it, never on `detail`.
  It is always `UPPER_SNAKE`.
- **`detail`** is human-readable and may change. It never contains secrets,
  token contents, SQL, stack traces, or rejected input values.
- **`requestId`** matches the `X-Request-Id` response header. Quote it in bug
  reports. A client may send its own `X-Request-Id` (8 to 64 characters of
  letters, digits, `.`, `_`, `-`); anything else is replaced.
- **`errors`** appears only for validation failures. It lists `field` and
  `message`, never the rejected value.

Paginated successes are `{ "items": [...], "nextCursor": "..." | null }`.

## Maintaining this file

Add a row here in the same change that introduces a code. Framework codes live
in `app.nichez.common.ErrorCode`. Feature codes are passed to `ApiException`
(or `NotFoundException`, `ConflictException`) by the feature itself and are
added to the feature table below. A new code must be `UPPER_SNAKE`; the
`ApiException` constructor rejects anything else.

## Framework codes

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Body, parameter or path value failed validation. Has `errors`. |
| 400 | `BAD_REQUEST` | Body missing or malformed, or another request-shape problem. |
| 401 | `AUTHENTICATION_REQUIRED` | No bearer token was sent. |
| 401 | `TOKEN_EXPIRED` | Token is valid but past `exp` (more than 30 s of clock skew). Refresh the session and retry. |
| 401 | `TOKEN_INVALID` | Bad signature, wrong issuer, wrong audience, malformed, or the subject is not a user id. Do not retry; sign in again. |
| 403 | `ACCESS_DENIED` | Authenticated, but not allowed. Includes non-admins on `/api/v1/admin/**`. |
| 404 | `NOT_FOUND` | No such route, or a resource the caller may not know about. |
| 405 | `METHOD_NOT_ALLOWED` | The route exists but not for this HTTP method. |
| 406 | `NOT_ACCEPTABLE` | The server cannot produce a response type the client accepts. |
| 409 | `CONFLICT` | Generic state conflict. Prefer a feature code. |
| 413 | `PAYLOAD_TOO_LARGE` | Request body over the size limit. |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Request `Content-Type` not supported. |
| 422 | `IDEMPOTENCY_KEY_MISMATCH` | An `Idempotency-Key` was reused with a different request body. |
| 429 | `RATE_LIMITED` | Too many requests. `Retry-After` header gives seconds to wait. |
| 500 | `INTERNAL_ERROR` | Unexpected failure. Generic message; the cause is in server logs under `requestId`. |

## Feature codes

None yet. Add rows as `| status | CODE | feature | when |`.
