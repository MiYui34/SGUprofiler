from __future__ import annotations

import datetime
import hashlib
import hmac
import json
import os
import re
import secrets
import tempfile
import time
import uuid
from pathlib import Path
from threading import Lock
from urllib.parse import parse_qs, quote_plus, urlencode

from dotenv import load_dotenv
import uvicorn
from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError
from fastapi import Depends, FastAPI, HTTPException, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from slowapi import Limiter
from slowapi.errors import RateLimitExceeded
from slowapi.middleware import SlowAPIMiddleware
from slowapi.util import get_remote_address
from starlette.middleware.sessions import SessionMiddleware
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import ClientDisconnect

BACKEND_ROOT = Path(__file__).resolve().parents[1]
_env_file = BACKEND_ROOT / ".env"
_repo_root_env = BACKEND_ROOT.parent / ".env"
_cwd_env = Path.cwd() / ".env"
# 有人会误把 .env 放在 app/ 目录（与 main.py 同级）
_app_env = Path(__file__).resolve().parent / ".env"


def _apply_env_file(path: Path) -> None:
    """Merge KEY=VALUE lines into os.environ (last writer wins). UTF-8 BOM / 常见编码均可。"""
    if not path.is_file():
        return
    try:
        raw = path.read_text(encoding="utf-8-sig")
    except UnicodeDecodeError:
        try:
            raw = path.read_text(encoding="utf-16-le")
        except (UnicodeDecodeError, OSError):
            return
    except OSError:
        return
    for line in raw.splitlines():
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        if "=" not in s:
            continue
        key, _, value = s.partition("=")
        key = key.strip().lstrip("\ufeff")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        if key:
            os.environ[key] = value


# 后加载的覆盖先加载的；最后一项为 backend/.env，优先级最高
for _dotenv_path in (_repo_root_env, _cwd_env, _app_env, _env_file):
    if _dotenv_path.is_file():
        load_dotenv(_dotenv_path, override=True, encoding="utf-8-sig")
        _apply_env_file(_dotenv_path)

DATA_DIR = Path(os.getenv("DATA_DIR", str(BACKEND_ROOT / "data"))).resolve()
USERS_FILE = DATA_DIR / "users.json"
SNAPSHOTS_DIR = DATA_DIR / "snapshots"
INDEX_FILE = SNAPSHOTS_DIR / "index.json"
SHARES_FILE = DATA_DIR / "share_tokens.json"
PROFILER_ALLOWLIST_FILE = DATA_DIR / "profiler_allowed_uuids.json"
ALLOWLIST_QUERY_SCHEMA = "allowlist_pull_v1"
STATIC_DIR = BACKEND_ROOT / "static"

SESSION_SECRET = os.getenv("SESSION_SECRET")
if SESSION_SECRET is not None:
    SESSION_SECRET = SESSION_SECRET.strip().lstrip("\ufeff\u200b").strip()
if SESSION_SECRET is None or len(SESSION_SECRET) < 16:
    raise RuntimeError(
        "Missing SESSION_SECRET (>=16 chars). "
        "Generate one with: python -c \"import secrets; print(secrets.token_urlsafe(32))\". "
        f"Looked for .env at: {_env_file} (exists={_env_file.is_file()}), "
        f"{_app_env} (exists={_app_env.is_file()}), "
        f"{_cwd_env} (exists={_cwd_env.is_file()}), "
        f"{_repo_root_env} (exists={_repo_root_env.is_file()}). "
        "Put .env next to the backend/ folder (with app/ inside), save as UTF-8, name exactly .env not .env.txt. "
        "Or set SESSION_SECRET in the shell before starting uvicorn."
    )

def normalized_env_sguprof_ingest_secret() -> str:
    raw = os.getenv("SGUPROF_INGEST_SECRET")
    if raw is None:
        return ""
    s = raw.strip().lstrip("\ufeff\u200b").strip()
    if len(s) >= 2 and (
        (s[0] == '"' and s[-1] == '"') or (s[0] == "'" and s[-1] == "'")
    ):
        s = s[1:-1].strip()
    return s


_ing_plain = normalized_env_sguprof_ingest_secret()
if _ing_plain:
    _ingest_b = len(_ing_plain.encode("utf-8"))
    print(
        "SGUProfiler: ingest HMAC key = SGUPROF_INGEST_SECRET "
        f"(chars={len(_ing_plain)} utf8_bytes={_ingest_b} "
        f"ingest_fp16={hashlib.sha256(_ing_plain.encode('utf-8')).hexdigest()[:16]})",
        flush=True,
    )
