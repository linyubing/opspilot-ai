const byId = (id) => document.getElementById(id);
const generateButton = byId("generateButton");
const statusText = byId("status");
const errorBox = byId("errorBox");
const emptyBox = byId("emptyBox");
const reportBox = byId("report");
let latestForecast = null;
let latestBar = null;
let checksSeq = 0;

const directions = {BULLISH: "上涨", NEUTRAL: "中性", BEARISH: "下跌", ABSTAINED: "不出信号（弃权）"};
const statuses = {PENDING: "等待验证", RESOLVED: "已验证", DATA_MISSING: "缺少结算数据", VOIDED: "已作废"};

async function request(url, options = {}) {
    const response = await fetch(url, {headers: {Accept: "application/json"}, ...options});
    if (!response.ok) {
        const data = await response.json().catch(() => ({}));
        const error = new Error(data.message || data.detail || `请求失败：HTTP ${response.status}`);
        error.status = response.status;
        throw error;
    }
    return response.json();
}

function text(id, value) {
    byId(id).textContent = value ?? "-";
}

function percent(value) {
    return value == null ? "暂无" : `${(Number(value) * 100).toFixed(1)}%`;
}

function money(value) {
    return value == null ? "-" : `$${Number(value).toFixed(2)}`;
}

async function loadGoldBar() {
    const bar = await request("/api/market-data/gold/daily-bars/latest");
    latestBar = bar;
    text("barDate", bar.priceDate);
    text("barOpen", money(bar.open));
    text("barHigh", money(bar.high));
    text("barLow", money(bar.low));
    text("barClose", money(bar.close));
    text("barProvider", bar.provider === "twelve_data" ? "Twelve Data" : bar.provider);
    renderPriceBasis();
}

function renderPriceBasis() {
    if (!latestForecast || !latestBar || latestForecast.baseDate !== latestBar.priceDate) {
        text("basePriceLabel", "预测基准价");
        return;
    }
    const matchesClose = Math.abs(Number(latestForecast.basePrice) - Number(latestBar.close)) < 0.005;
    text("basePriceLabel", matchesClose ? "基准日收盘价" : "历史预测基准价（旧口径）");
}

function lag(baseDate, sourceDate) {
    if (!baseDate || !sourceDate) return "";
    const days = Math.round((new Date(`${baseDate}T00:00:00Z`) - new Date(`${sourceDate}T00:00:00Z`)) / 86400000);
    return days === 0 ? "与基准日一致" : `较基准日滞后 ${days} 天`;
}

function renderIdentity(forecast) {
    const timing = forecast.timing;
    text("targetLabel", timing ? "承诺目标交易日" : "预计目标交易日（非历史承诺）");
    text("targetSession", timing?.targetDate || forecast.expectedTargetDate || "未知");
    text("settlementDate", forecast.targetDate || "尚未结算");
    text("signalState", forecast.signal === "ABSTAINED" ? "不出信号（弃权），不是中性"
        : forecast.signal === "PREDICTED" ? "已出信号" : "旧协议未声明信号状态");
    text("forecastIdentity", `正式大模型记录：${forecast.modelName || "未知"}；提示词 ${forecast.promptVersion || "未知"}；方向规则 ${forecast.forecastRuleVersion || "未知"}；时段规则 ${timing?.ruleVersion || "未知"}。`
        + (forecast.reportedModel ? `响应模型标识已核对：${forecast.reportedModel}。` : "旧记录未保存响应模型标识，身份核对状态未知。")
        + (forecast.modelRun ? `本次服务端模型摘要：${forecast.modelRun.digest}；服务版本：${forecast.modelRun.serverVersion}；${runLabel(forecast.modelRun.key)}。前后观察不认证执行中工件，也未固定所有服务端默认值。`
            : "模型名称与响应标识都不是不可变工件版本；本条未保存逐次运行依据，完整推理参数尚未留存。")
        + "统计实验成绩不代表本记录的表现。");
    text("modelRunDetails", forecast.modelRun ? JSON.stringify(forecast.modelRun, null, 2)
        : "本条未保存逐次运行依据；不使用当前模型资料补写历史。");
    text("promptHash", forecast.promptHash || "未知");
    const local = instant => new Intl.DateTimeFormat("zh-CN", {
        timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit",
        hour: "2-digit", minute: "2-digit", hourCycle: "h23"
    }).format(new Date(instant));
    text("localInterval", timing?.start && timing?.end
        ? `北京时间（Asia/Shanghai）候选目标区间：${local(timing.start)} → ${local(timing.end)}；仅转换展示时区，不是官方时段认证。`
        : "旧记录未保存目标候选时段，不能据预计日期补造发布资格。");
}

