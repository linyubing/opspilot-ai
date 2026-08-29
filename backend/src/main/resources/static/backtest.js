"use strict";

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

const levelNames = {
    INSUFFICIENT: "样本不足",
    NO_EDGE: "未发现优势",
    UNBALANCED: "方向表现失衡",
    WEAK: "优势较弱",
    PROMISING: "值得继续验证"
};

const form = document.getElementById("queryForm");
const taskInput = document.getElementById("taskId");
const loadButton = document.getElementById("loadButton");
const statusText = document.getElementById("status");
const errorBox = document.getElementById("error");
const resultBox = document.getElementById("result");
const conclusionCard = document.getElementById("conclusion");
const missOnly = document.getElementById("missOnly");
const caseList = document.getElementById("caseList");
const caseEmpty = document.getElementById("caseEmpty");
const caseSummary = document.getElementById("caseSummary");
let cases = [];

const directionNames = {
    BULLISH: "上涨",
    NEUTRAL: "中性",
    BEARISH: "下跌"
};

function formatPercent(value) {
    if (value === null || value === undefined || Number.isNaN(Number(value))) {
        return "暂无数据";
    }
    return `${(Number(value) * 100).toFixed(2)}%`;
}

function formatSignedPercent(value) {
    if (value === null || value === undefined || Number.isNaN(Number(value))) {
        return "暂无数据";
    }
    const number = Number(value) * 100;
    const sign = number > 0 ? "+" : "";
    return `${sign}${number.toFixed(2)}%`;
}

function formatCount(value) {
    if (value === null || value === undefined || value === "") {
        return "暂无数据";
    }
    const number = Number(value);
    return Number.isFinite(number) ? String(number) : "暂无数据";
}

function setText(id, value) {
    document.getElementById(id).textContent = value ?? "暂无数据";
}

function setLoading(loading) {
    taskInput.disabled = loading;
    loadButton.disabled = loading;
    loadButton.textContent = loading ? "正在加载…" : "加载评估";
    resultBox.setAttribute("aria-busy", String(loading));
    if (loading) {
        statusText.textContent = "正在读取回测评估结果…";
    }
}

function showError(message) {
    errorBox.textContent = message;
    errorBox.hidden = false;
    resultBox.hidden = true;
    statusText.textContent = "加载失败，请检查任务 ID 后重试。";
}

function clearError() {
    errorBox.textContent = "";
    errorBox.hidden = true;
}

function fallbackError(status) {
    if (status === 404) {
        return "未找到该回测任务。";
    }
    return `后端服务返回异常，状态码 ${status}。`;
}

async function readError(response) {
    try {
        const body = await response.json();
        return body.message || body.detail || body.error || fallbackError(response.status);
    } catch {
        return fallbackError(response.status);
    }
}

function renderConclusion(conclusion) {
    const level = conclusion?.level || "INSUFFICIENT";
    conclusionCard.dataset.level = level;
    setText("conclusionLevel", levelNames[level] || "需要人工复核");
    setText("conclusionSummary", conclusion?.summary || "暂无评估结论。");
}

function renderMetrics(data) {
    setText("sampleCount", formatCount(data.sampleCount));
    setText("accuracy", formatPercent(data.accuracy));
    setText("rollingAccuracy", formatPercent(data.rolling20Accuracy));
    setText("majorityBaseline", formatPercent(data.majorityBaselineAccuracy));
    setText("accuracyLift", formatSignedPercent(data.accuracyLift));
    setText("balancedAccuracy", formatPercent(data.balancedAccuracy));

    const lift = document.getElementById("accuracyLift");
    lift.classList.toggle("positive", Number(data.accuracyLift) > 0);
    lift.classList.toggle("negative", Number(data.accuracyLift) < 0);
}

function renderMatrix(matrix) {
    const bullish = matrix?.actualBullish || {};
    const neutral = matrix?.actualNeutral || {};
    const bearish = matrix?.actualBearish || {};

    setText("matrixBullishBullish", formatCount(bullish.bullish));
    setText("matrixBullishNeutral", formatCount(bullish.neutral));
    setText("matrixBullishBearish", formatCount(bullish.bearish));
    setText("matrixNeutralBullish", formatCount(neutral.bullish));
    setText("matrixNeutralNeutral", formatCount(neutral.neutral));
    setText("matrixNeutralBearish", formatCount(neutral.bearish));
    setText("matrixBearishBullish", formatCount(bearish.bullish));
    setText("matrixBearishNeutral", formatCount(bearish.neutral));
    setText("matrixBearishBearish", formatCount(bearish.bearish));
}

