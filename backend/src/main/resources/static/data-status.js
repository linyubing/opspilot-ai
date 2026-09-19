const stateNames = {
    FRESH: "新鲜",
    STALE: "过期",
    UNKNOWN: "未知"
};

const itemNames = {
    gold: "黄金价格",
    realRate: "实际利率",
    dollarIndex: "美元指数"
};

function text(id, value) {
    document.getElementById(id).textContent = value ?? "-";
}

function formatTime(value) {
    return value ? new Date(value).toLocaleString("zh-CN") : "-";
}

function renderStatus(data) {
    text("overall", stateNames[data.overall] || data.overall);
    text("analysisDate", data.analysisDate);
    text("generatedAt", formatTime(data.generatedAt));
    text("summary", data.overall === "FRESH" ? "核心数据可用" : "需要刷新核心数据");

    const items = document.getElementById("items");
    items.replaceChildren(...(data.items || []).map(item => {
        const article = document.createElement("article");
        const name = document.createElement("span");
        const date = document.createElement("strong");
        const detail = document.createElement("small");
        const state = stateNames[item.state] || item.state;

        name.textContent = `${itemNames[item.code] || item.name}：${state}`;
        date.textContent = item.observationDate || "-";
        detail.textContent = item.detail || "";
        article.append(name, date, detail);
        return article;
    }));
}

async function loadStatus() {
    const status = document.getElementById("status");
    const errorBox = document.getElementById("errorBox");
    status.textContent = "正在读取数据状态...";
    errorBox.hidden = true;

    try {
        const response = await fetch("/api/research/gold/data-status");
        if (!response.ok) {
            throw new Error(`读取失败：HTTP ${response.status}`);
        }

        renderStatus(await response.json());
        status.textContent = "数据状态已更新";
    } catch (error) {
        status.textContent = "读取失败";
        errorBox.textContent = error.message;
        errorBox.hidden = false;
    }
}

loadStatus();