// 同模型摘要、显式参数和服务版本才共用运行配置键，未知依据独立展示。
function runLabel(key) {
    return key ? `逐次运行配置键 ${String(key).slice(0, 12)}` : "逐次运行依据未知";
}

function renderForecast(forecast) {
    latestForecast = forecast;
    const direction = forecast.signal === "ABSTAINED" ? "ABSTAINED" : forecast.predictedDirection;

    reportBox.hidden = false;
    emptyBox.hidden = true;
    const card = byId("directionCard");
    card.className = `direction-card ${direction === "BULLISH" ? "up" : direction === "BEARISH" ? "down" : "flat"}`;
    text("direction", directions[direction] || direction);
    text("baseDate", forecast.baseDate);
    renderIdentity(forecast);
    text("basePrice", money(forecast.basePrice));
    text("forecastStatus", statuses[forecast.status] || forecast.status);
    text("createdAt", forecast.createdAt ? new Date(forecast.createdAt).toLocaleString("zh-CN") : "-");
    const phases = {UNKNOWN: "未知", BEFORE_SESSION: "候选开盘前", IN_SESSION: "候选盘中（非开盘前）", EXPIRED: "候选时段结束后"};
    text("publicationPhase", phases[forecast.publicationPhase] || "未知");
    text("targetInterval", forecast.timing
        ? `承诺目标日：${forecast.timing.targetDate}；${forecast.timing.start} → ${forecast.timing.end}（UTC候选区间；${forecast.timing.ruleVersion}）`
        : "旧记录未保存承诺目标日或候选时段，显示的预计日期不是历史发布证据。");
    text("publicationWarning", forecast.publicationWarning || "发布时间合同未知，不能证明事前预测。");
    text("modelName", forecast.modelName);
    text("reasoning", forecast.reasoning);
    renderPriceBasis();
    renderMissReason(forecast.missReason);
    loadChecks(forecast);

    const conditions = byId("conditions");
    conditions.replaceChildren(...(forecast.invalidationConditions || []).map(item => {
        const li = document.createElement("li");
        li.textContent = item;
        return li;
    }));
}

async function loadChecks(forecast) {
    // 页面多次刷新时，较早响应不得覆盖当前预测的检查记录。
    const seq = ++checksSeq;
    const link = byId("checkLink");
    link.hidden = true;
    text("checkTime", "尚未读取");
    text("checkReason", "正在读取已保存检查...");
    text("checkEvidence", "");
    if (!forecast.id) {
        text("checkReason", "预测编号未知，不能读取核验记录。");
        return;
    }
    const url = `/api/research/gold/forecasts/${encodeURIComponent(forecast.id)}/checks?limit=10`;
    try {
        const checks = await request(url);
        if (seq !== checksSeq) return;
        if (!Array.isArray(checks)) throw new Error("核验记录格式错误");
        link.href = url;
        link.hidden = false;
        if (checks.length === 0) {
            text("checkReason", "未留存结算检查，不能据此判断核验通过或失败。");
            text("checkEvidence", "旧证据不补造；须在实际结算尝试后查看记录。");
            return;
        }
        const check = checks[0];
        text("checkTime", check.checkedAt ? new Date(check.checkedAt).toLocaleString("zh-CN", {timeZone: "Asia/Shanghai"}) + "（北京时间）" : "未知");
        text("checkReason", check.reason || "检查原因未知，查看原始记录。");
        const states = {MATCHED:"开高低收一致", OHLC_MISMATCH:"开高低收不一致", MISSING_HOURS:"缺少小时线",
            NOT_ENDED:"时段未结束", INVALID_INPUT:"输入无效"};
        const describe = (name, proof) => {
            if (!proof) return `${name}：尚无小时核验输入`;
            const zone = proof.input?.timezoneBasis === "RESPONSE_UTC" ? "响应确认UTC"
                : proof.input?.timezoneBasis === "REQUEST_ONLY_UTC" ? "仅请求UTC，响应未确认" : "时区依据未知";
            return `${name}：${states[proof.status] || "未知"}，${proof.receivedRows}/${proof.expectedHours}小时；${zone}`;
        };
        text("checkEvidence", `${describe("基准", check.baseCheck)}。${describe("目标", check.targetCheck)}。`);
    } catch (error) {
        if (seq !== checksSeq) return;
        link.hidden = true;
        text("checkReason", "结算检查读取失败，不能据此判断数据缺失或核验通过。");
        text("checkEvidence", "请稍后刷新；预测及原有成绩未改变。");
    }
}

