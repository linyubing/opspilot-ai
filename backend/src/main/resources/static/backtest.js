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
const reviewButton = document.getElementById("reviewButton");
const reviewStatus = document.getElementById("reviewStatus");
const reviewError = document.getElementById("reviewError");
const reviewResult = document.getElementById("reviewResult");
const patternList = document.getElementById("patternList");
const riskList = document.getElementById("riskList");
const candidateInput = document.getElementById("candidateId");
const compareButton = document.getElementById("compareButton");
const compareStatus = document.getElementById("compareStatus");
const compareError = document.getElementById("compareError");
const compareResult = document.getElementById("compareResult");
const factorList = document.getElementById("factorList");
const horizonRows = document.getElementById("horizonRows");
const historyHorizonButton = document.getElementById("historyHorizonButton");
const historyHorizonStatus = document.getElementById("historyHorizonStatus");
const historyHorizonResult = document.getElementById("historyHorizonResult");
const historyHorizonRows = document.getElementById("historyHorizonRows");
let cases = [];
let activeId = null;
let reviewRequest = null;

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

const factorNames = {
    GOLD_MOMENTUM_20: "黄金 20 期动量",
    REAL_RATE: "实际利率",
    DOLLAR_INDEX: "美元指数"
};

function factorCard(item) {
    const card = document.createElement("article");
    card.className = "factor-card";
    const title = document.createElement("h3");
    title.textContent = factorNames[item.factor] || item.factor;
    const list = document.createElement("dl");
    list.append(
        reviewRow("总体准确率", formatPercent(item.accuracy)),
        reviewRow("方向覆盖率", formatPercent(item.coverage)),
        reviewRow("方向信号准确率", formatPercent(item.directionalAccuracy)),
        reviewRow(
            "信号分布",
            `涨 ${item.signals?.bullish || 0} / 中性 ${item.signals?.neutral || 0} / 跌 ${item.signals?.bearish || 0}`
        )
    );
    card.append(title, list);
    return card;
}

function renderFactors(data) {
    const factors = Array.isArray(data?.factors) ? data.factors : [];
    factorList.replaceChildren(...factors.map(factorCard));
}

function renderHorizons(data, target = horizonRows) {
    const rows = (data?.horizons || []).map(horizon => {
        const row = document.createElement("tr");
        const values = Object.fromEntries(
            (horizon.factors || []).map(item => [item.factor, item])
        );
        const period = document.createElement("th");
        period.scope = "row";
        period.textContent = `${horizon.sessions} 个交易日`;
        const count = document.createElement("td");
        count.textContent = formatCount(horizon.sampleCount);
        row.append(period, count);
        ["GOLD_MOMENTUM_20", "REAL_RATE", "DOLLAR_INDEX"].forEach(name => {
            const cell = document.createElement("td");
            cell.textContent = formatPercent(values[name]?.directionalAccuracy);
            row.append(cell);
        });
        return row;
    });
    target.replaceChildren(...rows);
}

