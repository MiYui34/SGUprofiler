document.addEventListener("DOMContentLoaded", async () => {
    const SGU = window.SGU;
    if (!SGU || !SGU.whenReady) {
        document.documentElement.classList.remove("auth-pending");
        return;
    }
    await SGU.whenReady;
    document.documentElement.classList.remove("auth-pending");

    const sel = document.getElementById("snapSelect");
    const summaryEl = document.getElementById("analysisSummary");
    const accordionEl = document.getElementById("analysisAccordion");
    const rawPre = document.getElementById("rawData");
    const currentLabel = document.getElementById("currentSnap");

    /** @type {Record<string, string>} */
    let snapDisplayById = {};

    /** @type {string | null} */
    let lastLoadedId = null;

    function esc(t) {
        return String(t ?? "").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;");
    }

    function parseLagLines(lines) {
        const groups = {};
        if (!Array.isArray(lines)) return groups;
        for (const line of lines) {
            if (typeof line !== "string") continue;
            const parts = line.split("-").map(p => p.trim());
            if (parts.length < 3) continue;
            const entity = parts[0];
            const lagName = parts.slice(1, -1).join("-") || "unknown";
            const valStr = parts[parts.length-1];
            const val = parseFloat(valStr) || 0;
            if (!groups[entity]) groups[entity] = { total: 0, items: [] };
            groups[entity].items.push({ name: lagName, val });
            groups[entity].total += val;
        }
        return groups;
    }

    function renderAnalysis(data) {
        summaryEl.innerHTML = "";
        accordionEl.innerHTML = "";
        rawPre.classList.add("hidden");
        if (rawPre) rawPre.textContent = "";

        let lagLines = data.lagData || data.lines || data.entries || [];
        if (!Array.isArray(lagLines) || !lagLines.length) {
            if (data && typeof data === "object") {
                lagLines = Object.entries(data).filter(([k]) => k.includes("-")).map(([k,v]) => `${k}-${v}`);
            }
        }
        const groups = parseLagLines(lagLines);

        const entities = Object.keys(groups).sort((a,b) => groups[b].total - groups[a].total);

        if (!entities.length) {
            summaryEl.innerHTML = `<p class="muted">无解析到「实体-卡顿-数值」格式数据。请确认快照 schema 为 entity_lag_v1且包含 lagData 数组。</p>`;
            if (rawPre) {
                rawPre.textContent = JSON.stringify(data, null, 2);
                rawPre.classList.remove("hidden");
            }
            return;
        }

        let sumHtml =
            `<div class="data-table-wrap"><table class="lag-summary-table"><colgroup><col class="lag-summary-col-entity" /><col class="lag-summary-col-num" /><col class="lag-summary-col-num" /></colgroup><thead><tr><th scope="col">实体</th><th scope="col">TICK 总卡顿 (ms)</th><th scope="col">子项数</th></tr></thead><tbody>`;
        for (const e of entities) {
            const g = groups[e];
            sumHtml += `<tr><td>${esc(e)}</td><td class="num"><strong>${g.total.toFixed(2)}</strong></td><td class="num">${g.items.length}</td></tr>`;
        }
        sumHtml += `</tbody></table></div>`;
        summaryEl.innerHTML = `<h3 style="margin:8px 0 6px;">实体 TICK 汇总（默认展示）</h3>` + sumHtml;

        for (const e of entities) {
            const g = groups[e];
            const det = document.createElement("details");
            det.className = "lag-entity";
            const sum = document.createElement("summary");
            sum.innerHTML = `<span class="entity-name">${esc(e)}</span> <span class="total-badge">${g.total.toFixed(1)} ms TICK</span> <span class="muted small">(${g.items.length} 项)</span>`;
            det.appendChild(sum);

            const content = document.createElement("div");
            content.className = "lag-details";
            let itemsHtml = `<ul style="margin:8px 0;padding-left:18px;">`;
            for (const it of g.items.sort((a,b)=>b.val-a.val)) {
                itemsHtml += `<li><span>${esc(it.name)}</span> <strong>${it.val.toFixed(2)}</strong> ms</li>`;
            }
            itemsHtml += `</ul>`;
            content.innerHTML = itemsHtml;
            det.appendChild(content);
            accordionEl.appendChild(det);
        }
    }

    function clearAnalysisView() {
        lastLoadedId = null;
        if (summaryEl) summaryEl.innerHTML = "";
        if (accordionEl) accordionEl.innerHTML = "";
        if (currentLabel) currentLabel.innerHTML = "";
        if (rawPre) {
            rawPre.textContent = "";
            rawPre.classList.add("hidden");
        }
    }

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
        sel.innerHTML = "";
        const resp = await SGU.api("/api/v1/snapshots", { method: "GET" });
        const items = (resp.items || []).filter((it) => {
            const sch = it.schema || "";
            return sch !== "deep_entity_v2" && sch !== "dimension_scan_v1";
        });
        snapDisplayById = computeSnapshotLabels(items);
        for (const it of items) {
            const opt = document.createElement("option");
            const rawId = String(it.id ?? "");
            opt.value = rawId;
            const ts = Number(it.createdAtEpochMillis || 0);
            const label = snapDisplayById[rawId] || rawId;
            opt.textContent = `${label} · ${it.schema} · ${new Date(ts).toLocaleString()}`;
            opt.title = `存储 ID：${rawId}\n` + opt.textContent;
            sel.appendChild(opt);
        }
    }

    document.getElementById("reloadListBtn")?.addEventListener("click", () => void reloadList().catch(()=>{}));

    document.getElementById("deleteSnapshotBtn")?.addEventListener("click", async () => {
        const id = sel?.options[sel.selectedIndex]?.value;
        if (!id) {
            SGU.alertModal("请选择快照", { title: "提示" });
            return;
        }
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
            if (id === lastLoadedId) clearAnalysisView();
            if (typeof SGU.toast === "function") {
                SGU.toast("已删除采样", { variant: "success", duration: 2200 });
            }
            await reloadList();
        } catch (e) {
            SGU.alertModal("删除失败: " + (e.message || e), { title: "错误" });
        }
    });

    document.getElementById("loadAnalysisBtn")?.addEventListener("click", async () => {
        const id = sel?.options[sel.selectedIndex]?.value;
        if (!id) {
            SGU.alertModal("请选择快照", { title: "提示" });
            return;
        }
        try {
            const obj = await SGU.api(`/api/v1/snapshots/${encodeURIComponent(id)}`, { method: "GET" });
            const label = snapDisplayById[id] || id;
            currentLabel.innerHTML = `<span class="snap-display-label">${esc(label)}</span>`;
            lastLoadedId = id;
            renderAnalysis(obj);
            if (rawPre) rawPre.textContent = JSON.stringify(obj, null, 2);
        } catch (e) {
            SGU.alertModal("加载失败: " + (e.message || e), { title: "错误" });
        }
    });

    try {
        await reloadList();
    } catch (_) {}
});