function renderMissReason(missReason) {
    const panel = byId("missReasonPanel");
    if (!missReason || !missReason.code) {
        panel.hidden = true;
        return;
    }
    text("missReasonCode", missReason.code);
    text("missReasonTitle", missReason.title);
    text("missReasonDetail", missReason.detail);
    const tags = byId("missReasonTags");
    tags.replaceChildren(...(missReason.tags || []).filter(Boolean).map(item => {
        const li = document.createElement("li");
        li.textContent = item;
        return li;
    }));
    panel.hidden = false;
}

function renderForecastOnly(forecast) {
    renderForecast(forecast);
    text("goldDate", forecast.baseDate);
    text("goldLag", "预测采用的黄金基准行情");
    text("rateDate", "研究解读尚未生成");
    text("rateLag", "");
    text("dollarDate", "研究解读尚未生成");
    text("dollarLag", "");
    text("summary", "方向预测已生成，完整研究解读尚未生成。");
    text("rateAnalysis", "-");
    text("dollarAnalysis", "-");
}

function renderReport(data) {
    const snapshot = data.snapshot.snapshot;
    const forecast = data.forecast;
    const narrative = data.narrative.content;

    renderForecast(forecast);

    text("goldDate", snapshot.latestGoldDate);
    text("rateDate", snapshot.latestRealRateDate);
    text("dollarDate", snapshot.latestDollarIndexDate);
    text("goldLag", lag(snapshot.analysisDate, snapshot.latestGoldDate));
    text("rateLag", lag(snapshot.analysisDate, snapshot.latestRealRateDate));
    text("dollarLag", lag(snapshot.analysisDate, snapshot.latestDollarIndexDate));
    text("summary", narrative.summary);
    text("rateAnalysis", narrative.realRateAnalysis);
    text("dollarAnalysis", narrative.dollarIndexAnalysis);
}

async function loadLatest() {
    errorBox.hidden = true;
    try {
        const forecasts = await request("/api/research/gold/forecasts?limit=1");
        if (forecasts.length === 0) {
            reportBox.hidden = true;
            emptyBox.hidden = false;
            statusText.textContent = "当前还没有正式预测。";
            return;
        }

        renderForecastOnly(forecasts[0]);
        statusText.textContent = "已加载最新正式预测，完整研究解读尚未生成。";

        try {
            const report = await request("/api/research/gold/daily-report/latest");
            renderReport(report);
            statusText.textContent = "已加载最新正式预测。";
        } catch (reportError) {
            if (reportError.status !== 404) throw reportError;
        }
    } catch (error) {
        showError(error);
    }
}

function renderSignalStats(data) {
    const s = data.signals;
    text("signalStats", s ? `正式记录 ${s.sampleCount}；显式出信号 ${s.declaredSignalCount}；弃权 ${s.abstainedCount}；旧未知协议 ${s.legacyCount}；新协议覆盖率 ${percent(s.coverage)}。`
        + `已结算信号准确率 ${percent(s.signalAccuracy)}；同信号日期中性基线 ${percent(s.neutralBaselineAccuracy)}；完整样本准确率 ${percent(s.fullSampleAccuracy)}。${s.note}`
        : "缺少逐条信号协议证据，不能从历史方向反推覆盖率。");
    const box = byId("versionStats");
    box.replaceChildren();
    for (const v of data.versions || []) {
        const row = document.createElement("p");
        row.className = "note";
        row.textContent = `${v.modelName} · ${v.promptVersion} · ${v.forecastRuleVersion} · 响应标识${v.reportedModel ? "已核对" : "未知"} · ${runLabel(v.runKey)}：已结算信号 ${v.sampleCount}，信号准确率 ${percent(v.accuracy)}，`
            + `覆盖率 ${percent(v.signals?.coverage)}，弃权 ${v.signals?.abstainedCount ?? "未知"}，旧协议 ${v.signals?.legacyCount ?? "未知"}，`
            + `同信号日期中性基线 ${percent(v.signals?.neutralBaselineAccuracy)}，完整样本准确率 ${percent(v.signals?.fullSampleAccuracy)}。`;
        box.appendChild(row);
    }
}