async function loadHistoryHorizons() {
    historyHorizonButton.disabled = true;
    historyHorizonStatus.textContent = "正在用本地真实历史数据计算，不调用大模型……";
    try {
        const response = await fetch(
            "/api/research/gold/backtests/horizons/history?samples=120",
            {headers: {Accept: "application/json"}}
        );
        if (!response.ok) throw new Error(await readError(response));
        const data = await response.json();
        renderHorizons(data, historyHorizonRows);
        historyHorizonResult.hidden = false;
        historyHorizonStatus.textContent =
            `已从 ${formatCount(data.requestedSamples)} 条候选日期完成扩大样本诊断。`;
    } catch (error) {
        historyHorizonStatus.textContent = error.message || "扩大样本诊断失败。";
    } finally {
        historyHorizonButton.disabled = false;
    }
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

function reviewRow(label, value) {
    const wrapper = document.createElement("div");
    const term = document.createElement("dt");
    const detail = document.createElement("dd");
    term.textContent = label;
    detail.textContent = value || "暂无数据";
    wrapper.append(term, detail);
    return wrapper;
}

function patternCard(item) {
    const card = document.createElement("article");
    card.className = "pattern-card";
    const title = document.createElement("h3");
    title.textContent = item.category || "未分类错误";
    const list = document.createElement("dl");
    list.append(
        reviewRow("观察结果", item.observation),
        reviewRow("证据编号", (item.evidence || []).join("、")),
        reviewRow("改进假设", item.improvement),
        reviewRow("验证方法", item.validationMethod)
    );
    card.append(title, list);
    return card;
}

function renderReview(data) {
    const source = data.cached ? "内存缓存" : "本次调用模型";
    setText("reviewModel", `模型：${data.modelName || "未知"}｜来源：${source}`);
    setText("reviewSummary", data.summary || "暂无复盘摘要。");
    setText(
        "reviewSummaryEvidence",
        `摘要证据：${(data.summaryEvidence || []).join("、")}`
    );
    setText("reviewDisclaimer", data.disclaimer || "不构成投资建议");
    const patterns = Array.isArray(data.patterns) ? data.patterns : [];
    patternList.replaceChildren(...patterns.map(patternCard));
    const risks = Array.isArray(data.risks) ? data.risks : [];
    riskList.replaceChildren(...risks.map(item => {
        const li = document.createElement("li");
        const evidence = (item.evidence || []).join("、");
        li.textContent = `${item.description || "暂无说明"}（证据：${evidence}）`;
        return li;
    }));
    reviewResult.hidden = false;
}

async function loadReview() {
    if (!activeId) return;
    const reviewId = activeId;
    reviewRequest?.abort();
    const request = new AbortController();
    reviewRequest = request;
    reviewButton.disabled = true;
    reviewButton.textContent = "正在复盘…";
    reviewStatus.textContent = "大模型正在分析真实错误样本，请稍候…";
    reviewError.hidden = true;
    reviewResult.hidden = true;
    try {
        const response = await fetch(
            `/api/research/gold/backtests/${encodeURIComponent(reviewId)}/review`,
            {
                method: "POST",
                headers: {Accept: "application/json"},
                signal: request.signal
            }
        );
        if (!response.ok) {
            throw new Error(await readError(response));
        }
        const data = await response.json();
        if (activeId !== reviewId || reviewRequest !== request) return;
        renderReview(data);
        reviewStatus.textContent = "AI 复盘已生成，可结合逐日错误样本人工核验。";
    } catch (error) {
        if (error.name === "AbortError") return;
        if (activeId !== reviewId || reviewRequest !== request) return;
        reviewError.textContent = error.message || "生成 AI 复盘失败。";
        reviewError.hidden = false;
        reviewStatus.textContent = "AI 复盘生成失败。";
    } finally {
        if (reviewRequest === request) {
            reviewRequest = null;
            reviewButton.disabled = false;
            reviewButton.textContent = "重新生成 AI 复盘";
        }
    }
}

function renderComparison(data) {
    setText("baseAccuracy", formatPercent(data.baselineAccuracy));
    setText("candidateAccuracy", formatPercent(data.candidateAccuracy));
    setText("accuracyChange", formatSignedPercent(data.accuracyChange));
    setText("balancedChange", formatSignedPercent(data.balancedAccuracyChange));
    compareResult.hidden = false;
    compareStatus.textContent = `已比较 ${data.sampleCount} 个相同历史样本。`;
}

async function loadComparison() {
    const candidateId = candidateInput.value.trim();
    if (!activeId || !uuidPattern.test(candidateId)) {
        compareError.textContent = "请先加载基准任务，再输入正确的候选任务 ID。";
        compareError.hidden = false;
        return;
    }

    compareButton.disabled = true;
    compareButton.textContent = "正在比较…";
    compareError.hidden = true;
    compareResult.hidden = true;
    try {
        const query = new URLSearchParams({
            baselineId: activeId,
            candidateId
        });
        const response = await fetch(
            `/api/research/gold/backtests/compare?${query}`,
            {headers: {Accept: "application/json"}}
        );
        if (!response.ok) throw new Error(await readError(response));
        renderComparison(await response.json());
    } catch (error) {
        compareError.textContent = error.message || "对比回测失败。";
        compareError.hidden = false;
        compareStatus.textContent = "无法比较这两个回测任务。";
    } finally {
        compareButton.disabled = false;
        compareButton.textContent = "比较结果";
    }
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
    reviewRequest?.abort();
    reviewRequest = null;
    activeId = null;
    reviewButton.disabled = true;
    reviewStatus.textContent = "正在切换回测任务…";
    setLoading(true);
    try {
        const base = `/api/research/gold/backtests/${encodeURIComponent(id)}`;
        const [evaluationResponse, casesResponse, factorResponse, horizonResponse] = await Promise.all([
            fetch(`${base}/evaluation`, {headers: {Accept: "application/json"}}),
            fetch(`${base}/results?limit=120`, {headers: {Accept: "application/json"}}),
            fetch(`${base}/factors`, {headers: {Accept: "application/json"}}),
            fetch(`${base}/horizons`, {headers: {Accept: "application/json"}})
        ]);
        if (!evaluationResponse.ok) {
            throw new Error(await readError(evaluationResponse));
        }
        if (!casesResponse.ok) {
            throw new Error(await readError(casesResponse));
        }
        if (!factorResponse.ok) {
            throw new Error(await readError(factorResponse));
        }
        if (!horizonResponse.ok) {
            throw new Error(await readError(horizonResponse));
        }
        const [evaluation, loadedCases, factors, horizons] = await Promise.all([
            evaluationResponse.json(),
            casesResponse.json(),
            factorResponse.json(),
            horizonResponse.json()
        ]);
        cases = Array.isArray(loadedCases) ? loadedCases : [];
        activeId = id;
        compareResult.hidden = true;
        compareError.hidden = true;
        compareStatus.textContent = "请输入使用 v2 候选提示词的回测任务 ID。";
        reviewButton.disabled = false;
        reviewButton.textContent = "生成 AI 复盘";
        reviewStatus.textContent = "已加载真实回测数据，可手动生成 AI 复盘。";
        reviewError.hidden = true;
        reviewResult.hidden = true;
        missOnly.checked = false;
        render(evaluation, id);
        renderCases();
        renderFactors(factors);
        renderHorizons(horizons);
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
reviewButton.addEventListener("click", loadReview);
compareButton.addEventListener("click", loadComparison);
historyHorizonButton.addEventListener("click", loadHistoryHorizons);

const initialId = new URLSearchParams(location.search).get("id");
if (initialId) {
    taskInput.value = initialId;
    loadEvaluation(initialId);
}