function renderDirection(prefix, data) {
    setText(`${prefix}Samples`, formatCount(data?.sampleCount));
    setText(`${prefix}Hits`, formatCount(data?.hitCount));
    setText(`${prefix}Accuracy`, formatPercent(data?.accuracy));
}

function formatReturn(value) {
    if (value === null || value === undefined || Number.isNaN(Number(value))) {
        return "暂无数据";
    }
    const number = Number(value);
    const sign = number > 0 ? "+" : "";
    return `${sign}${number.toFixed(2)}%`;
}

function tag(direction, label) {
    const span = document.createElement("span");
    span.className = `direction-tag ${String(direction || "").toLowerCase()}`;
    span.textContent = directionNames[direction] || "未知";
    span.setAttribute("aria-label", `${label}：${span.textContent}`);
    return span;
}

function caseRow(item) {
    const row = document.createElement("article");
    row.className = `case-row${item.hit ? "" : " miss"}`;

    const date = document.createElement("time");
    date.className = "case-date";
    date.dateTime = item.asOfDate;
    date.textContent = item.asOfDate || "暂无日期";

    const arrow = document.createElement("span");
    arrow.className = "case-arrow";
    arrow.textContent = "→";
    arrow.setAttribute("aria-hidden", "true");

    const value = document.createElement("span");
    value.className = "case-return";
    const number = Number(item.actualReturn);
    if (number > 0) value.classList.add("positive");
    if (number < 0) value.classList.add("negative");
    value.textContent = formatReturn(item.actualReturn);

    const hit = document.createElement("span");
    hit.className = "hit-tag";
    hit.textContent = item.hit ? "命中" : "错误";

    const details = document.createElement("details");
    details.className = "case-reason";
    const summary = document.createElement("summary");
    summary.textContent = "查看模型依据";
    const reason = document.createElement("p");
    reason.textContent = item.reasoning || "暂无模型依据。";
    details.append(summary, reason);

    row.append(date, tag(item.predictedDirection, "预测方向"), arrow,
            tag(item.actualDirection, "实际方向"), value, hit, details);
    return row;
}

function renderCases() {
    const visible = missOnly.checked ? cases.filter(item => !item.hit) : cases;
    caseList.replaceChildren(...visible.map(caseRow));
    caseEmpty.hidden = visible.length > 0;
    const missCount = cases.filter(item => !item.hit).length;
    caseSummary.textContent = `共 ${cases.length} 条，错误 ${missCount} 条`;
}

function render(data, id) {
    renderConclusion(data.conclusion);
    renderMetrics(data);
    renderMatrix(data.confusionMatrix);
    renderDirection("bullish", data.bullish);
    renderDirection("neutral", data.neutral);
    renderDirection("bearish", data.bearish);

    clearError();
    resultBox.hidden = false;
    statusText.textContent = `已加载任务：${id}`;
}

async function loadEvaluation(id) {
    if (!id) {
        showError("请输入回测任务 ID。");
        return;
    }
    if (!uuidPattern.test(id)) {
        showError("任务 ID 格式不正确。");
        return;
    }

    clearError();
    setLoading(true);
    try {
        const base = `/api/research/gold/backtests/${encodeURIComponent(id)}`;
        const [evaluationResponse, casesResponse] = await Promise.all([
            fetch(`${base}/evaluation`, {headers: {Accept: "application/json"}}),
            fetch(`${base}/results?limit=120`, {headers: {Accept: "application/json"}})
        ]);
        if (!evaluationResponse.ok) {
            throw new Error(await readError(evaluationResponse));
        }
        if (!casesResponse.ok) {
            throw new Error(await readError(casesResponse));
        }
        const [evaluation, loadedCases] = await Promise.all([
            evaluationResponse.json(),
            casesResponse.json()
        ]);
        cases = Array.isArray(loadedCases) ? loadedCases : [];
        missOnly.checked = false;
        render(evaluation, id);
        renderCases();
        history.replaceState(null, "", `?id=${encodeURIComponent(id)}`);
    } catch (error) {
        const message = error instanceof TypeError
            ? "无法连接后端服务，请确认应用已经启动。"
            : error.message;
        showError(message || "加载评估结果失败。");
    } finally {
        setLoading(false);
    }
}

form.addEventListener("submit", event => {
    event.preventDefault();
    loadEvaluation(taskInput.value.trim());
});

missOnly.addEventListener("change", renderCases);

const initialId = new URLSearchParams(location.search).get("id");
if (initialId) {
    taskInput.value = initialId;
    loadEvaluation(initialId);
}
