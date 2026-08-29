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
        const response = await fetch(
            `/api/research/gold/backtests/${encodeURIComponent(id)}/evaluation`,
            {headers: {Accept: "application/json"}}
        );
        if (!response.ok) {
            throw new Error(await readError(response));
        }
        render(await response.json(), id);
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

const initialId = new URLSearchParams(location.search).get("id");
if (initialId) {
    taskInput.value = initialId;
    loadEvaluation(initialId);
}
