const statusText = document.querySelector("#status");
const errorBox = document.querySelector("#error");
const result = document.querySelector("#result");
const horizonButtons = [...document.querySelectorAll("[data-horizon]")];
const profileButtons = [...document.querySelectorAll("[data-profile]")];

const horizonNames = {
    NEXT_DAY: "1 个交易日",
    FIVE_DAYS: "5 个交易日",
    TWENTY_DAYS: "20 个交易日"
};

const profileNames = {
    BASE_16: "BASE_16（16 个基础特征）",
    OHLC_20: "OHLC_20（20 个 OHLC 技术特征）",
    ALL_36: "ALL_36（全部 36 个特征）"
};

const percent = value => value == null ? "无法计算" : `${(Number(value) * 100).toFixed(2)}%`;
const number = value => value == null ? "无法计算" : Number(value).toFixed(4);
const improvement = value => value == null ? "无数据" : `${(Number(value) * 100).toFixed(2)}%`;

// 未认识的策略不能推定为已修正；历史输入可信也不代表模型已经预测准确。
function policyName(policy) {
    if (policy === "fred-known-before-day-v1") return "历史版本：前一日已知";
    if (policy === "legacy-latest-version") return "旧口径：未证明当时可得";
    return "口径未知：不可直接比较";
}

function fillRecalls(id, values = {}) {
    document.querySelector(id).innerHTML = [
        ["上涨召回率", values.BULLISH],
        ["中性召回率", values.NEUTRAL],
        ["下跌召回率", values.BEARISH]
    ].map(([label, value]) => `<span>${label}<br><strong>${percent(value)}</strong></span>`).join("");
}

function fillMetrics(prefix, data) {
    const metric = data || {};
    const full = metric.paired?.all;
    const baseline = metric.paired?.baselineOnSignals;
    const noSignal = metric.coveredCount === 0;
    document.querySelector(`#${prefix}Accuracy`).textContent = noSignal ? "无信号" : percent(metric.accuracy);
    document.querySelector(`#${prefix}Balanced`).textContent = noSignal ? "无信号" : percent(metric.balancedAccuracy);
    document.querySelector(`#${prefix}Coverage`).textContent = percent(metric.coverage);
    document.querySelector(`#${prefix}Brier`).textContent = number(metric.brierScore);
    document.querySelector(`#${prefix}FullAccuracy`).textContent = full ? percent(full.accuracy) : "缺少配对证据";
    document.querySelector(`#${prefix}FullBalanced`).textContent = full ? percent(full.balancedAccuracy) : "缺少配对证据";
    document.querySelector(`#${prefix}Baseline`).textContent = !metric.paired ? "缺少配对证据"
        : noSignal ? "无信号" : percent(baseline?.accuracy);
    document.querySelector(`#${prefix}Population`).textContent =
        `信号 ${metric.coveredCount ?? "未知"} / 全部 ${metric.sampleCount ?? "未知"}；下方为信号子集召回率`;
    const loss = document.querySelector(`#${prefix}LogLoss`);
    if (loss) loss.textContent = number(metric.logLoss);
    fillRecalls(`#${prefix}Recalls`, metric.recalls || {});
}

let currentHorizon = "FIVE_DAYS";
let currentProfile = "ALL_36";
// 统一视图代次：迟到的预览或对比响应不得覆盖用户新选中的历史记录。
let viewSeq = 0;

function selectContext(horizon, profile) {
    currentHorizon = horizon;
    currentProfile = profile;
    horizonButtons.forEach(button => button.classList.toggle("active", button.dataset.horizon === horizon));
    profileButtons.forEach(button => button.classList.toggle("active", button.dataset.profile === profile));
}

function render(data) {
    document.querySelector("#metricSource").textContent = "统计实验预览（未保存），不是正式大模型预测成绩";
    document.querySelector("#candidateResult").hidden = true;
    document.querySelector("#relativeMajority").textContent = "未进行同批比较";
    document.querySelector("#relativeBase16").textContent = "未进行同批比较";
    document.querySelector("#activeProfile").textContent =
        profileNames[data.featureProfile] || data.featureProfile || "ALL_36";
    document.querySelector("#trainStart").textContent = data.trainStart;
    document.querySelector("#validationRange").textContent = `${data.validationStart} 至 ${data.validationEnd}`;
    document.querySelector("#validationSamples").textContent = `${data.validationSamples} 条`;
    document.querySelector("#refitCount").textContent = `${data.refitCount} 次，每 ${data.refitEvery} 条一次`;
    document.querySelector("#holdoutRange").textContent =
        `${data.finalHoldout.samples} 条，${data.finalHoldout.start} 至 ${data.finalHoldout.end}`;
    fillMetrics("majority", data.majority);
    fillMetrics("logistic", data.logistic);
    fillMetrics("xgboost", data.xgboost);

    result.hidden = false;
}

