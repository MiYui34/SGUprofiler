document.addEventListener("DOMContentLoaded", async () => {
    /** @type {typeof window.SGU | undefined} */
    const SGU = window.SGU;

    let __snapshotsFallbackAlertSeq = 0;

    function notify(msg, variant) {
        const titleText = variant === "error" ? "错误" : "提示";
        const text = String(msg || "");
        if (window.SGU && typeof window.SGU.alertModal === "function") {
            window.SGU.alertModal(text, { title: titleText });
        } else {
            alertFallbackModal(text, titleText);
        }
    }

    function fail(msg) {
        notify(msg, "error");
    }

    /** 页内提示框（与 SGU.alertModal 一致），无 app.js 时兜底 */
    function alertFallbackModal(message, titleText) {
        if (!document.body) return;
        const text = String(message || "");
        const titleStr = titleText || "提示";
        const titleId = "sgu-fallback-alert-title-" + String(++__snapshotsFallbackAlertSeq);
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
        title.textContent = titleStr;
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

    function openHint(msg) {
        notify(msg, "info");
    }

    if (!SGU || !SGU.whenReady) {
        fail("页面核心脚本未加载成功，请检查是否能访问 /js/app.js（不要禁用脚本）。");
        document.documentElement.classList.remove("auth-pending");
        return;
    }

    await SGU.whenReady;

    document.documentElement.classList.remove("auth-pending");
    document.documentElement.classList.remove("auth-redirect");

    const sel = document.getElementById("snapSelect");
    const details = document.getElementById("details");
    const detailRendered = document.getElementById("detailRendered");

    function esc(t) {
        return String(t ?? "")
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;");
    }

    /** @param {[string,string][]} cols @param {Record<string,unknown>[]} rows */
    function tableHtml(cols, rows) {
        let h = '<div class="data-table-wrap" style="overflow-x:auto;margin:8px 0 12px;"><table style="width:100%;border-collapse:collapse;font-size:13px;">';
        h += '<thead><tr>';
        for (const [, lab] of cols) {
            h += `<th style="border:1px solid var(--line);padding:5px 8px;text-align:left;">${esc(lab)}</th>`;
        }
        h += "</tr></thead><tbody>";
        for (const r of rows) {
            h += "<tr>";
            for (const [k] of cols) {
                const v = r[k];
                h += `<td style="border:1px solid var(--line);padding:5px 8px;">${esc(
                    v === undefined || v === null ? "" : v,
                )}</td>`;
            }
            h += "</tr>";
        }
        h += "</tbody></table></div>";
        return h;
    }

    /**
     * 默认实体采样：schema entity_lag_v1，lagData 为「实体-卡顿名-数值」字符串行。
     * @param {Record<string,unknown>} data
     */
    function renderEntityLagSummary(data) {
        const raw = data.lagData;
        const lines = Array.isArray(raw) ? raw.filter((x) => typeof x === "string") : [];
        const groups = {};
        for (const line of lines) {
            const parts = String(line).split("-").map((p) => p.trim());
            if (parts.length < 3) continue;
            const entity = String(parts[0]);
            const lagName = parts.slice(1, -1).join("-") || "unknown";
            const val = parseFloat(String(parts[parts.length - 1])) || 0;
            if (!groups[entity]) groups[entity] = { total: 0, items: [] };
            groups[entity].items.push({ name: lagName, val });
            groups[entity].total += val;
        }
        const entities = Object.keys(groups).sort((a, b) => groups[b].total - groups[a].total);
        if (!entities.length) {
            return '<p class="muted">lagData 为空或无法按「实体-卡顿-数值」解析。</p>';
        }
        let h = '<h3 style="margin:14px 0 6px;font-size:1rem;">实体 TICK 汇总</h3>';
        const sumRows = entities.map((e) => ({
            entity: e,
            total: groups[e].total.toFixed(3),
            n: groups[e].items.length,
        }));
        h += tableHtml(
            [
                ["entity", "实体"],
                ["total", "TICK 合计 (ms)"],
                ["n", "子项数"],
            ],
            sumRows,
        );
        h += '<h4 style="margin:14px 0 6px;font-size:0.95rem;">明细（按实体）</h4><ul style="margin:0;padding-left:1.2em;">';
        for (const e of entities) {
            const g = groups[e];
            const top = [...g.items].sort((a, b) => b.val - a.val).slice(0, 12);
            h += `<li><strong>${esc(e)}</strong> · 合计 ${g.total.toFixed(3)} ms`;
            if (top.length) {
                h +=
                    "<br><span class=\"muted small\">" +
                    top.map((it) => `${esc(it.name)} ${it.val.toFixed(3)} ms`).join(" · ") +
                    (g.items.length > 12 ? " …" : "") +
                    "</span>";
            }
            h += "</li>";
        }
        h += "</ul>";
        return h;
    }

    /** 与 app.js 一致；避免浏览器缓存旧 app.js 时列表仍只显示 UUID。 */
    function computeSnapshotLabels(items) {
        if (typeof SGU.snapshotDisplayLabels === "function") {
            return SGU.snapshotDisplayLabels(items);
        }
        /** @type {Record<string, string>} */
        const out = {};
        if (!Array.isArray(items) || !items.length) return out;
        const sorted = [...items].sort(
            (a, b) => Number(a.createdAtEpochMillis || 0) - Number(b.createdAtEpochMillis || 0),
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

    async function reloadList() {
        if (!sel || !details) return;
        sel.innerHTML = "";
        const resp = await SGU.api("/api/v1/snapshots", { method: "GET" });
        const items = (resp.items || []).filter((it) => {
            const schema = it.schema || "";
            return schema !== "deep_entity_v2" && schema !== "dimension_scan_v1";
        });
        const labels = computeSnapshotLabels(items);
        for (const it of items) {
            const schema = it.schema || "";
            const opt = document.createElement("option");
            const id = it.id || "?";
            const ts = Number(it.createdAtEpochMillis || 0);
            opt.value = String(id);
            const label = labels[String(id)] || id;
            opt.textContent = `${label} · ${schema || "?"} · ${new Date(ts).toLocaleString()}`;
            opt.title = `存储 ID：${String(id)}\n` + opt.textContent;
            sel.appendChild(opt);
        }
        details.textContent = sel.options.length
            ? `已加载 ${sel.options.length} 条实体采样快照。请选择并查看详情。`
            : `暂无实体采样快照。请先在服务端用 profile stop 上报数据。`;
        if (detailRendered) {
            detailRendered.innerHTML = "";
            detailRendered.classList.add("hidden");
        }
    }

    document.getElementById("reloadListBtn")?.addEventListener("click", () => {
        void reloadList().catch((err) => fail("刷新失败：" + String(err.message || err)));
    });

    document.getElementById("loadDetailsBtn")?.addEventListener("click", async () => {
        const id = sel?.options[sel.selectedIndex]?.value;
        if (!id) return openHint("请先选择一条快照");
        try {
            const obj = await SGU.api(`/api/v1/snapshots/${encodeURIComponent(id)}`, { method: "GET" });
            if (detailRendered) {
                detailRendered.innerHTML = "";
                const schema = obj && obj.schema;
                if (schema === "entity_lag_v1") {
                    detailRendered.innerHTML = renderEntityLagSummary(/** @type {Record<string,unknown>} */ (obj));
                    detailRendered.classList.remove("hidden");
                } else {
                    detailRendered.innerHTML =
                        '<p class="muted">当前快照类型无表格摘要，请查看下方原始 JSON。</p>';
                    detailRendered.classList.remove("hidden");
                }
            }
            details.textContent = JSON.stringify(obj, null, 2);
        } catch (err) {
            fail("读取失败：" + String(err.message || err));
        }
    });

    document.getElementById("shareSnapshotBtn")?.addEventListener("click", async () => {
        const id = sel?.options[sel.selectedIndex]?.value;
        if (!id) return openHint("请先选择一条快照");
        try {
            const r = await SGU.api(`/api/v1/snapshots/${encodeURIComponent(id)}/share`, {
                method: "POST",
                body: "{}",
            });
            const url = r && r.shareUrl ? String(r.shareUrl) : "";
            if (!url) throw new Error("响应缺少 shareUrl");
            await navigator.clipboard.writeText(url).catch(() => null);
            if (typeof SGU.showShareUrlModal === "function") {
                SGU.showShareUrlModal(url);
            } else if (typeof SGU.alertModal === "function") {
                SGU.alertModal(
                    "已尝试复制到剪贴板。请手动复制以下链接：\n\n" + url,
                    { title: "只读分享链接" },
                );
            } else {
                alertFallbackModal(
                    "已尝试复制到剪贴板。请手动复制以下链接：\n\n" + url,
                    "只读分享链接",
                );
            }
        } catch (err) {
            fail("生成分享链接失败：" + String(err.message || err));
        }
    });

    document.getElementById("deleteSnapshotBtn")?.addEventListener("click", async () => {
        const id = sel?.options[sel.selectedIndex]?.value;
        if (!id) return openHint("请先选择一条快照");
        const opt = sel.options[sel.selectedIndex];
        const line0 = opt && opt.textContent ? opt.textContent.split(" · ")[0].trim() : id;
        const ok =
            typeof SGU.confirmModal === "function"
                ? await SGU.confirmModal(
                      `确定删除此采样？\n\n${line0}\n\n删除后无法恢复，相关分享链接也将失效。`,
                      {
                          title: "删除采样",
                          okLabel: "删除",
                          cancelLabel: "取消",
                          dangerous: true,
                      },
                  )
                : window.confirm(`确定删除采样「${line0}」？删除后无法恢复。`);
        if (!ok) return;
        try {
            await SGU.api(`/api/v1/snapshots/${encodeURIComponent(id)}`, { method: "DELETE" });
            if (typeof SGU.toast === "function") {
                SGU.toast("已删除采样", { variant: "success", duration: 2200 });
            }
            await reloadList();
        } catch (err) {
            fail("删除失败：" + String(err.message || err));
        }
    });

    try {
        await reloadList();
    } catch (err) {
        fail("加载列表失败：" + String(err.message || err));
    }
});
