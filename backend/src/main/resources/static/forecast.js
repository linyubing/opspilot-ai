const byId = (id) => document.getElementById(id);
const generateButton = byId("generateButton");
const statusText = byId("status");
const errorBox = byId("errorBox");
const emptyBox = byId("emptyBox");
const reportBox = byId("report");
let latestForecast = null;
let latestBar = null;

const directions = {BULLISH: "上涨", NEUTRAL: "中性", BEARISH: "下跌"};
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

function renderForecast(forecast) {
    latestForecast = forecast;
    const direction = forecast.predictedDirection;

    reportBox.hidden = false;
    emptyBox.hidden = true;
    const card = byId("directionCard");
    card.className = `direction-card ${direction === "BULLISH" ? "up" : direction === "BEARISH" ? "down" : "flat"}`;
    text("direction", directions[direction] || direction);
    text("baseDate", forecast.baseDate);
    text("targetLabel", forecast.targetDate ? "真实结算日" : "预计目标交易日");
    text("targetSession", forecast.targetDate || forecast.expectedTargetDate || `基准日 ${forecast.baseDate} 后的下一有效黄金交易日`);
    text("basePrice", money(forecast.basePrice));
    text("forecastStatus", statuses[forecast.status] || forecast.status);
    text("createdAt", forecast.createdAt ? new Date(forecast.createdAt).toLocaleString("zh-CN") : "-");
    text("modelName", forecast.modelName);
    text("reasoning", forecast.reasoning);
    renderPriceBasis();
    renderMissReason(forecast.missReason);

    const conditions = byId("conditions");
    conditions.replaceChildren(...(forecast.invalidationConditions || []).map(item => {
        const li = document.createElement("li");
        li.textContent = item;
        return li;
    }));
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

async function loadAccuracy() {
    try {
        const data = await request("/api/research/gold/forecasts/evaluation");
        text("resolvedCount", data.resolvedCount);
        text("overallAccuracy", percent(data.overallAccuracy));
        text("rollingAccuracy", percent(data.rolling20Accuracy));
    } catch (error) {
        text("overallAccuracy", "读取失败");
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
    const byCode = {};
    (data.items || []).forEach(item => {
        byCode[item.code] = item;
        const badge = byId(`${item.code}Status`);
        if (badge) {
            badge.textContent = dataStates[item.state] || item.state;
            badge.className = `status-pill ${item.state === "STALE" ? "stale" : item.state === "FRESH" ? "fresh" : ""}`;
        }
        const note = byId(`${item.code}StatusDetail`);
        if (note) note.textContent = item.detail || "";
    });
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
