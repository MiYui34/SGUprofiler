/**
 * Login page — 见 login.html。
 * 使用 JSON + fetch 登录，避免整页 POST 挂起；表单立即绑定，不阻塞在 whenReady 上。
 */
(function () {
    "use strict";

    const FLASH_COOKIE = "sgu_login_flash";
    const LOGIN_TIMEOUT_MS = 20000;

    const ERR_MSG = {
        bad_credentials: "用户名或密码错误。",
        need_user: "请填写用户名。",
        need_pass: "请填写密码。",
        empty_body: "未收到登录数据，请重试或确认已启用 JavaScript。",
        bad_request: "请求异常，请重试。",
        invalid_credentials: "用户名或密码错误。",
        request_timeout: "连接服务器超时，请检查后端是否在运行后重试。",
    };

    function safeNext(raw) {
        if (!raw || typeof raw !== "string") return "/snapshots.html";
        if (!raw.startsWith("/") || raw.startsWith("//")) return "/snapshots.html";
        return raw;
    }

    function readFlashCookie() {
        const re = new RegExp("(?:^|;\\s*)" + FLASH_COOKIE + "=([^;]*)");
        const m = document.cookie.match(re);
        return m ? decodeURIComponent(m[1].trim()) : "";
    }

    function clearFlashCookie() {
        document.cookie = FLASH_COOKIE + "=; Path=/; Max-Age=0; SameSite=Lax";
    }

    function showLoginFlash(text) {
        const wrap = document.getElementById("loginFlash");
        const span = document.getElementById("loginFlashText");
        const SGU = window.SGU;
        if (SGU && typeof SGU.alertModal === "function") {
            SGU.alertModal(text, { title: "提示" });
            return;
        }
        if (!wrap || !span) {
            console.error("[login]", text);
            document.body.insertAdjacentHTML(
                "afterbegin",
                '<p role="alert" style="margin:12px;padding:14px;background:#ffd4d4;border:1px solid #c03832;border-radius:10px;font:14px/system-ui,sans-serif">' +
                    text.replace(/</g, "&lt;") +
                    "</p>",
            );
            return;
        }
        span.textContent = text;
        wrap.classList.remove("hidden");
        wrap.setAttribute("aria-hidden", "false");
    }

    /** 防止「已登录 → replace」与「登录成功 → assign」两次导航叠加重载 */
    let navigationLocked = false;

    function navigateOnce(dest) {
        const url = safeNext(dest);
        if (navigationLocked) return;
        navigationLocked = true;
        window.location.replace(url);
    }

    async function loginWithFetch(username, password, nextHref) {
        const ctrl = new AbortController();
        const timer = setTimeout(() => ctrl.abort(), LOGIN_TIMEOUT_MS);
        try {
            const res = await fetch("/api/v1/auth/login", {
                method: "POST",
                credentials: "include",
                headers: {
                    "Content-Type": "application/json; charset=UTF-8",
                    "X-SGU-Async": "1",
                },
                signal: ctrl.signal,
                body: JSON.stringify({
                    username,
                    password,
                    next: nextHref,
                }),
            });
            const text = await res.text();
            let body = null;
            try {
                body = text ? JSON.parse(text) : null;
            } catch (_) {
                body = { raw: text };
            }
            if (!res.ok) {
                const detail = body && body.detail;
                const code = typeof detail === "string" ? detail : "login_failed";
                const err = new Error(code);
                err.status = res.status;
                throw err;
            }
            const next = body && body.next ? String(body.next) : nextHref;
            return safeNext(next);
        } finally {
            clearTimeout(timer);
        }
    }

    function bindLoginForm() {
        const form = document.getElementById("loginForm");
        const elUser = document.getElementById("user");
        const elPass = document.getElementById("pass");
        const elNext = document.getElementById("loginNext");
        const submitBtn = document.getElementById("loginSubmitBtn");

        if (!form) return;

        function onSubmit(ev) {
            ev.preventDefault();
            ev.stopPropagation();
            document.getElementById("loginFlash")?.classList.add("hidden");

            const username = (elUser && elUser.value && elUser.value.trim()) || "";
            const password = (elPass && elPass.value) || "";
            if (!username) {
                showLoginFlash("请填写用户名。");
                return;
            }
            if (!password) {
                showLoginFlash("请填写密码。");
                return;
            }

            window.__sguUserLoginInFlight = true;
            const nextHref = safeNext((elNext && elNext.value) || "/snapshots.html");

            if (submitBtn) {
                submitBtn.disabled = true;
                submitBtn.dataset._label = submitBtn.textContent || "";
                submitBtn.textContent = "登录中…";
            }

            void loginWithFetch(username, password, nextHref)
                .then((dest) => {
                    navigateOnce(dest);
                })
                .catch((err) => {
                    window.__sguUserLoginInFlight = false;
                    const code = err && err.message ? String(err.message) : "login_failed";
                    if (err && err.name === "AbortError") {
                        showLoginFlash(ERR_MSG.request_timeout);
                    } else {
                        showLoginFlash(ERR_MSG[code] || "登录失败，请重试。");
                    }
                    if (submitBtn) {
                        submitBtn.disabled = false;
                        if (submitBtn.dataset._label) submitBtn.textContent = submitBtn.dataset._label;
                    }
                });
        }

        /* 捕获阶段尽早拦截，避免未 preventDefault 时浏览器按 action 整页提交导致「自己刷新」 */
        form.addEventListener("submit", onSubmit, true);
    }

    async function runDeferredSetup() {
        const SGU = window.SGU;
        if (SGU && SGU.whenReady) {
            try {
                await SGU.whenReady;
            } catch (_) {
                /* ignore */
            }
        }

        const params = new URLSearchParams(window.location.search);
        let errCode = (params.get("err") || "").trim();
        let fromCookie = readFlashCookie().trim();
        clearFlashCookie();

        const codeOk = (c) => /^[a-z_]{1,48}$/.test(c);
        if (errCode && !codeOk(errCode)) errCode = "bad_request";
        if (fromCookie && !codeOk(fromCookie)) fromCookie = "";

        if (!errCode && fromCookie) errCode = fromCookie;

        if (errCode) {
            showLoginFlash(ERR_MSG[errCode] || "登录失败（" + errCode + "），请重试。");
            params.delete("err");
            const q = params.toString();
            const path = window.location.pathname;
            window.history.replaceState({}, "", path + (q ? "?" + q : ""));
        }

        document.getElementById("loginFlashDismiss")?.addEventListener("click", () => {
            const wrap = document.getElementById("loginFlash");
            wrap?.classList.add("hidden");
            wrap?.setAttribute("aria-hidden", "true");
        });

        const next = safeNext(params.get("next"));
        const elNext = document.getElementById("loginNext");
        if (elNext) elNext.value = next;

        const SGU2 = window.SGU;
        if (SGU2 && typeof SGU2.session === "function") {
            try {
                const s = await SGU2.session();
                if (s && s.logged_in && !window.__sguUserLoginInFlight) navigateOnce(next);
            } catch (_) {
                /* ignore */
            }
        }

        requestAnimationFrame(() => {
            document.getElementById("user")?.focus();
        });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", () => {
            bindLoginForm();
            void runDeferredSetup();
        });
    } else {
        bindLoginForm();
        void runDeferredSetup();
    }
})();
