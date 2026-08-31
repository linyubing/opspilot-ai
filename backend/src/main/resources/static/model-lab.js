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

buttons.forEach(button => button.addEventListener("click", () => load(button.dataset.horizon)));
load("FIVE_DAYS");
