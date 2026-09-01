const statusText = document.querySelector("#status");
const errorBox = document.querySelector("#error");
const result = document.querySelector("#result");
const buttons = [...document.querySelectorAll("[data-horizon]")];

const horizonNames = {
    NEXT_DAY: "1 个交易日",
    FIVE_DAYS: "5 个交易日",
    TWENTY_DAYS: "20 个交易日"
};

const percent = value => value == null ? "无法计算" : `${(Number(value) * 100).toFixed(2)}%`;
const number = value => value == null ? "无法计算" : Number(value).toFixed(4);

function fillRecalls(id, values = {}) {
    document.querySelector(id).innerHTML = [
        ["上涨召回率", values.BULLISH],
        ["中性召回率", values.NEUTRAL],
        ["下跌召回率", values.BEARISH]
    ].map(([label, value]) => `<span>${label}<br><strong>${percent(value)}</strong></span>`).join("");
}

function fillMetrics(prefix, data) {
    document.querySelector(`#${prefix}Accuracy`).textContent = percent(data.accuracy);
    document.querySelector(`#${prefix}Balanced`).textContent = percent(data.balancedAccuracy);
    document.querySelector(`#${prefix}Coverage`).textContent = percent(data.coverage);
    document.querySelector(`#${prefix}Brier`).textContent = number(data.brierScore);
    fillRecalls(`#${prefix}Recalls`, data.recalls);
}

let currentHorizon = "FIVE_DAYS";

function render(data) {
    document.querySelector("#trainStart").textContent = data.trainStart;
    document.querySelector("#validationRange").textContent = `${data.validationStart} 至 ${data.validationEnd}`;
    document.querySelector("#validationSamples").textContent = `${data.validationSamples} 条`;
    document.querySelector("#refitCount").textContent = `${data.refitCount} 次，每 ${data.refitEvery} 条一次`;
    document.querySelector("#holdoutRange").textContent =
        `${data.finalHoldout.samples} 条，${data.finalHoldout.start} 至 ${data.finalHoldout.end}`;
    fillMetrics("majority", data.majority);
    fillMetrics("logistic", data.logistic);
    result.hidden = false;
}

async function load(horizon) {
    currentHorizon = horizon;
    errorBox.hidden = true;
    result.hidden = true;
    buttons.forEach(button => {
        button.classList.toggle("active", button.dataset.horizon === horizon);
        button.disabled = true;
    });
    statusText.textContent = `正在构建真实历史样本，并运行 ${horizonNames[horizon]} 滚动验证...`;
    try {
        const response = await fetch(`/api/research/gold/model-experiments?horizon=${horizon}`);
        const data = await response.json();
        if (!response.ok) {
            throw new Error(data.message || "模型实验运行失败");
        }
        render(data);
        statusText.textContent = `开发验证区间实验已完成：${horizonNames[horizon]}`;
    } catch (error) {
        errorBox.textContent = error.message;
        errorBox.hidden = false;
        statusText.textContent = "模型实验未完成";
    } finally {
        buttons.forEach(button => button.disabled = false);
    }
}

async function saveExperiment() {
    const saveBtn = document.querySelector("#saveExperiment");
    const saveStatus = document.querySelector("#saveStatus");
    saveBtn.disabled = true;
    saveStatus.textContent = "正在保存实验...";
    saveStatus.hidden = false;
    try {
        const response = await fetch(`/api/research/gold/model-experiments?horizon=${currentHorizon}`, {
            method: "POST"
        });
        const data = await response.json();
        if (!response.ok) {
            throw new Error(data.message || "保存实验失败");
        }
        saveStatus.textContent = `实验已保存，ID: ${data.id}`;
        loadHistory();
        showDetail(data.id);
    } catch (error) {
        saveStatus.textContent = `保存失败: ${error.message}`;
    } finally {
        saveBtn.disabled = false;
    }
}

async function loadHistory() {
    const list = document.querySelector("#experimentList");
    try {
        const response = await fetch("/api/research/gold/model-experiments/history?limit=20");
        const experiments = await response.json();
        if (!response.ok) {
            throw new Error("加载历史失败");
        }
        list.innerHTML = experiments.length === 0
            ? "<li class='empty'>暂无实验记录</li>"
            : experiments.map(exp => `
                <li class="experiment-item" data-id="${exp.id}">
                    <span class="exp-horizon">${horizonNames[exp.horizon] || exp.horizon}</span>
                    <span class="exp-status">${exp.status}</span>
                    <span class="exp-hash">${exp.datasetHashPrefix}...</span>
                    <span class="exp-time">${formatTime(exp.createdAt)}</span>
                </li>
            `).join("");
        list.querySelectorAll(".experiment-item").forEach(item => {
            item.addEventListener("click", () => showDetail(item.dataset.id));
        });
    } catch (error) {
        list.innerHTML = `<li class="error">加载失败: ${error.message}</li>`;
    }
}

async function showDetail(id) {
    const detail = document.querySelector("#experimentDetail");
    detail.hidden = false;
    try {
        const response = await fetch(`/api/research/gold/model-experiments/${id}`);
        const data = await response.json();
        if (!response.ok) {
            throw new Error(data.message || "加载详情失败");
        }
        document.querySelector("#detailId").textContent = data.id;
        document.querySelector("#detailStatus").textContent = data.status;
        document.querySelector("#detailHash").textContent = data.datasetHash;
        document.querySelector("#detailFeatureVersion").textContent = data.featureVersion;
        document.querySelector("#detailLabelVersion").textContent = data.labelVersion;
        document.querySelector("#detailSplitVersion").textContent = data.splitVersion;
        document.querySelector("#detailDataRange").textContent = `${data.dataStart} 至 ${data.dataEnd}`;
        document.querySelector("#detailTrainStart").textContent = data.trainStart;
        document.querySelector("#detailValidationRange").textContent = `${data.validationStart} 至 ${data.validationEnd}`;
        document.querySelector("#detailHoldoutRange").textContent = `${data.holdoutStart} 至 ${data.holdoutEnd}`;
        document.querySelector("#detailValidationSamples").textContent = data.validationSamples;
        document.querySelector("#detailHoldoutSamples").textContent = data.holdoutSamples;
        document.querySelector("#detailGitCommit").textContent = data.gitCommit;
        document.querySelector("#detailCreatedAt").textContent = formatTime(data.createdAt);
        document.querySelector("#detailStartedAt").textContent = data.startedAt ? formatTime(data.startedAt) : "-";
        document.querySelector("#detailCompletedAt").textContent = data.completedAt ? formatTime(data.completedAt) : "-";
        
        const failureBox = document.querySelector("#detailFailure");
        if (data.failureMessage) {
            failureBox.hidden = false;
            document.querySelector("#detailFailureMessage").textContent = data.failureMessage;
        } else {
            failureBox.hidden = true;
        }
    } catch (error) {
        detail.hidden = true;
        alert(`加载详情失败: ${error.message}`);
    }
}

function formatTime(isoString) {
    if (!isoString) return "-";
    const date = new Date(isoString);
    return date.toLocaleString("zh-CN", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit"
    });
}

document.querySelector("#saveExperiment").addEventListener("click", saveExperiment);
document.querySelector("#closeDetail").addEventListener("click", () => {
    document.querySelector("#experimentDetail").hidden = true;
});

buttons.forEach(button => button.addEventListener("click", () => load(button.dataset.horizon)));
load("FIVE_DAYS");
loadHistory();