async function loadAccuracy() {
    try {
        const data = await request("/api/research/gold/forecasts/evaluation");
        renderSignalStats(data);
        text("resolvedCount", data.resolvedCount);
        text("overallAccuracy", percent(data.overallAccuracy));
        text("rollingAccuracy", percent(data.rolling20Accuracy));
        const timing = data.timing;
        text("trustedCount", timing?.trustedCount ?? "暂无");
        text("trustedAccuracy", percent(timing?.trustedAccuracy));
        text("timingUnknown", timing?.unknown?.sampleCount ?? "暂无");
        text("timingBefore", timing?.beforeSession?.sampleCount ?? "暂无");
        text("timingDuring", timing?.inSession?.sampleCount ?? "暂无");
        text("timingInvalid", timing?.invalid?.sampleCount ?? "暂无");
        const box = document.getElementById("independentStats");
        if (box) {
            box.replaceChildren();
            const groups = data.independent?.groups ?? [];
            if (!groups.length) box.textContent = "暂无具备候选发布合同的独立目标；旧记录不补造资格。";
            for (const group of groups) {
                const row = document.createElement("p");
                const phase = group.phase === "BEFORE_SESSION" ? "候选开盘前" : "候选盘中";
                row.textContent = `${group.modelName} · ${group.promptVersion} · ${group.forecastRuleVersion} · 响应标识${group.reportedModel ? "已核对" : "未知"} · ${runLabel(group.runKey)} · ${phase}：`
                    + `独立目标 ${group.selectedCount}，已结算 ${group.resolvedCount}，未结算 ${group.unresolvedCount}，`
                    + `重复记录排除 ${group.duplicatesIgnored}，信号 ${group.signalCount ?? "未知"}，弃权 ${group.abstainedCount ?? "未知"}，覆盖率 ${percent(group.coverage)}，候选信号命中率 ${percent(group.accuracy)}。`;
                box.appendChild(row);
            }
        }
    } catch (error) {
        text("overallAccuracy", "读取失败");
        text("independentStats", "独立目标统计读取失败，不能据此判断准确率。");
    }

    const backtestId = localStorage.getItem("goldBacktestId");
    if (!backtestId) return;
    try {
        const data = await request(`/api/research/gold/backtests/${encodeURIComponent(backtestId)}/evaluation`);
        text("backtestAccuracy", percent(data.overallAccuracy));
    } catch (error) {
        text("backtestAccuracy", "读取失败");
    }
}

const dataStates = {FRESH: "新鲜", STALE: "过期", UNKNOWN: "未知"};

function renderDataStatus(data) {
    text("dataStatusOverall", dataStates[data.overall] || data.overall || "-");
    // API 使用因子名称，页面沿用短节点名；不能直接拼接 API code。
    const ids = {gold: "gold", realRate: "rate", dollarIndex: "dollar"};
    (data.items || []).forEach(item => {
        const id = ids[item.code];
        if (!id) return;
        const badge = byId(`${id}Status`);
        if (badge) {
            badge.textContent = dataStates[item.state] || item.state;
            badge.className = `status-pill ${item.state === "STALE" ? "stale" : item.state === "FRESH" ? "fresh" : ""}`;
        }
        const note = byId(`${id}StatusDetail`);
        if (note) note.textContent = item.detail || "";
    });
    // 后端总体状态只要求核心因子新鲜，不代表可选美元因子也新鲜。
    if (data.overall === "FRESH" && (data.items || []).some(item =>
        item.code === "dollarIndex" && item.state === "STALE")) {
        text("dataStatusOverall", "核心数据新鲜，美元仍过期");
    }
}

async function loadDataStatus() {
    try {
        const data = await request("/api/research/gold/data-status");
        renderDataStatus(data);
    } catch (error) {
        text("dataStatusOverall", "读取失败");
    }
}

function showError(error) {
    errorBox.textContent = error instanceof TypeError
        ? "无法连接后端服务，请确认应用已经启动。"
        : error.message;
    errorBox.hidden = false;
    statusText.textContent = "操作未完成。";
}

async function generate() {
    generateButton.disabled = true;
    errorBox.hidden = true;
    statusText.textContent = "正在同步真实数据并调用大模型，可能需要几十秒...";
    try {
        await request("/api/research/gold/daily-report", {method: "POST"});
        await Promise.all([loadLatest(), loadAccuracy(), loadGoldBar(), loadDataStatus()]);
        statusText.textContent = "预测已保存，等待下一有效交易日真实价格验证。";
    } catch (error) {
        showError(error);
    } finally {
        generateButton.disabled = false;
    }
}

generateButton.addEventListener("click", generate);
Promise.all([loadLatest(), loadAccuracy(), loadGoldBar(), loadDataStatus()]).catch(showError);
