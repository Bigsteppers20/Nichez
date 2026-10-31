"""Builds api-spec/ from the OpenAPI document springdoc generates from the controllers.

    scripts/export-api-spec.ps1        (runs the export test, then this script)
    python scripts/export-api-spec.py  (only this step, from an existing api-spec/openapi.json)

Writes, next to openapi.json:
    openapi.json, openapi.yaml       the cleaned OpenAPI 3 document (test-only routes removed, public routes marked)
    endpoints.json                   a flat index: one entry per endpoint, with its path relative to /api/v1
    postman/                         a Postman collection plus prod and local environments (Insomnia imports it too)

Standard library only. Never writes secrets: the environments leave the anon key and test login blank.
"""

import copy
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "api-spec"
API_PREFIX = "/api/v1"
PROD_URL = "https://nichez-api.onrender.com"
LOCAL_URL = "http://localhost:8080"
SUPABASE_URL = "https://bmfxcbacewsfbwfonzat.supabase.co"
METHODS = ("get", "post", "put", "patch", "delete")

# Routes that exist only in the test sources (SecurityTestController).
TEST_ONLY_TAGS = {"security-test-controller"}

# SecurityConfig.PUBLIC_PATHS, as regexes over the OpenAPI path. Keep the two in step.
PUBLIC_PATHS = [
    r"/api/v1/ping",
    r"/api/v1/auth/phone/(start|verify|resend)",
    r"/api/v1/links/\{[^/]+\}",
    r"/api/v1/config",
]

# Friendlier group names than springdoc's "<x>-controller".
GROUP_NAMES = {
    "app-config": "App config",
    "phone-auth": "Auth",
    "logout": "Auth",
    "handle": "Onboarding",
    "niche": "Niches",
    "niche-membership": "Niches",
    "profile": "Profile",
    "follow": "Graph",
    "feed": "Feed",
    "interaction": "Reactions & comments",
    "post": "Posts",
    "post-extras": "Stories, drafts & insights",
    "upload": "Uploads",
    "view": "Streams",
    "collection": "Collections",
    "search": "Search",
    "activity": "Activity",
    "safety": "Safety",
    "sharing": "Sharing",
    "community": "Communities",
    "account": "Account",
    "ping": "Service",
}


def group_of(tag):
    key = tag.removesuffix("-controller")
    return GROUP_NAMES.get(key, key.replace("-", " ").capitalize())


def is_public(path):
    return any(re.fullmatch(p, path) for p in PUBLIC_PATHS)


# ── OpenAPI cleanup ──

def clean(doc):
    doc = copy.deepcopy(doc)
    paths = {}
    for path, item in doc["paths"].items():
        ops = {m: o for m, o in item.items() if m in METHODS and not TEST_ONLY_TAGS & set(o.get("tags", []))}
        if not ops:
            continue
        for op in ops.values():
            op["tags"] = [group_of(t) for t in op.get("tags", [])]
            if is_public(path):
                op["security"] = []
        paths[path] = {**{k: v for k, v in item.items() if k not in METHODS}, **ops}
    doc["paths"] = dict(sorted(paths.items()))
    doc["servers"] = [
        {"url": PROD_URL, "description": "Production (Render)"},
        {"url": LOCAL_URL, "description": "Local backend"},
    ]
    doc["info"]["description"] = (
        "Nichez backend API, served under /api/v1. Every endpoint needs a Supabase access token "
        "(Authorization: Bearer <token>) except those with `security: []`. Errors are RFC 9457 "
        "problem+json bodies with a stable `code`; see docs/API_ERRORS.md. Contract: docs/API.md."
    )
    doc["tags"] = [{"name": n} for n in sorted({t for i in paths.values() for o in i.values() for t in o["tags"]})]
    prune_schemas(doc)
    return doc


def refs_in(node):
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "$ref" and isinstance(v, str):
                yield v.rsplit("/", 1)[-1]
            else:
                yield from refs_in(v)
    elif isinstance(node, list):
        for v in node:
            yield from refs_in(v)


def prune_schemas(doc):
    """Drops schemas only the removed test routes used."""
    schemas = doc.get("components", {}).get("schemas", {})
    keep, todo = set(), list(refs_in(doc["paths"]))
    while todo:
        name = todo.pop()
        if name in keep or name not in schemas:
            continue
        keep.add(name)
        todo.extend(refs_in(schemas[name]))
    doc["components"]["schemas"] = {k: v for k, v in sorted(schemas.items()) if k in keep}


# ── Examples from schemas ──