async function runComparison() {
    const ticket = ++viewSeq;
    const button = document.querySelector("#runComparison");
    const candidateBox = document.querySelector("#candidateResult");
    button.disabled = true;
    errorBox.hidden = true;
    document.querySelector("#experimentDetail").hidden = true;
    candidateBox.hidden = true;
    statusText.textContent = `正在运行 ${horizonNames[currentHorizon]} 的三组真实对比实验...`;
    try {
        const response = await fetch(
            `/api/research/gold/model-experiments/compare?horizon=${currentHorizon}`,
            { method: "POST" }
        );
        const data = await response.json();
        if (ticket !== viewSeq) return;
        if (!response.ok) {
            throw new Error(data.message || "三组对比实验运行失败");
        }

        const selected = data.experiments.find(item => item.featureProfile === currentProfile)
            || data.experiments[0];
        document.querySelector("#activeProfile").textContent = profileNames[selected.featureProfile];
        document.querySelector("#trainStart").textContent = selected.trainStart;
        document.querySelector("#validationRange").textContent =
            `${selected.validationStart} 至 ${selected.validationEnd}`;
        document.querySelector("#validationSamples").textContent = `${selected.validationSamples} 条`;
        document.querySelector("#refitCount").textContent = "以批次参数为准";
        document.querySelector("#holdoutRange").textContent =
            `${selected.holdoutSamples} 条，${selected.holdoutStart} 至 ${selected.holdoutEnd}`;
        fillMetrics("majority", selected.majority);
        fillMetrics("logistic", selected.logistic);
        fillMetrics("xgboost", selected.xgboost);
        document.querySelector("#metricSource").textContent = `统计实验批次 ${data.comparisonId}，实验 ${selected.id}`;
        document.querySelector("#relativeMajority").textContent = "见完整样本与同日期基线";
        document.querySelector("#relativeBase16").textContent = "见实验历史";

        document.querySelector("#candidateTitle").textContent = data.stage8Candidate
            ? "阶段8候选通过"
            : "阶段8暂无候选";
        document.querySelector("#candidateProfile").textContent = data.candidateProfile || "未通过";
        document.querySelector("#candidateReason").textContent = data.candidateReason;
        candidateBox.hidden = false;
        result.hidden = false;
        statusText.textContent = `对比完成，批次 ID：${data.comparisonId}`;
        loadHistory();
    } catch (error) {
        if (ticket !== viewSeq) return;
        errorBox.textContent = error.message;
        errorBox.hidden = false;
        statusText.textContent = "三组对比实验未完成";
    } finally {
        button.disabled = false;
    }
}

async function load(horizon, profile) {
    const ticket = ++viewSeq;
    selectContext(horizon, profile);
    errorBox.hidden = true;
    result.hidden = true;
    document.querySelector("#experimentDetail").hidden = true;
    horizonButtons.forEach(button => {
        button.classList.toggle("active", button.dataset.horizon === horizon);
        button.disabled = true;
    });
    profileButtons.forEach(button => {
        button.classList.toggle("active", button.dataset.profile === profile);
        button.disabled = true;
    });
    statusText.textContent = `正在构建真实历史样本，运行 ${horizonNames[horizon]} ${profileNames[profile]} 滚动验证...`;
    try {
        const response = await fetch(
            `/api/research/gold/model-experiments?horizon=${horizon}&featureProfile=${profile}`
        );
        const data = await response.json();
        if (ticket !== viewSeq) return;
        if (!response.ok) {
            throw new Error(data.message || "模型实验运行失败");
        }
        render(data);
        statusText.textContent = `开发验证区间实验已完成：${horizonNames[horizon]} / ${profileNames[profile]}`;
    } catch (error) {
        if (ticket !== viewSeq) return;
        errorBox.textContent = error.message;
        errorBox.hidden = false;
        statusText.textContent = "模型实验未完成";
    } finally {
        horizonButtons.forEach(button => button.disabled = false);
        profileButtons.forEach(button => button.disabled = false);
    }
}

async function saveExperiment() {
    const ticket = ++viewSeq;
    const saveBtn = document.querySelector("#saveExperiment");
    const saveStatus = document.querySelector("#saveStatus");
    saveBtn.disabled = true;
    saveStatus.textContent = "正在保存实验...";
    saveStatus.hidden = false;
    try {
        const response = await fetch(
            `/api/research/gold/model-experiments?horizon=${currentHorizon}&featureProfile=${currentProfile}`,
            { method: "POST" }
        );
        const data = await response.json();
        if (!response.ok) {
            throw new Error(data.message || "保存实验失败");
        }
        saveStatus.textContent = `实验已保存，ID: ${data.id}`;
        loadHistory();
        if (ticket === viewSeq) showDetail(data.id);
    } catch (error) {
        saveStatus.textContent = `保存失败: ${error.message}`;
    } finally {
        saveBtn.disabled = false;
    }
}