else:
    print(
        "SGUProfiler: ingest HMAC key = SESSION_SECRET fallback — mod ingestSecret must match SESSION_SECRET, "
        "or set SGUPROF_INGEST_SECRET in backend/.env",
        flush=True,
    )


LOGIN_FLASH_COOKIE = "sgu_login_flash"


def cors_origins() -> list[str]:
    raw = os.getenv("CORS_ORIGINS")
    if raw is None or raw.strip() == "":
        return [
            "http://127.0.0.1:5173",
            "http://localhost:5173",
            "http://127.0.0.1:8787",
            "http://localhost:8787",
        ]
    return [x.strip() for x in raw.split(",") if x.strip()]


def atomic_write_json(path: Path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    data = json.dumps(obj, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    fd, tmp_path = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(data)
        os.replace(tmp_path, path)
    finally:
        try:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)
        except FileNotFoundError:
            pass


def read_json_optional(path: Path, default):
    try:
        with path.open(encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return default


def ensure_data_storage() -> None:
    """启动时确保存在 data/、snapshots/，并初始化空的 JSON 存储文件（从未登录也可见路径）。"""
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    SNAPSHOTS_DIR.mkdir(parents=True, exist_ok=True)
    if not USERS_FILE.is_file():
        atomic_write_json(USERS_FILE, {})
    if not PROFILER_ALLOWLIST_FILE.is_file():
        atomic_write_json(PROFILER_ALLOWLIST_FILE, {"uuids": []})
    print(f"SGUProfiler: DATA_DIR={DATA_DIR}", flush=True)


ensure_data_storage()


_SNAPSHOT_LABEL_SKIP_SCHEMA = frozenset({"deep_entity_v2", "dimension_scan_v1"})
_SNAPSHOT_ID_YYYYMMDD_SEQ = re.compile(r"^\d{8}-\d{1,9}$")


def _normalize_snapshot_id_raw(snapshot_id: str) -> str | None:
    """返回可用于文件名的 id（UUID 规范串或 YYYYMMDD-n），非法则 None。"""
    raw = str(snapshot_id).strip()
    if not raw or len(raw) > 128:
        return None
    for bad in ("..", "/", "\\", "\x00"):
        if bad in raw:
            return None
    safe = "".join(ch for ch in raw if ch.isalnum() or ch == "-")
    if not safe or len(safe) > 128:
        return None
    try:
        return str(uuid.UUID(safe))
    except ValueError:
        pass
    if _SNAPSHOT_ID_YYYYMMDD_SEQ.fullmatch(safe):
        return safe
    return None


def normalize_snapshot_id_ingest(snapshot_id: str) -> str:
    s = _normalize_snapshot_id_raw(snapshot_id)
    if s is None:
        raise HTTPException(status_code=400, detail="bad_snapshot_id")
    return s


def normalize_snapshot_id_lookup(snapshot_id: str) -> str:
    s = _normalize_snapshot_id_raw(snapshot_id)
    if s is None:
        raise HTTPException(status_code=404, detail="not_found")
    return s


def snapshot_display_id_from_index(meta: list, target_id: str) -> str | None:
    """模组 YYYYMMDD-n 与写入的 id 一致；旧 UUID 等仍按「非 YYYYMMDD-n」条目在时间序下按日编号。"""
    if not isinstance(meta, list):
        return None
    tid = str(target_id).strip()
    if not tid:
        return None
    if _SNAPSHOT_ID_YYYYMMDD_SEQ.fullmatch(tid):
        return tid
    trimmed = meta[-500:]
    filtered: list[dict] = []
    for entry in trimmed:
        if not isinstance(entry, dict):
            continue
        sch = entry.get("schema") or ""
        if sch in _SNAPSHOT_LABEL_SKIP_SCHEMA:
            continue
        eid = entry.get("id")
        if eid is None:
            continue
        filtered.append(entry)
    filtered.sort(key=lambda e: int(e.get("createdAtEpochMillis") or 0))
    day_count: dict[str, int] = {}
    for it in filtered:
        iid = str(it.get("id") or "")
        if _SNAPSHOT_ID_YYYYMMDD_SEQ.fullmatch(iid):
            continue
        ts = int(it.get("createdAtEpochMillis") or 0)
        dt = datetime.datetime.fromtimestamp(ts / 1000.0)
        key = dt.strftime("%Y%m%d")
        day_count[key] = day_count.get(key, 0) + 1
        if iid == tid:
            return f"{key}-{day_count[key]}"
    return None


PH = PasswordHasher()
STORE_LOCK = Lock()

app = FastAPI(title="SGUProfiler API", version="0.1.0")

# SlowAPI reads a separate env file using OS default encoding; keep ASCII-only (.env.slowapi).
limiter = Limiter(key_func=get_remote_address, config_filename=str(BACKEND_ROOT / ".env.slowapi"))


@app.exception_handler(RateLimitExceeded)
async def ratelimit_handler(_request: Request, _exc: RateLimitExceeded):
    return Response(status_code=429, content='{"detail":"Too Many Requests"}', media_type="application/json")


app.state.limiter = limiter

app.add_middleware(SlowAPIMiddleware)


class LoginRequiredMiddleware(BaseHTTPMiddleware):
    """未登录用户禁止访问除登录页和公开分享页之外的任何页面。"""

    PUBLIC_PATHS = {"/login.html", "/shared-view.html"}
    PUBLIC_PREFIXES = (
        "/api/v1/auth/",
        "/api/v1/ingest",
        "/api/v1/profiler/allowlist/",
        "/css/",
        "/js/",
        "/favicon",
        "/healthz",
        "/static/",
    )

    async def dispatch(self, request: Request, call_next):
        path = request.url.path or "/"
        if path in self.PUBLIC_PATHS or any(path.startswith(p) for p in self.PUBLIC_PREFIXES):
            return await call_next(request)

        # 保护所有 .html 页面和根路径（须在 SessionMiddleware 之内执行，否则读不到登录会话）
        if path == "/" or path.endswith(".html"):
            session_data = request.scope.get("session") or {}
            username = session_data.get("username")
            if not username:
                next_url = path
                if request.url.query:
                    next_url += "?" + request.url.query
                login_url = f"/login.html?next={quote_plus(next_url)}"
                return RedirectResponse(url=login_url, status_code=303)

        return await call_next(request)


app.add_middleware(LoginRequiredMiddleware)

# 必须在本层之外包裹 Session：请求先经 Session 把 Cookie 读入 scope，内层的 LoginRequired 才能识别已登录
app.add_middleware(
    SessionMiddleware,
    secret_key=SESSION_SECRET,
    session_cookie="sguprof_sess",
    same_site="lax",
    https_only=os.getenv("COOKIE_HTTPS_ONLY", "false").lower() in ("1", "true", "yes"),
    path="/",
)


app.add_middleware(
    CORSMiddleware,
    allow_origins=cors_origins(),
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class SecurityHeadersMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        resp = await call_next(request)
        resp.headers.setdefault("X-Content-Type-Options", "nosniff")
        resp.headers.setdefault("X-Frame-Options", "DENY")
        resp.headers.setdefault("Referrer-Policy", "strict-origin-when-cross-origin")
        return resp


class UiNoCacheMiddleware(BaseHTTPMiddleware):
    """
    禁止浏览器缓存快照/分析等前端资源，避免出现 304 仍用旧 HTML/JS（看不到删除按钮与日序号）。
    体量小，本机场景可接受；若需 CDN 长期缓存可改为读取环境变量开关。
    """

    async def dispatch(self, request: Request, call_next):
        resp = await call_next(request)
        p = request.url.path or ""
        if p.endswith(".html") or p.startswith("/js/") or p.startswith("/css/") or p == "/":
            resp.headers["Cache-Control"] = "no-store, max-age=0, must-revalidate"
            for h in ("ETag", "Last-Modified"):
                if h in resp.headers:
                    del resp.headers[h]
        return resp


app.add_middleware(SecurityHeadersMiddleware)
app.add_middleware(UiNoCacheMiddleware)


def bootstrap_user_if_requested() -> None:
    username = os.getenv("BOOTSTRAP_USER")
    password = os.getenv("BOOTSTRAP_PASS")
    if not username or not password:
        return
    with STORE_LOCK:
        users = read_json_optional(USERS_FILE, {})
        if not isinstance(users, dict):
            users = {}
        if username in users:
            return
        users[str(username)] = {"password_hash_argon2id": PH.hash(password), "admin": True}
        atomic_write_json(USERS_FILE, users)


bootstrap_user_if_requested()


def ingest_secret_bytes() -> bytes:
    plain = normalized_env_sguprof_ingest_secret()
    if plain:
        return plain.encode("utf-8")
    return SESSION_SECRET.strip().encode("utf-8")


def verify_hmac(secret: bytes, timestamp_ms: str, body_bytes: bytes, sig_hex: str) -> None:
    if len(body_bytes) == 0:
        raise HTTPException(status_code=400, detail="empty_body")
    timestamp_ms = timestamp_ms.strip()
    if not timestamp_ms.isdigit():
        raise HTTPException(status_code=400, detail="invalid_timestamp")

    max_skew_ms = int(os.getenv("SIGNATURE_MAX_SKEW_MS", str(10 * 60 * 1000)))
    now_ms = time.time_ns() // 1_000_000
    ts_ms = int(timestamp_ms)
    if abs(now_ms - ts_ms) > max_skew_ms:
        raise HTTPException(status_code=401, detail="timestamp_out_of_window")

    # 与模组一致：HMAC(secret, timestamp_ms + "\n" + body 原始 UTF-8 字节)
    to_sign = timestamp_ms.encode("ascii") + b"\n" + body_bytes
    mac = hmac.new(secret, to_sign, hashlib.sha256).digest()

    malformed_hex = False
    try:
        sig_bytes = bytes.fromhex((sig_hex or "").lower())
    except ValueError:
        malformed_hex = True
        sig_bytes = b""

    ok = (
        not malformed_hex
        and len(sig_bytes) == len(mac)
        and hmac.compare_digest(mac, sig_bytes)
    )

    _ingest_dbg = os.getenv("SGUPROF_INGEST_DEBUG", "").lower() in ("1", "true", "yes")
    if _ingest_dbg and not ok:
        body_hash = hashlib.sha256(body_bytes).hexdigest()
        print(
            f"SGUPROF_INGEST_DEBUG ts={timestamp_ms!r} body_bytes_len={len(body_bytes)} "
            f"body_sha256_prefix={body_hash[:24]} malformed_hex={malformed_hex} "
            f"hmac_expect_prefix={mac.hex()[:24]} sig_prefix={(sig_hex or '')[:24]} "
            f"secret_utf8_bytes={len(secret)}",
            flush=True,
        )

    if malformed_hex:
        raise HTTPException(status_code=401, detail="bad_signature")

    if not ok:
        raise HTTPException(status_code=401, detail="bad_signature")


def require_logged_in(request: Request) -> str:
    username = request.session.get("username")
    if not username:
        raise HTTPException(status_code=401, detail="not_logged_in")
    return str(username)


def user_is_admin(users: dict, username: str) -> bool:
    row = users.get(username)
    return isinstance(row, dict) and bool(row.get("admin"))


def normalize_minecraft_uuid(raw: str) -> str | None:
    """正版 Java UUID，规范为带连字符小写 hex。"""
    s = str(raw).strip()
    if not s:
        return None
    try:
        return str(uuid.UUID(s))
    except ValueError:
        return None


def read_profiler_allowlist() -> list[str]:
    payload = read_json_optional(PROFILER_ALLOWLIST_FILE, {"uuids": []})
    if not isinstance(payload, dict):
        return []
    uuids = payload.get("uuids")
    if not isinstance(uuids, list):
        return []
    out: list[str] = []
    for x in uuids:
        nu = normalize_minecraft_uuid(str(x))
        if nu and nu not in out:
            out.append(nu)
    return sorted(out)


def write_profiler_allowlist(uuids: list[str]) -> None:
    cleaned: list[str] = []
    for x in uuids:
        nu = normalize_minecraft_uuid(str(x))
        if nu and nu not in cleaned:
            cleaned.append(nu)
    atomic_write_json(PROFILER_ALLOWLIST_FILE, {"uuids": sorted(cleaned)})


def require_admin(request: Request) -> str:
    username = require_logged_in(request)
    with STORE_LOCK:
        users = read_json_optional(USERS_FILE, {})
    if not user_is_admin(users, username):
        raise HTTPException(status_code=403, detail="admins_only")
    return username


def login_form_error_redirect(request: Request, next_href: str, err: str) -> RedirectResponse:
    """表单整页 POST 登录失败时不返回 JSON；附绝对 URL + cookie 备选（内嵌浏览器或丢 Query 时用）。"""
    q = urlencode({"next": next_href, "err": err})
    base = str(request.base_url).rstrip("/")
    url = f"{base}/login.html?{q}"
    r = RedirectResponse(url=url, status_code=303)
    r.headers["cache-control"] = "no-store"
    r.set_cookie(
        LOGIN_FLASH_COOKIE,
        err,
        max_age=120,
        path="/",
        samesite="lax",
        httponly=False,
    )
    return r


def safe_next_href(raw: str | None) -> str:
    if not raw or not isinstance(raw, str):
        return "/snapshots.html"
    s = raw.strip()
    if not s.startswith("/") or s.startswith("//"):
        return "/snapshots.html"
    return s


_SHARE_TOKEN_RE = re.compile(r"^[A-Za-z0-9_\-]{16,512}$")


def parse_login_credentials(request: Request, raw: bytes) -> tuple[str, str, str]:
    """username, password, next (relative URL)."""
    if not raw:
        raise HTTPException(status_code=422, detail="empty_body")

    ctype = (request.headers.get("content-type") or "").split(";")[0].strip().lower()
    text = raw.decode("utf-8", errors="replace")

    def from_form(body: str) -> tuple[str, str, str]:
        qs = parse_qs(body, strict_parsing=False, keep_blank_values=True)
        u = "".join(qs.get("username", [""])).strip()
        pw = "".join(qs.get("password", [""]))
        nx = "".join(qs.get("next", ["/snapshots.html"])).strip() or "/snapshots.html"
        return u, pw, safe_next_href(nx)

    if ctype == "application/x-www-form-urlencoded":
        return from_form(text)

    trimmed = text.lstrip()
    as_json = trimmed.startswith("{") or ctype in ("application/json", "text/json")

    if as_json:
        try:
            obj = json.loads(text)
        except json.JSONDecodeError as e:
            raise HTTPException(status_code=400, detail="invalid_json_body") from e
        if not isinstance(obj, dict):
            raise HTTPException(status_code=400, detail="json_must_be_object")
        u = str(obj.get("username", "") or "").strip()
        pw = str(obj.get("password", "") or "")
        nx = safe_next_href(str(obj.get("next", "") or ""))
        return u, pw, nx

    if "=" in text:
        return from_form(text)

    raise HTTPException(status_code=415, detail=f"unsupported_content_type:{ctype or 'unset'}")


def login_body_is_noop_payload(raw: bytes) -> bool:
    """内嵌浏览器可能在 POST 时附带空 JSON 等占位正文，不能当作真实 API 登录体。"""
    if not raw:
        return True
    text = raw.decode("utf-8", errors="replace").strip()
    if not text:
        return True
    return text in ("{}", "[]", "null", "undefined", '""', "''")


class UserCreate(BaseModel):
    username: str = Field(..., min_length=1, max_length=128)
    password: str = Field(..., min_length=6, max_length=4096)


class ProfilerUuidBody(BaseModel):
    uuid: str = Field(..., min_length=32, max_length=40)


@app.get("/healthz")
async def healthz():
    return {"ok": True}


@app.post("/api/v1/auth/login")
@limiter.limit("8/minute")
async def login(request: Request):
    raw = await request.body()
    qp_user = (request.query_params.get("username") or "").strip()
    qp_pass = request.query_params.get("password") or ""
    query_both = bool(qp_user and qp_pass)
    noop_body = login_body_is_noop_payload(raw)
    has_real_body = bool(raw and raw.strip()) and not noop_body

    # 仅当存在「非占位」正文时才走 JSON（fetch）；query 凭证 + 空/{} 等占位正文 → 始终整页 303。
    wants_json_async = request.headers.get("x-sgu-async", "").strip() == "1" and has_real_body

    next_fallback = safe_next_href(request.query_params.get("next"))
    try:
        if query_both and noop_body:
            username_raw = qp_user
            pwd = qp_pass
            next_href = safe_next_href(request.query_params.get("next"))
        elif has_real_body:
            username_raw, pwd, next_href = parse_login_credentials(request, raw)
        else:
            # Cursor 等内嵌环境有时丢失 POST body；允许空正文时从 query 读取（勿用于公网生产）
            allow_q = os.getenv("SGUPROF_ALLOW_QUERY_LOGIN", "true").lower() in ("1", "true", "yes")
            if not allow_q:
                if wants_json_async:
                    raise HTTPException(status_code=422, detail="empty_body")
                return login_form_error_redirect(request, next_fallback, "empty_body")
            username_raw = (request.query_params.get("username") or "").strip()
            pwd = request.query_params.get("password") or ""
            next_href = safe_next_href(request.query_params.get("next"))
    except HTTPException:
        # 解析失败（如空 body）；fetch/JSON API 沿用 JSON 422
        if wants_json_async:
            raise
        return login_form_error_redirect(request, next_fallback, "bad_request")

    if not username_raw:
        if wants_json_async:
            raise HTTPException(status_code=422, detail="empty_username")
        return login_form_error_redirect(request, next_href, "need_user")

    if not pwd:
        if wants_json_async:
            raise HTTPException(status_code=422, detail="empty_password")
        return login_form_error_redirect(request, next_href, "need_pass")

    with STORE_LOCK:
        users = read_json_optional(USERS_FILE, {})

    if not isinstance(users, dict) or username_raw not in users:
        if wants_json_async:
            raise HTTPException(status_code=401, detail="invalid_credentials")
        return login_form_error_redirect(request, next_href, "bad_credentials")

    row = users.get(username_raw)
    if not isinstance(row, dict):
        if wants_json_async:
            raise HTTPException(status_code=401, detail="invalid_credentials")
        return login_form_error_redirect(request, next_href, "bad_credentials")

    hashed = row.get("password_hash_argon2id")
    if not isinstance(hashed, str):
        if wants_json_async:
            raise HTTPException(status_code=401, detail="invalid_credentials")
        return login_form_error_redirect(request, next_href, "bad_credentials")

    try:
        PH.verify(hashed, pwd.encode("utf-8"))
    except VerifyMismatchError:
        if wants_json_async:
            raise HTTPException(status_code=401, detail="invalid_credentials") from None
        return login_form_error_redirect(request, next_href, "bad_credentials")

    request.session["username"] = username_raw

    if wants_json_async:
        jr = JSONResponse(
            {"ok": True, "next": next_href},
            headers={"cache-control": "no-store"},
        )
        jr.delete_cookie(LOGIN_FLASH_COOKIE, path="/")
        return jr

    base = str(request.base_url).rstrip("/")
    dest = next_href if next_href.startswith("/") else f"/{next_href}"
    resp = RedirectResponse(url=f"{base}{dest}", status_code=303)
    resp.headers["cache-control"] = "no-store"
    resp.delete_cookie(LOGIN_FLASH_COOKIE, path="/")
    return resp


@app.post("/api/v1/auth/logout")
async def logout(request: Request, response: Response):
    response.headers["cache-control"] = "no-store"
    request.session.clear()
    return {"ok": True}


@app.get("/api/v1/session")
async def session(request: Request):
    username = request.session.get("username")
    if username is None:
        return {"logged_in": False}
    return {"logged_in": True, "username": str(username)}


@app.post("/api/v1/users")
async def create_user(payload: UserCreate, session_user: str = Depends(require_logged_in)):
    with STORE_LOCK:
        users = read_json_optional(USERS_FILE, {})
        if not isinstance(users, dict):
            users = {}

        if not user_is_admin(users, session_user):
            raise HTTPException(status_code=403, detail="admins_only")

        if payload.username in users:
            raise HTTPException(status_code=409, detail="already_exists")

        users[payload.username] = {
            "password_hash_argon2id": PH.hash(payload.password),
            "admin": False,
        }
        atomic_write_json(USERS_FILE, users)

    return {"ok": True}


@app.post("/api/v1/users/admin")
async def create_admin_user(payload: UserCreate, session_user: str = Depends(require_logged_in)):
    with STORE_LOCK:
        users = read_json_optional(USERS_FILE, {})
        if not isinstance(users, dict):
            users = {}

        if not user_is_admin(users, session_user):
            raise HTTPException(status_code=403, detail="admins_only")

        if payload.username in users:
            raise HTTPException(status_code=409, detail="already_exists")

        users[payload.username] = {
            "password_hash_argon2id": PH.hash(payload.password),
            "admin": True,
        }
        atomic_write_json(USERS_FILE, users)

    return {"ok": True}


@app.get("/api/v1/snapshots")
async def list_snapshots(_username: str = Depends(require_logged_in)):
    SNAPSHOTS_DIR.mkdir(parents=True, exist_ok=True)

    meta = read_json_optional(INDEX_FILE, [])
    if not isinstance(meta, list):
        meta = []

    trimmed = meta[-500:]
    return {"items": list(reversed(trimmed))}


@app.get("/api/v1/snapshots/{snapshot_id}")
async def get_snapshot(snapshot_id: str, _username: str = Depends(require_logged_in)):
    safe_id = normalize_snapshot_id_lookup(snapshot_id)

    path = SNAPSHOTS_DIR / f"{safe_id}.json"
    if not path.exists():
        raise HTTPException(status_code=404, detail="not_found")

    with path.open(encoding="utf-8") as f:
        return json.load(f)


@app.delete("/api/v1/snapshots/{snapshot_id}")
@limiter.limit("30/minute")
async def delete_snapshot(request: Request, snapshot_id: str, _username: str = Depends(require_logged_in)):
    safe_id = normalize_snapshot_id_lookup(snapshot_id)
    path = SNAPSHOTS_DIR / f"{safe_id}.json"

    with STORE_LOCK:
        idx = read_json_optional(INDEX_FILE, [])
        if not isinstance(idx, list):
            idx = []
        had_index = any(isinstance(e, dict) and str(e.get("id")) == safe_id for e in idx)
        file_exists = path.is_file()
        if not had_index and not file_exists:
            raise HTTPException(status_code=404, detail="not_found")

        if file_exists:
            try:
                path.unlink()
            except OSError as e:
                raise HTTPException(status_code=500, detail="delete_failed") from e

        new_idx = [e for e in idx if not (isinstance(e, dict) and str(e.get("id")) == safe_id)]
        if len(new_idx) != len(idx):
            atomic_write_json(INDEX_FILE, new_idx[-4000:] if len(new_idx) > 4000 else new_idx)

        shares = read_json_optional(SHARES_FILE, {})
        if isinstance(shares, dict):
            to_del = [
                tok
                for tok, row in shares.items()
                if isinstance(row, dict) and str(row.get("snapshot_id")) == safe_id
            ]
            if to_del:
                for tok in to_del:
                    del shares[tok]
                atomic_write_json(SHARES_FILE, shares)

    return {"ok": True}


@app.post("/api/v1/profiler/allowlist/query")
@limiter.limit("120/minute")
async def profiler_allowlist_query(request: Request):
    """模组使用与 ingest 相同的 HMAC 密钥拉取正版 UUID 白名单。"""
    timestamp_ms = (request.headers.get("X-SGU-Timestamp-Ms") or "").strip()
    sig_hex = (request.headers.get("X-SGU-Signature") or "").strip()
    try:
        raw_bytes = await request.body()
    except ClientDisconnect:
        return Response(status_code=204)
    if len(raw_bytes) == 0:
        raise HTTPException(status_code=400, detail="empty_body")
    try:
        verify_hmac(ingest_secret_bytes(), timestamp_ms, raw_bytes, sig_hex)
    except HTTPException as exc:
        if exc.status_code in (401, 400):
            sec = ingest_secret_bytes()
            fp16 = hashlib.sha256(sec).hexdigest()[:16]
            print(
                f"SGUProfiler: allowlist/query verify failed detail={exc.detail} ts_len={len(timestamp_ms)} "
                f"body_len={len(raw_bytes)} server_ingest_utf8_bytes={len(sec)} server_ingest_fp16={fp16}",
                flush=True,
            )
            if exc.detail == "bad_signature":
                print(
                    "SGUProfiler: 模组 config/sguprofiler.json 的 ingestSecret（或 JVM/环境变量）必须与后端 "
                    "SGUPROF_INGEST_SECRET 完全一致；未配置 ingest 时后端使用 SESSION_SECRET，模组仍为 devsecret。",
                    flush=True,
                )
        raise
    try:
        payload = json.loads(raw_bytes.decode("utf-8"))
    except json.JSONDecodeError as e:
        raise HTTPException(status_code=400, detail="invalid_json") from e
    if not isinstance(payload, dict) or str(payload.get("schema") or "") != ALLOWLIST_QUERY_SCHEMA:
        raise HTTPException(status_code=400, detail="bad_schema")
    with STORE_LOCK:
        uuids = read_profiler_allowlist()
    return {"schema": "profiler_allowed_uuids_v1", "uuids": uuids}


@app.get("/api/v1/admin/profiler-allowlist")
@limiter.limit("60/minute")
async def admin_profiler_allowlist_list(request: Request, _: str = Depends(require_admin)):
    with STORE_LOCK:
        uuids = read_profiler_allowlist()
    return {"uuids": uuids}


@app.post("/api/v1/admin/profiler-allowlist")
@limiter.limit("60/minute")
async def admin_profiler_allowlist_add(request: Request, payload: ProfilerUuidBody, _: str = Depends(require_admin)):
    nu = normalize_minecraft_uuid(payload.uuid)
    if not nu:
        raise HTTPException(status_code=400, detail="invalid_uuid")
    with STORE_LOCK:
        cur = read_profiler_allowlist()
        if nu not in cur:
            cur.append(nu)
        write_profiler_allowlist(cur)
        out = read_profiler_allowlist()
    return {"ok": True, "uuids": out}


@app.delete("/api/v1/admin/profiler-allowlist/{uuid_raw}")
@limiter.limit("60/minute")
async def admin_profiler_allowlist_delete(request: Request, uuid_raw: str, _: str = Depends(require_admin)):
    nu = normalize_minecraft_uuid(uuid_raw)
    if not nu:
        raise HTTPException(status_code=400, detail="invalid_uuid")
    with STORE_LOCK:
        cur = [x for x in read_profiler_allowlist() if x != nu]
        write_profiler_allowlist(cur)
        out = read_profiler_allowlist()
    return {"ok": True, "uuids": out}


@app.post("/api/v1/ingest")
async def ingest(request: Request):
    timestamp_ms = (request.headers.get("X-SGU-Timestamp-Ms") or "").strip()
    sig_hex = (request.headers.get("X-SGU-Signature") or "").strip()

    try:
        raw_bytes = await request.body()
    except ClientDisconnect:
        # 客户端（如游戏内 HttpClient）在 body 未读完前断开；避免当作未处理异常刷 ERROR
        print("SGUProfiler: ingest client_disconnect (incomplete body)", flush=True)
        return Response(status_code=204)

    if len(raw_bytes) == 0:
        print("SGUProfiler: ingest empty_body (check mod client / Expect 100-continue)", flush=True)
        raise HTTPException(status_code=400, detail="empty_body")

    try:
        verify_hmac(ingest_secret_bytes(), timestamp_ms, raw_bytes, sig_hex)
    except HTTPException as exc:
        if exc.status_code in (401, 400):
            print(f"SGUProfiler: ingest verify failed ({exc.detail})", flush=True)
        raise

    SNAPSHOTS_DIR.mkdir(parents=True, exist_ok=True)

    try:
        payload = json.loads(raw_bytes.decode("utf-8"))
    except json.JSONDecodeError as e:
        print(f"SGUProfiler: ingest invalid_json ({e})", flush=True)
        raise HTTPException(status_code=400, detail="invalid_json") from e

    snapshot_id = payload.get("snapshotId")
    if snapshot_id is None:
        print("SGUProfiler: ingest missing_snapshot_id keys=" + repr(list(payload.keys()))[:240], flush=True)
        raise HTTPException(status_code=400, detail="missing_snapshot_id")
    snapshot_id = str(snapshot_id)

    safe_id = normalize_snapshot_id_ingest(snapshot_id)

    snap_path = SNAPSHOTS_DIR / f"{safe_id}.json"

    index_entry = {"id": safe_id, "schema": payload.get("schema"), "createdAtEpochMillis": payload.get("createdAtEpochMillis")}


    with STORE_LOCK:
        if snap_path.exists():
            return {"ok": True}
        atomic_write_json(snap_path, payload)

        idx = read_json_optional(INDEX_FILE, [])
        if not isinstance(idx, list):
            idx = []
        idx.append(index_entry)
        atomic_write_json(INDEX_FILE, idx[-4000:] if len(idx) > 4000 else idx)

    return {"ok": True}


@app.post("/api/v1/snapshots/{snapshot_id}/share")
@limiter.limit("12/minute")
async def create_snapshot_share(request: Request, snapshot_id: str, _username: str = Depends(require_logged_in)):
    safe_id = normalize_snapshot_id_lookup(snapshot_id)

    path = SNAPSHOTS_DIR / f"{safe_id}.json"
    if not path.is_file():
        raise HTTPException(status_code=404, detail="not_found")

    token = secrets.token_urlsafe(32)
    entry = {"snapshot_id": safe_id, "created_at_ms": int(time.time() * 1000)}

    with STORE_LOCK:
        data = read_json_optional(SHARES_FILE, {})
        if not isinstance(data, dict):
            data = {}
        data[token] = entry
        atomic_write_json(SHARES_FILE, data)

    base = str(request.base_url).rstrip("/")
    share_url = f"{base}/shared-view.html?token={token}"
    return {"ok": True, "token": token, "shareUrl": share_url, "snapshotId": safe_id}


@app.get("/api/v1/public/share/{token}")
@limiter.limit("120/minute")
async def public_shared_snapshot(request: Request, token: str):
    if not _SHARE_TOKEN_RE.match(token):
        raise HTTPException(status_code=404, detail="not_found")

    with STORE_LOCK:
        data = read_json_optional(SHARES_FILE, {})
    if not isinstance(data, dict):
        raise HTTPException(status_code=404, detail="not_found")

    row = data.get(token)
    if not isinstance(row, dict):
        raise HTTPException(status_code=404, detail="not_found")

    sid = row.get("snapshot_id")
    if not isinstance(sid, str):
        raise HTTPException(status_code=404, detail="not_found")

    storage_id = normalize_snapshot_id_lookup(sid)

    path = SNAPSHOTS_DIR / f"{storage_id}.json"
    if not path.is_file():
        raise HTTPException(status_code=404, detail="not_found")

    with path.open(encoding="utf-8") as f:
        payload = json.load(f)

    if not isinstance(payload, dict):
        raise HTTPException(status_code=500, detail="bad_snapshot_file")

    meta = read_json_optional(INDEX_FILE, [])
    disp = snapshot_display_id_from_index(meta if isinstance(meta, list) else [], storage_id)
    out = dict(payload)
    if disp:
        out["snapshotDisplayId"] = disp
    return out


if STATIC_DIR.exists():
    app.mount("/", StaticFiles(directory=str(STATIC_DIR), html=True), name="ui")


if __name__ == "__main__":
    uvicorn.run(
        "app.main:app",
        host=os.getenv("HOST", "127.0.0.1"),
        port=int(os.getenv("PORT", "8787")),
        reload=False,
    )