def example(schema, schemas, depth=0):
    if not schema or depth > 6:
        return None
    if "$ref" in schema:
        return example(schemas.get(schema["$ref"].rsplit("/", 1)[-1], {}), schemas, depth + 1)
    if "example" in schema:
        return schema["example"]
    if "enum" in schema:
        return schema["enum"][0]
    for combo in ("allOf", "oneOf", "anyOf"):
        if combo in schema:
            return example(schema[combo][0], schemas, depth + 1)
    kind, fmt = schema.get("type"), schema.get("format")
    if kind == "object" or "properties" in schema:
        return {k: example(v, schemas, depth + 1) for k, v in schema.get("properties", {}).items()}
    if kind == "array":
        item = example(schema.get("items", {}), schemas, depth + 1)
        return [] if item is None else [item]
    if kind == "integer":
        return 0
    if kind == "number":
        return 0
    if kind == "boolean":
        return False
    if kind == "string":
        return {"uuid": "00000000-0000-0000-0000-000000000000", "date-time": "2026-01-01T00:00:00Z",
                "date": "2026-01-01", "uri": "https://example.com"}.get(fmt, "string")
    return None


def schema_name(schema):
    if not schema:
        return None
    if "$ref" in schema:
        return schema["$ref"].rsplit("/", 1)[-1]
    if schema.get("type") == "array":
        inner = schema_name(schema.get("items", {}))
        return f"{inner}[]" if inner else "array"
    return schema.get("type")


def body_of(op):
    content = op.get("requestBody", {}).get("content", {})
    for ctype, media in content.items():
        return ctype, media.get("schema", {})
    return None, None


# ── endpoints.json ──

def index(doc):
    schemas = doc["components"]["schemas"]
    endpoints = []
    for path, item in doc["paths"].items():
        for method, op in item.items():
            if method not in METHODS:
                continue
            params = op.get("parameters", [])
            ctype, body = body_of(op)
            entry = {
                "group": op["tags"][0] if op["tags"] else None,
                "method": method.upper(),
                "path": path,
                "relativePath": path.removeprefix(API_PREFIX) or "/",
                "operationId": op.get("operationId"),
                "public": op.get("security") == [],
                "pathParams": [p["name"] for p in params if p["in"] == "path"],
                "queryParams": [
                    {"name": p["name"], "required": p.get("required", False), "type": schema_name(p.get("schema"))}
                    for p in params if p["in"] == "query"
                ],
                "headers": [
                    {"name": p["name"], "required": p.get("required", False)} for p in params if p["in"] == "header"
                ],
                "requestBody": None if body is None else {
                    "contentType": ctype,
                    "required": op["requestBody"].get("required", False),
                    "schema": schema_name(body),
                    "example": example(body, schemas),
                },
                "responses": {
                    code: schema_name(next(iter(r.get("content", {}).values()), {}).get("schema"))
                    for code, r in op.get("responses", {}).items()
                },
            }
            endpoints.append(entry)
    endpoints.sort(key=lambda e: (e["group"] or "", e["relativePath"], METHODS.index(e["method"].lower())))
    return {
        "name": "Nichez API",
        "version": "v1",
        "baseUrls": {"production": PROD_URL + API_PREFIX, "local": LOCAL_URL + API_PREFIX},
        "auth": {
            "type": "bearer",
            "header": "Authorization: Bearer <Supabase access token>",
            "obtainToken": f"POST {SUPABASE_URL}/auth/v1/token?grant_type=password "
                           "with header apikey: <anon key> and body { email, password }",
        },
        "errors": "application/problem+json: { type, title, status, detail, code, requestId, errors? } "
                  "(docs/API_ERRORS.md)",
        "pagination": "Lists return { items, nextCursor }; pass ?cursor=<nextCursor>&limit=<n>",
        "count": len(endpoints),
        "endpoints": endpoints,
    }


# ── Postman ──