function formatImprovement(value) {
    if (value == null) return "-";
    const num = Number(value);
    const sign = num > 0 ? "+" : "";
    return `${sign}${(num * 100).toFixed(2)}%`;
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
                    <span class="exp-profile">${exp.featureProfile || "ALL_36"}</span>
                    <span class="exp-status">${exp.status}</span>
                    <span class="exp-policy">${policyName(exp.dataPolicy)}</span>
                    <span class="exp-metrics">
                        逻辑回归信号准确率 ${exp.coverage === 0 ? "无信号" : percent(exp.logisticAccuracy)}
                        | 信号平衡准确率 ${percent(exp.balancedAccuracy)}
                        | 完整样本平衡准确率 ${exp.fullBalancedAccuracy == null ? "缺少配对证据" : percent(exp.fullBalancedAccuracy)}
                        ${exp.relativeMajorityImprovement != null
                            ? ` | 基线提升 ${formatImprovement(exp.relativeMajorityImprovement)}`
                            : ""}
                    </span>
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
    const ticket = ++viewSeq;
    const detail = document.querySelector("#experimentDetail");
    detail.hidden = false;
    try {
        const response = await fetch(`/api/research/gold/model-experiments/${id}`);
        const data = await response.json();
        if (ticket !== viewSeq) return;
        if (!response.ok) {
            throw new Error(data.message || "加载详情失败");
        }
        // 历史详情必须切换成绩卡，不能继续展示上一条预览或另一个模型的成绩。
        selectContext(data.horizon, data.featureProfile);
        errorBox.hidden = true;
        statusText.textContent = `已加载历史统计实验：${horizonNames[data.horizon] || data.horizon} / ${data.featureProfile}`;
        for (const type of ["majority", "logistic", "xgboost"]) fillMetrics(type, data[type]);
        document.querySelector("#metricSource").textContent =
            `统计实验 ${data.id}｜${horizonNames[data.horizon] || data.horizon}｜Git ${data.gitCommit || "未知"}｜评估 ${data.parameters?.evaluationVersion || "旧版：缺少配对证据"}｜逻辑回归 ${data.parameters?.logisticVersion || "未记录"}`;
        document.querySelector("#activeProfile").textContent = profileNames[data.featureProfile] || data.featureProfile;
        document.querySelector("#trainStart").textContent = data.trainStart || "未知";
        document.querySelector("#validationRange").textContent = `${data.validationStart} 至 ${data.validationEnd}`;
        document.querySelector("#validationSamples").textContent = `${data.validationSamples ?? "未知"} 条`;
        document.querySelector("#refitCount").textContent = data.parameters?.refitEvery ? `每 ${data.parameters.refitEvery} 条一次` : "未记录";
        document.querySelector("#holdoutRange").textContent = `${data.holdoutSamples ?? "未知"} 条，${data.holdoutStart} 至 ${data.holdoutEnd}`;
        document.querySelector("#candidateResult").hidden = true;
        document.querySelector("#relativeMajority").textContent = "见完整样本与同日期基线";
        document.querySelector("#relativeBase16").textContent = "见同批次实验历史";
        result.hidden = false;
        document.querySelector("#detailId").textContent = data.id;
        document.querySelector("#detailStatus").textContent = data.status;
        document.querySelector("#detailFeatureProfile").textContent = data.featureProfile || "ALL_36";
        document.querySelector("#detailDataPolicy").textContent = policyName(data.dataPolicy);
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
        const params = data.parameters || {};
        document.querySelector("#detailModels").textContent =
            `多数类：${params.majorityTrainer || "未记录"}\n逻辑回归：${params.logisticVersion || params.logisticTrainer || "未记录"}\nXGBoost：${JSON.stringify(params.xgboostTrainer || "未记录")}\n评估版本：${params.evaluationVersion || "未记录，不能补造配对成绩"}`;
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
        if (ticket !== viewSeq) return;
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
document.querySelector("#runComparison").addEventListener("click", runComparison);
document.querySelector("#closeDetail").addEventListener("click", () => {
    document.querySelector("#experimentDetail").hidden = true;
});

horizonButtons.forEach(button => button.addEventListener("click", () => load(button.dataset.horizon, currentProfile)));
profileButtons.forEach(button => button.addEventListener("click", () => load(currentHorizon, button.dataset.profile)));
load("FIVE_DAYS", "ALL_36");
loadHistory();
