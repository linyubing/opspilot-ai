const stateNames = {
    FRESH: "新鲜",
    STALE: "过期",
    UNKNOWN: "未知"
};

const itemNames = {
    gold: "黄金价格",
    realRate: "实际利率",
    dollarIndex: "广义美元指数（不是DXY）"
};

function text(id, value) {
    document.getElementById(id).textContent = value ?? "-";
}

function formatTime(value) {
    return value ? new Date(value).toLocaleString("zh-CN", {timeZone: "Asia/Shanghai"}) : "-";
}

function renderStatus(data) {
    text("overall", stateNames[data.overall] || data.overall);
    text("analysisDate", data.analysisDate);
    text("generatedAt", formatTime(data.generatedAt));
    text("summary", data.overall === "FRESH" ? "快照核心数据在允许时效内，不代表预测已通过验收" : "快照核心数据过期或缺失，不能借用仓库日期冒充新鲜");

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

// 三个来源独立查询：一个来源失败不能遮住其他真实结果，也不能回填旧快照。
async function loadWarehouse() {
    text("warehouseStatus", "正在读取仓库...");
    const sources = [
        {name: "黄金确认日收盘（非实时）", url: "/api/market-data/gold/daily-bars/latest", gold: true},
        {name: "实际利率 DFII10", url: "/api/macro-data/real-rate/latest"},
        {name: "广义美元 DTWEXBGS（不是DXY）", url: "/api/macro-data/dollar-index/latest"}
    ];
    const results = await Promise.all(sources.map(async source => {
        const article = document.createElement("article");
        const name = document.createElement("span");
        const value = document.createElement("strong");
        const detail = document.createElement("small");
        name.textContent = source.name;
        let available = false;
        try {
            const response = await fetch(source.url);
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const row = await response.json();
            const day = source.gold ? row.priceDate : row.observationDate;
            const number = source.gold ? row.close : row.value;
            if (!day || !Number.isFinite(number)) throw new Error("数据字段不完整");
            value.textContent = `${day} · ${number}`;
            const units = {percent: "百分比（%）", index: "指数点位"};
            const unit = source.gold ? "美元/金衡盎司" : units[row.unit] || row.unit || "单位未知";
            detail.textContent = `来源：${row.provider || "未知"}；单位：${unit}；采集时间（北京时间）：${formatTime(row.collectedAt)}`;
            available = true;
        } catch (error) {
            value.textContent = "读取失败或尚无确认数据";
            detail.textContent = error instanceof TypeError ? "无法连接服务" : error.message;
        }
        article.append(name, value, detail);
        return {article, available};
    }));
    document.getElementById("warehouseItems").replaceChildren(...results.map(item => item.article));
    text("warehouseStatus", results.every(item => item.available)
        ? "仓库读取完成；请对照观测日期，不代表实时行情或预测已更新"
        : "部分来源未能读取；其他来源结果仍保留，不使用假数据补齐");
}

async function refreshStatus() {
    const button = document.getElementById("refreshStatus");
    button.disabled = true;
    try { await Promise.all([loadStatus(), loadWarehouse()]); }
    finally { button.disabled = false; }
}

document.getElementById("refreshStatus").addEventListener("click", refreshStatus);
refreshStatus();