def postman(doc, idx):
    def request(e):
        segments = [f":{s[1:-1]}" if s.startswith("{") else s for s in e["relativePath"].strip("/").split("/") if s]
        url = {
            "raw": "{{baseUrl}}/" + "/".join(segments),
            "host": ["{{baseUrl}}"],
            "path": segments,
        }
        if e["queryParams"]:
            url["query"] = [{"key": q["name"], "value": "", "disabled": not q["required"]} for q in e["queryParams"]]
            url["raw"] += "?" + "&".join(f"{q['name']}=" for q in e["queryParams"] if q["required"])
        if e["pathParams"]:
            url["variable"] = [{"key": p, "value": ""} for p in e["pathParams"]]
        req = {
            "method": e["method"],
            "header": [{"key": h["name"], "value": "{{$guid}}" if h["name"] == "Idempotency-Key" else "",
                        "disabled": not h["required"]} for h in e["headers"]],
            "url": url,
        }
        if e["public"]:
            req["auth"] = {"type": "noauth"}
        rb = e["requestBody"]
        if rb and rb["contentType"] == "application/json":
            req["header"].append({"key": "Content-Type", "value": "application/json"})
            req["body"] = {"mode": "raw", "raw": json.dumps(rb["example"], indent=2),
                           "options": {"raw": {"language": "json"}}}
        elif rb:
            req["body"] = {"mode": "file", "file": {}}
            req["header"].append({"key": "Content-Type", "value": rb["contentType"]})
        name = f"{e['method']} {e['relativePath']}"
        return {"name": name, "request": req}

    folders = {}
    for e in idx["endpoints"]:
        folders.setdefault(e["group"], []).append(request(e))

    sign_in = {
        "name": "Sign in (Supabase, email + password)",
        "event": [{"listen": "test", "script": {"type": "text/javascript", "exec": [
            "const body = pm.response.json();",
            "if (body.access_token) {",
            "  pm.environment.set('accessToken', body.access_token);",
            "  pm.environment.set('refreshToken', body.refresh_token);",
            "}",
        ]}}],
        "request": {
            "auth": {"type": "noauth"},
            "method": "POST",
            "header": [{"key": "apikey", "value": "{{supabaseAnonKey}}"},
                       {"key": "Content-Type", "value": "application/json"}],
            "body": {"mode": "raw", "raw": '{\n  "email": "{{email}}",\n  "password": "{{password}}"\n}',
                     "options": {"raw": {"language": "json"}}},
            "url": {"raw": "{{supabaseUrl}}/auth/v1/token?grant_type=password", "host": ["{{supabaseUrl}}"],
                    "path": ["auth", "v1", "token"], "query": [{"key": "grant_type", "value": "password"}]},
        },
    }
    items = [{"name": "0 · Get a token (run first)", "item": [sign_in]}]
    items += [{"name": g, "item": reqs} for g, reqs in sorted(folders.items())]
    return {
        "info": {
            "name": "Nichez API",
            "description": "Run '0 · Get a token' first: it stores accessToken in the environment, "
                           "which every other request sends as a Bearer token.",
            "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
        },
        "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{accessToken}}", "type": "string"}]},
        "item": items,
    }


def environment(name, base):
    values = {
        "baseUrl": base + API_PREFIX,
        "supabaseUrl": SUPABASE_URL,
        "supabaseAnonKey": "",
        "email": "",
        "password": "",
        "accessToken": "",
        "refreshToken": "",
    }
    secret = {"password", "accessToken", "refreshToken"}
    return {
        "name": name,
        "values": [{"key": k, "value": v, "type": "secret" if k in secret else "default", "enabled": True}
                   for k, v in values.items()],
    }


# ── YAML (enough for JSON-shaped data) ──

def yaml(node, indent=0):
    pad = "  " * indent
    if isinstance(node, dict):
        if not node:
            return "{}"
        lines = []
        for k, v in node.items():
            key = scalar(str(k))
            if isinstance(v, (dict, list)) and v:
                lines.append(f"{pad}{key}:\n{yaml(v, indent + 1)}")
            else:
                lines.append(f"{pad}{key}: {yaml(v, 0) if isinstance(v, (dict, list)) else scalar(v)}")
        return "\n".join(lines)
    if isinstance(node, list):
        if not node:
            return "[]"
        lines = []
        for v in node:
            if isinstance(v, (dict, list)) and v:
                inner = yaml(v, indent + 1).lstrip()
                lines.append(f"{pad}- {inner}")
            else:
                lines.append(f"{pad}- {yaml(v, 0) if isinstance(v, (dict, list)) else scalar(v)}")
        return "\n".join(lines)
    return scalar(node)


def scalar(v):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(v)
    return json.dumps(v, ensure_ascii=False)  # a JSON string is a valid YAML double-quoted scalar


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")


def main():
    source = OUT / "openapi.json"
    if not source.exists():
        sys.exit(f"{source} is missing: run scripts/export-api-spec.ps1")
    doc = clean(json.loads(source.read_text(encoding="utf-8")))
    idx = index(doc)
    write_json(OUT / "openapi.json", doc)
    (OUT / "openapi.yaml").write_text(yaml(doc) + "\n", encoding="utf-8", newline="\n")
    write_json(OUT / "endpoints.json", idx)
    write_json(OUT / "postman" / "Nichez.postman_collection.json", postman(doc, idx))
    write_json(OUT / "postman" / "Nichez-production.postman_environment.json",
               environment("Nichez · production", PROD_URL))
    write_json(OUT / "postman" / "Nichez-local.postman_environment.json", environment("Nichez · local", LOCAL_URL))
    print(f"{idx['count']} endpoints in {len({e['group'] for e in idx['endpoints']})} groups -> {OUT}")


if __name__ == "__main__":
    main()
