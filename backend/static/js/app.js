/** @global SGUProfiler static shell */
(function () {
    "use strict";

    let resolveShellReady = () => {};

    const whenShellReady = new Promise((r) => {
        resolveShellReady = r;
    });

    const FETCH_TIMEOUT_MS = 20000;

    /** @returns {unknown} */
    async function api(path, opts = {}) {
        const { headers: hdrOverride = {}, signal: outerSignal, ...rest } = opts;
        const extras =
            hdrOverride && typeof hdrOverride === "object" ? { ...hdrOverride } : {};
        const hdrs = { "X-SGU-Async": "1", ...extras };
        if (!Object.keys(hdrs).some((k) => k.toLowerCase() === "content-type")) {
            hdrs["Content-Type"] = "application/json; charset=UTF-8";
        }

        const ctrl = new AbortController();
        const t = setTimeout(() => ctrl.abort(), FETCH_TIMEOUT_MS);
        let mergedSignal = ctrl.signal;
        if (outerSignal) {
            if (outerSignal.aborted) ctrl.abort();
            else outerSignal.addEventListener("abort", () => ctrl.abort(), { once: true });
        }

        let res;
        try {
            res = await fetch(path, {
                credentials: "include",
                headers: hdrs,
                signal: mergedSignal,
                ...rest,
            });
        } catch (e) {
            clearTimeout(t);
            if (e && e.name === "AbortError") {
                const err = new Error("request_timeout");
                err.status = 0;
                throw err;
            }
            throw e;
        }
        clearTimeout(t);

        let bodyText = "";
        try {
            bodyText = await res.text();
        } catch (_) {
            bodyText = "";
        }

        let bodyObj = null;
        try {
            bodyObj = bodyText ? JSON.parse(bodyText) : null;
        } catch (_) {
            bodyObj = { raw: bodyText };
        }

        if (!res.ok) {
            const detailRaw = bodyObj && bodyObj.detail;
            let detailStr;
            if (typeof detailRaw === "string") detailStr = detailRaw;
            else if (Array.isArray(detailRaw)) {
                detailStr = detailRaw
                    .map((e) =>
                        e && typeof e === "object" && "msg" in e ? String(e.msg) : JSON.stringify(e),
                    )
                    .join("; ");
            } else if (detailRaw && typeof detailRaw === "object") {
                detailStr = JSON.stringify(detailRaw);
            } else detailStr = res.status + " " + bodyText.slice(0, 200);

            const err = new Error(detailStr);
            err.status = res.status;
            err.payload = bodyObj;
            throw err;
        }
        return bodyObj;
    }

    /** @returns {boolean} */
    function safeNextHref(raw) {
        if (!raw || typeof raw !== "string") return false;
        if (!raw.startsWith("/")) return false;
        if (raw.startsWith("//")) return false;
        return true;
    }

    /** @returns {Promise<{logged_in: boolean, username?: string}>} */
    async function session() {
        return api("/api/v1/session", { method: "GET" });
    }

    function isLoginPublicPath(pathname) {
        const p = pathname || "";
        return p === "/login.html" || p === "/shared-view.html";
    }

    async function bootstrapShell() {
        const pathname = window.location.pathname;
        const loginPublic = isLoginPublicPath(pathname);
        /** @type {{ logged_in: boolean, username?: string }} */
        let s = { logged_in: false };
        try {
            s = await session();
        } catch (_) {
            s = { logged_in: false };
        }

        const badge = document.getElementById("sessionBadge");
        const btn = document.getElementById("navLogoutBtn");

        if (!loginPublic && !s.logged_in) {
            const next = (pathname || "/") + window.location.search;
            window.location.replace("/login.html?next=" + encodeURIComponent(next));
            return;
        }

        if (btn) btn.classList.toggle("hidden", !s.logged_in);
        if (badge) {
            if (s.logged_in) {
                badge.textContent = `已登录：${s.username}`;
                badge.classList.remove("hidden");
            } else {
                badge.textContent = "";
                badge.classList.add("hidden");
            }
        }
    }

    async function refreshNavSession() {
        await bootstrapShell();
    }

    async function navLogout() {
        try {
            await api("/api/v1/auth/logout", { method: "POST" });
        } catch (_) {
            /* ignore */
        }
        window.location.href = "/login.html";
    }

    function markActiveNav() {
        const cur = window.location.pathname;
        try {
            document.querySelectorAll("[data-nav]").forEach((a) => {
                const raw = a.getAttribute("href") || "";
                const absPath = new URL(raw, window.location.origin).pathname;
                a.classList.toggle("is-active", cur === absPath);
            });
        } catch (_) {
            /* ignore malformed href */
        }
    }

    function ensureToastHost() {
        let host = document.getElementById("sgu-toast-host");
        if (!host) {
            host = document.createElement("div");
            host.id = "sgu-toast-host";
            host.className = "sgu-toast-host";
            host.setAttribute("aria-live", "polite");
            document.body.appendChild(host);
        }
        return host;
    }

    /**
     * 页内轻提示（替代 alert）
     * @param {string} message
     * @param {{ variant?: 'info'|'success'|'error', duration?: number }} [opts]
     */
    function toast(message, opts) {
        const o = opts || {};
        const variant = o.variant === "success" || o.variant === "error" ? o.variant : "info";
        const duration = typeof o.duration === "number" ? o.duration : 4800;
        const host = ensureToastHost();
        const el = document.createElement("div");
        el.className = "sgu-toast sgu-toast--" + variant;
        el.setAttribute("role", variant === "error" ? "alert" : "status");
        el.textContent = String(message || "");
        host.appendChild(el);
        requestAnimationFrame(() => el.classList.add("sgu-toast--show"));
        window.setTimeout(() => {
            el.classList.remove("sgu-toast--show");
            window.setTimeout(() => el.remove(), 280);
        }, duration);
    }

    /**
     * 页内对话框展示分享链接（替代 window.prompt）
     * @param {string} url
     */
    function showShareUrlModal(url) {
        const u = String(url || "");
        const backdrop = document.createElement("div");
        backdrop.className = "sgu-modal-backdrop";
        backdrop.setAttribute("role", "dialog");
        backdrop.setAttribute("aria-modal", "true");
        backdrop.setAttribute("aria-labelledby", "sgu-share-title");

        const box = document.createElement("div");
        box.className = "sgu-modal-box";

        const title = document.createElement("h3");
        title.id = "sgu-share-title";
        title.className = "sgu-modal-title";
        title.textContent = "只读分享链接";

        const hint = document.createElement("p");
        hint.className = "muted small";
        hint.style.margin = "0 0 12px";
        hint.textContent = "已尝试复制到剪贴板。可复制下方地址或再次点击「复制」。";

        const input = document.createElement("input");
        input.type = "text";
        input.className = "sgu-modal-input";
        input.readOnly = true;
        input.value = u;

        const actions = document.createElement("div");
        actions.className = "sgu-modal-actions";

        const btnCopy = document.createElement("button");
        btnCopy.type = "button";
        btnCopy.className = "primary";
        btnCopy.textContent = "复制";

        const btnClose = document.createElement("button");
        btnClose.type = "button";
        btnClose.className = "sgu-modal-close";
        btnClose.textContent = "关闭";

        actions.appendChild(btnCopy);
        actions.appendChild(btnClose);

        box.appendChild(title);
        box.appendChild(hint);
        box.appendChild(input);
        box.appendChild(actions);
        backdrop.appendChild(box);
        document.body.appendChild(backdrop);

        function close() {
            document.removeEventListener("keydown", onKey);
            backdrop.classList.remove("sgu-modal-backdrop--show");
            window.setTimeout(() => backdrop.remove(), 220);
        }

        function onKey(ev) {
            if (ev.key === "Escape") close();
        }

        document.addEventListener("keydown", onKey);
        backdrop.addEventListener("click", (ev) => {
            if (ev.target === backdrop) close();
        });
        btnClose.addEventListener("click", close);
        btnCopy.addEventListener("click", async () => {
            try {
                await navigator.clipboard.writeText(u);
                toast("已复制到剪贴板", { variant: "success", duration: 2000 });
            } catch (_) {
                input.focus();
                input.select();
                toast("请手动复制文本框中的链接", { variant: "info", duration: 3500 });
            }
        });

        requestAnimationFrame(() => {
            backdrop.classList.add("sgu-modal-backdrop--show");
            input.focus();
            input.select();
        });
    }

    let __sguAlertModalSeq = 0;

    /**
     * 页内提示框（替代 window.alert），居中、单按钮「确定」。
     * @param {string} message
     * @param {{ title?: string }} [opts]
     */
    function alertModal(message, opts) {
        const o = opts || {};
        const titleText = o.title || "提示";
        const text = String(message || "");
        const titleId = "sgu-alert-title-" + String(++__sguAlertModalSeq);
        const backdrop = document.createElement("div");
        backdrop.className = "sgu-modal-backdrop";
        backdrop.setAttribute("role", "alertdialog");
        backdrop.setAttribute("aria-modal", "true");
        backdrop.setAttribute("aria-labelledby", titleId);

        const box = document.createElement("div");
        box.className = "sgu-modal-box";

        const title = document.createElement("h3");
        title.id = titleId;
        title.className = "sgu-modal-title";
        title.textContent = titleText;

        const body = document.createElement("p");
        body.className = "sgu-modal-body";
        body.textContent = text;

        const actions = document.createElement("div");
        actions.className = "sgu-modal-actions";
        const ok = document.createElement("button");
        ok.type = "button";
        ok.className = "primary";
        ok.textContent = "确定";
        actions.appendChild(ok);

        box.appendChild(title);
        box.appendChild(body);
        box.appendChild(actions);
        backdrop.appendChild(box);
        document.body.appendChild(backdrop);

        function close() {
            document.removeEventListener("keydown", onKey);
            backdrop.classList.remove("sgu-modal-backdrop--show");
            window.setTimeout(() => backdrop.remove(), 220);
        }

        function onKey(ev) {
            if (ev.key === "Escape" || ev.key === "Enter") close();
        }

        document.addEventListener("keydown", onKey);
        backdrop.addEventListener("click", (ev) => {
            if (ev.target === backdrop) close();
        });
        ok.addEventListener("click", close);

        requestAnimationFrame(() => {
            backdrop.classList.add("sgu-modal-backdrop--show");
            ok.focus();
        });
    }

    let __sguConfirmModalSeq = 0;

    /**
     * 双按钮确认框；点遮罩或 Esc 视为取消。
     * @param {string} message
     * @param {{ title?: string, okLabel?: string, cancelLabel?: string, dangerous?: boolean }} [opts]
     * @returns {Promise<boolean>} true 表示用户点击确认
     */
    function confirmModal(message, opts) {
        const o = opts || {};
        const titleText = o.title || "请确认";
        const okLabel = o.okLabel || "确定";
        const cancelLabel = o.cancelLabel || "取消";
        const text = String(message || "");
        return new Promise((resolve) => {
            const titleId = "sgu-confirm-title-" + String(++__sguConfirmModalSeq);
            let finished = false;
            function finish(val) {
                if (finished) return;
                finished = true;
                close();
                resolve(val);
            }
            const backdrop = document.createElement("div");
            backdrop.className = "sgu-modal-backdrop";
            backdrop.setAttribute("role", "dialog");
            backdrop.setAttribute("aria-modal", "true");
            backdrop.setAttribute("aria-labelledby", titleId);

            const box = document.createElement("div");
            box.className = "sgu-modal-box";

            const title = document.createElement("h3");
            title.id = titleId;
            title.className = "sgu-modal-title";
            title.textContent = titleText;

            const body = document.createElement("p");
            body.className = "sgu-modal-body";
            body.textContent = text;

            const actions = document.createElement("div");
            actions.className = "sgu-modal-actions";
            const cancel = document.createElement("button");
            cancel.type = "button";
            cancel.className = "sgu-modal-cancel";
            cancel.textContent = cancelLabel;
            const ok = document.createElement("button");
            ok.type = "button";
            ok.className = o.dangerous ? "primary sgu-btn-danger" : "primary";
            ok.textContent = okLabel;
            actions.appendChild(cancel);
            actions.appendChild(ok);

            box.appendChild(title);
            box.appendChild(body);
            box.appendChild(actions);
            backdrop.appendChild(box);
            document.body.appendChild(backdrop);

            function close() {
                document.removeEventListener("keydown", onKey);
                backdrop.classList.remove("sgu-modal-backdrop--show");
                window.setTimeout(() => backdrop.remove(), 220);
            }

            function onKey(ev) {
                if (ev.key === "Escape") finish(false);
            }

            document.addEventListener("keydown", onKey);
            backdrop.addEventListener("click", (ev) => {
                if (ev.target === backdrop) finish(false);
            });
            cancel.addEventListener("click", () => finish(false));
            ok.addEventListener("click", () => finish(true));

            requestAnimationFrame(() => {
                backdrop.classList.add("sgu-modal-backdrop--show");
                cancel.focus();
            });
        });
    }

    /**
     * 为快照列表生成可读展示名：模组/服务端写入的 YYYYMMDD-n 原样展示；
     * 仅对旧版 UUID 等 id 按本地日期的 YYYYMMDD + 当天序号（仅统计非 YYYYMMDD-n 条，按时间升序）生成标签。
     * @param {Array<{ id?: string, createdAtEpochMillis?: number }>} items
     * @returns {Record<string, string>}
     */
    function snapshotDisplayLabels(items) {
        /** @type {Record<string, string>} */
        const out = {};
        if (!Array.isArray(items) || !items.length) return out;
        const sorted = [...items].sort(
            (a, b) =>
                Number(a.createdAtEpochMillis || 0) - Number(b.createdAtEpochMillis || 0),
        );
        /** @type {Record<string, number>} */
        const dayCount = {};
        const modStyleId = /^\d{8}-\d+$/;
        for (const it of sorted) {
            const id = it && it.id != null ? String(it.id) : "";
            if (!id) continue;
            if (modStyleId.test(id)) {
                out[id] = id;
                continue;
            }
            const ts = Number(it.createdAtEpochMillis || 0);
            const d = new Date(ts);
            const y = d.getFullYear();
            const m = String(d.getMonth() + 1).padStart(2, "0");
            const day = String(d.getDate()).padStart(2, "0");
            const key = `${y}${m}${day}`;
            dayCount[key] = (dayCount[key] || 0) + 1;
            out[id] = `${key}-${dayCount[key]}`;
        }
        return out;
    }

    window.SGU = {
        api,
        session,
        refreshNavSession,
        navLogout,
        safeNextHref,
        toast,
        showShareUrlModal,
        alertModal,
        confirmModal,
        snapshotDisplayLabels,
        whenReady: whenShellReady,
    };

    function getPreferredTheme() {
        const stored = localStorage.getItem("sgu_theme");
        if (stored === "light" || stored === "dark") return stored;
        return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
    }

    function applyTheme(theme) {
        const root = document.documentElement;
        root.setAttribute("data-theme", theme);
        localStorage.setItem("sgu_theme", theme);
    }

    function createThemeToggle() {
        if (document.querySelector(".theme-toggle")) return;
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "theme-toggle";
        btn.setAttribute("aria-label", "切换明暗主题");
        btn.textContent = "🌓";
        btn.addEventListener("click", () => {
            const current = document.documentElement.getAttribute("data-theme") || getPreferredTheme();
            const next = current === "dark" ? "light" : "dark";
            applyTheme(next);
            btn.textContent = next === "dark" ? "☀️" : "🌙";
        });
        document.body.appendChild(btn);
        const init = document.documentElement.getAttribute("data-theme") || getPreferredTheme();
        btn.textContent = init === "dark" ? "☀️" : "🌙";
    }

    function initTheme() {
        const theme = getPreferredTheme();
        applyTheme(theme);
        const media = window.matchMedia("(prefers-color-scheme: dark)");
        media.addEventListener("change", (e) => {
            if (!localStorage.getItem("sgu_theme")) {
                applyTheme(e.matches ? "dark" : "light");
            }
        });
    }

    document.addEventListener("DOMContentLoaded", () => {
        initTheme();
        createThemeToggle();
        document.getElementById("navLogoutBtn")?.addEventListener("click", (e) => {
            e.preventDefault();
            void navLogout();
        });

        void (async () => {
            const BOOTSTRAP_WAIT_MS = 22000;
            try {
                await Promise.race([
                    bootstrapShell(),
                    new Promise((_, reject) =>
                        setTimeout(() => reject(new Error("bootstrap_timeout")), BOOTSTRAP_WAIT_MS),
                    ),
                ]);
            } catch (_) {
                /* 超时仍解锁 whenReady，避免「正在验证登录」遮罩永久挡住页面 */
            } finally {
                markActiveNav();
                resolveShellReady();
            }
        })();
    });
})();
