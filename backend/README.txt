SGUProfiler backend

Notes:

- Users & snapshots are stored under backend/data/ by default (resolved from this package, not shell CWD),
  so uvicorn can be started from repo root or backend/ and still find the same users.json.

- Optional ASCII-only `.env.slowapi` exists so SlowAPI (rate limit library) never reads `.env`
  with GBK encoding on Chinese Windows.

Environment variables (recommended: put them in backend/.env — loaded automatically if that file exists):

- SESSION_SECRET: long random string (required).
- SGUPROF_INGEST_SECRET: HMAC key for /api/v1/ingest; must match the Minecraft ingest secret (same UTF-8 bytes — mod log prints "utf8_bytes"). Recommended on the MC server: set "ingestSecret" in Fabric config folder file config/sguprofiler.json (mod creates an empty template on first run); optional overrides — env SGUPROF_INGEST_SECRET or JVM -Dsguprof.ingestSecret=....
- CORS_ORIGINS: comma-separated list, e.g. https://dash.example.com,http://127.0.0.1:5173

If backend/.env exists, its values override same-named variables already set in the shell (so the file is the single source of truth for local dev).

- SGUPROF_ALLOW_QUERY_LOGIN: default true. When true, empty POST body on /api/v1/auth/login falls back to
  reading username/password/next from the URL query (for broken embedded browsers). Set to false on public deployments.

Optional bootstrap (first run):

- BOOTSTRAP_USER / BOOTSTRAP_PASS: creates a hashed user in ./data/users.json

Run locally (from the backend folder):

cd backend

python -m venv .venv
.venv\\Scripts\\activate
pip install -r requirements.txt
copy .env.example .env
  (optional) edit .env — or use the generated .env if present; never commit .env.
python -m uvicorn app.main:app --reload --host 127.0.0.1 --port 8787

If you still set variables in the shell instead of .env, they override .env for that session.

Ingest troubleshoot:

- Minecraft server log prints utf8_bytes when the mod resolves the secret — it must equal uvicorn startup "utf8_bytes" for SGUPROF_INGEST_SECRET.
- If ingest returns 401 bad_signature, restart backend with SGUPROF_INGEST_DEBUG=1 for one line of HMAC diagnostics (never leave on in production).
