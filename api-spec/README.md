# Nichez API spec

Every endpoint of the Nichez backend, generated from the code. Don't edit these files by
hand: run `./scripts/export-api-spec.ps1` after changing a controller or DTO, and commit
the result.

- **Production:** `https://nichez-api.onrender.com/api/v1`
- **Local:** `http://localhost:8080/api/v1`
- **Human-written contract** (shapes, rules, screen by screen): [`docs/API.md`](../docs/API.md)
- **Error codes:** [`docs/API_ERRORS.md`](../docs/API_ERRORS.md)

| File | What it is | Use it for |
|---|---|---|
| [`endpoints.json`](endpoints.json) | Flat index, one entry per endpoint: group, method, full and relative path, public or not, parameters, request example, response schema | Quick lookups, scripts, wiring the Flutter services |
| [`openapi.json`](openapi.json) | OpenAPI 3 document, with every request and response schema | Swagger Editor, code generators, Insomnia, Bruno |
| [`openapi.yaml`](openapi.yaml) | The same document as YAML | Reading it yourself, tools that prefer YAML |
| [`postman/Nichez.postman_collection.json`](postman/Nichez.postman_collection.json) | Postman collection: one folder per feature, a ready request for every endpoint | Trying the API by hand |
| [`postman/*.postman_environment.json`](postman/) | Environments for production and local | Choosing which server Postman calls |

## Trying it in Postman

1. **Import** the collection and both environment files.
2. Pick the **Nichez · production** environment (top right) and fill in `supabaseAnonKey`
   (Supabase → Project Settings → API), `email` and `password` for a test user.
3. Run **0 · Get a token → Sign in**. It saves `accessToken` into the environment.
4. Every other request now sends `Authorization: Bearer {{accessToken}}`. Tokens last an
   hour; run Sign in again when you get `401 TOKEN_INVALID` or `TOKEN_EXPIRED`.

The free Render plan sleeps after 15 minutes without traffic. The first request after that
can take up to a minute.

## Conventions

- **Auth:** a Supabase access token as `Authorization: Bearer <token>` on every endpoint
  except the six marked `"public": true` (`security: []` in OpenAPI).
- **Lists:** `{ items, nextCursor }`. Pass `?cursor=<nextCursor>&limit=<n>` for the next page.
- **Errors:** `application/problem+json`, `{ type, title, status, detail, code, requestId, errors? }`.
  Branch on `code`, never on `detail`.
- **Retries:** creates and payments take an `Idempotency-Key` header; Postman fills it with a
  fresh GUID on each send.

Request examples are generated from the schemas (`"string"`, `0`, `false`), so replace them
with real values. Required fields and limits are in the schemas in `openapi.json`.
