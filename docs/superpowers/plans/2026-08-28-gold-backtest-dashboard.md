# 黄金回测可视化页面实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 在现有 Spring Boot 应用中增加一个本地黄金回测评估页面，直观展示中文结论、核心指标、混淆矩阵和分方向表现。

**架构：** 页面作为 Spring Boot 静态资源发布，原生 JavaScript 调用现有只读评估接口。HTML 负责语义结构，CSS 负责响应式视觉层级，JavaScript 负责输入校验、请求、格式化、渲染和错误状态；不引入新的前端构建链或服务端接口。

**技术栈：** Java 21、Spring Boot 3.5、MockMvc、HTML5、CSS3、原生 JavaScript、浏览器验收

**设计文档：** `docs/superpowers/specs/2026-08-28-gold-backtest-dashboard-design.md`

## 全局约束

- 页面由 `http://localhost:8080/backtest.html` 提供。
- 只调用 `get /api/research/gold/backtests/{id}/evaluation`，不创建或运行回测。
- 不增加 React、Node.js、Thymeleaf、WebJars 和图表库。
- 不调用大模型，不消耗 API 额度，不展示 API Key 或原始模型响应。
- 所有用户可见内容使用中文，比例保留两位小数，空值显示“暂无数据”。
- 页面结论不得转换成交易建议、仓位建议或收益承诺。
- 新增 Java 测试类必须有简短中文类级注释。
- 不修改或提交 `.env.example` 和未完成的文档生命周期文件。

---

### 任务 1：建立静态页面入口

**文件：**

- 创建：`backend/src/test/java/com/opspilot/ai/forecast/backtest/api/BacktestDashboardTests.java`
- 创建：`backend/src/main/resources/static/backtest.html`

**接口：**

- 输入：浏览器请求 `get /backtest.html`
- 输出：状态码 200、媒体类型 `text/html`

- [ ] **步骤 1：编写失败的静态页面测试**

```java
package com.opspilot.ai.forecast.backtest.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证黄金回测可视化页面能够由 Spring Boot 正常提供。 */
@SpringBootTest
@AutoConfigureMockMvc
class BacktestDashboardTests {

    @Autowired
    private MockMvc mvc;

    @Test
    void opensDashboard() throws Exception {
        mvc.perform(get("/backtest.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
    }
}
```

- [ ] **步骤 2：运行测试并确认红灯**

运行：

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd -Dtest=BacktestDashboardTests test
```

预期：测试返回 404，说明页面入口尚不存在。

- [ ] **步骤 3：创建最小 HTML 页面**

```html
<!doctype html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>黄金回测评估</title>
    <link rel="stylesheet" href="/backtest.css">
</head>
<body>
<main id="app">
    <h1>黄金回测评估</h1>
</main>
<script src="/backtest.js" defer></script>
</body>
</html>
```

- [ ] **步骤 4：重复测试并确认绿灯**

运行：`.\mvnw.cmd -Dtest=BacktestDashboardTests test`

预期：`Tests run: 1, Failures: 0, Errors: 0` 和 `BUILD SUCCESS`。

---

### 任务 2：实现页面结构和视觉样式

**文件：**

- 修改：`backend/src/main/resources/static/backtest.html`
- 创建：`backend/src/main/resources/static/backtest.css`

**接口：**

- 提供固定 DOM 标识：`taskId`、`loadButton`、`status`、`result`、`error`
- 为 JavaScript 提供带 `data-field` 的指标位置和带 `data-matrix` 的矩阵单元格

- [ ] **步骤 1：在 HTML 中建立完整语义结构**

页面依次包含查询区、隐藏的错误提示、隐藏的结果区、结论卡片、六个指标卡片、混淆矩阵和三个方向卡片。关键结构如下：

```html
<form id="queryForm" class="query-panel">
    <label for="taskId">回测任务 ID</label>
    <div class="query-row">
        <input id="taskId" name="taskId" autocomplete="off"
               placeholder="例如：171b3874-54f9-477d-a3d9-5aff4bfbffcb">
        <button id="loadButton" type="submit">加载评估</button>
    </div>
    <p id="status" aria-live="polite">请输入已经完成的回测任务 ID。</p>
</form>

<section id="error" class="message error" hidden></section>
<section id="result" hidden>
    <article class="conclusion-card">
        <span id="conclusionLevel"></span>
        <h2 id="conclusionSummary"></h2>
    </article>
    <section id="metrics" class="metric-grid"></section>
    <table class="matrix-table"></table>
    <section id="directions" class="direction-grid"></section>
</section>
```

- [ ] **步骤 2：添加响应式 CSS**

使用 CSS 变量统一颜色，卡片采用圆角、浅边框和克制阴影；桌面指标为三列，窄屏改为单列：

```css
:root {
    --bg: #f7f5ef;
    --surface: #ffffff;
    --text: #26231d;
    --muted: #746f64;
    --gold: #a87920;
    --border: #e6dfd0;
    --up: #287a55;
    --flat: #6b7280;
    --down: #ad4949;
}

.metric-grid,
.direction-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 16px;
}

@media (max-width: 720px) {
    .metric-grid,
    .direction-grid {
        grid-template-columns: 1fr;
    }
}
```

- [ ] **步骤 3：检查静态资源入口测试**

运行：`.\mvnw.cmd -Dtest=BacktestDashboardTests test`

预期：页面仍返回 200 和 HTML 媒体类型。

---

### 任务 3：实现查询、格式化和渲染

**文件：**

- 创建：`backend/src/main/resources/static/backtest.js`

**接口：**

- 消费：`get /api/research/gold/backtests/{id}/evaluation`
- 公开页面行为：提交表单加载、URL 参数自动加载、中文错误、重复请求锁定
- 关键函数：`loadEvaluation(id)`、`render(data)`、`formatPercent(value)`、`showError(message)`

- [ ] **步骤 1：实现输入校验和百分比格式化**

```javascript
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function formatPercent(value) {
    if (value === null || value === undefined || Number.isNaN(Number(value))) {
        return "暂无数据";
    }
    return `${(Number(value) * 100).toFixed(2)}%`;
}
```

- [ ] **步骤 2：实现同源接口请求**

```javascript
async function loadEvaluation(id) {
    if (!uuidPattern.test(id)) {
        showError("任务 ID 格式不正确。");
        return;
    }

    setLoading(true);
    try {
        const response = await fetch(
            `/api/research/gold/backtests/${encodeURIComponent(id)}/evaluation`,
            {headers: {Accept: "application/json"}}
        );
        if (!response.ok) {
            throw new Error(await readError(response));
        }
        render(await response.json());
        history.replaceState(null, "", `?id=${encodeURIComponent(id)}`);
    } catch (error) {
        showError(error.message || "无法连接后端服务，请确认应用已经启动。");
    } finally {
        setLoading(false);
    }
}
```

- [ ] **步骤 3：实现安全渲染**

所有文本通过 `textContent` 写入，不使用 `innerHTML` 注入接口数据。`render(data)` 必须：

- 翻译五种 `conclusion.level`。
- 将六个指标写入对应位置。
- 按“真实方向为行、预测方向为列”写入九个矩阵单元格。
- 写入三个方向的样本数、命中数和准确率。
- 隐藏错误区并显示结果区。

```javascript
function setText(id, value) {
    document.getElementById(id).textContent = value ?? "暂无数据";
}
```

- [ ] **步骤 4：实现表单和 URL 自动加载**

```javascript
document.getElementById("queryForm").addEventListener("submit", event => {
    event.preventDefault();
    loadEvaluation(document.getElementById("taskId").value.trim());
});

const initialId = new URLSearchParams(location.search).get("id");
if (initialId) {
    document.getElementById("taskId").value = initialId;
    loadEvaluation(initialId);
}
```

---

### 任务 4：浏览器端到端验收

**文件：**

- 验收：`backend/src/main/resources/static/backtest.html`
- 验收：`backend/src/main/resources/static/backtest.css`
- 验收：`backend/src/main/resources/static/backtest.js`

**接口：**

- 输入：一个已经完成的真实回测任务 ID
- 输出：浏览器可见的中文结论、指标、矩阵和方向表现

- [ ] **步骤 1：启动本地应用**

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd spring-boot:run
```

等待日志出现应用启动完成和端口 8080。

- [ ] **步骤 2：在浏览器打开页面**

打开：`http://localhost:8080/backtest.html`

验证页面标题、输入框、加载按钮和初始提示均可见。

- [ ] **步骤 3：加载真实回测结果**

输入已完成任务 ID，验证：

- 中文结论与接口 `conclusion` 一致。
- 六个指标显示为两位小数百分比。
- 混淆矩阵九个数字与接口一致。
- 三个方向卡片样本数、命中数、准确率与接口一致。
- 浏览器控制台无 JavaScript 错误。

- [ ] **步骤 4：验证错误和窄屏状态**

- 输入非法 ID，必须显示“任务 ID 格式不正确”。
- 将视口缩小到 390 像素宽，页面不能横向溢出，指标和方向卡片改为单列。

---

### 任务 5：完整回归、提交和推送

**文件：**

- 提交任务 1 至任务 4 创建和修改的页面、样式、脚本与测试文件
- 不提交用户已有的其他未暂存文件

**接口：**

- 输出：通过测试并与 GitHub `master` 同步的页面功能

- [ ] **步骤 1：运行定向测试**

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd -Dtest=BacktestDashboardTests test
```

预期：`BUILD SUCCESS`。

- [ ] **步骤 2：运行完整非 Live 回归**

```powershell
.\mvnw.cmd "-Dtest=**/*Tests,!**/*LiveTests,!**/DocumentLifecycleServiceTests" test
```

预期：全部已纳入测试通过；排除真实外部接口测试和用户尚未完成的文档生命周期测试。

- [ ] **步骤 3：只暂存本功能文件**

```powershell
git add -- backend/src/main/resources/static/backtest.html `
    backend/src/main/resources/static/backtest.css `
    backend/src/main/resources/static/backtest.js `
    backend/src/test/java/com/opspilot/ai/forecast/backtest/api/BacktestDashboardTests.java
```

- [ ] **步骤 4：提交并推送**

```powershell
git commit -m "feat: 增加黄金回测可视化页面"
git push origin master
```

- [ ] **步骤 5：确认同步状态**

确认 `git rev-parse HEAD` 与 `git rev-parse origin/master` 一致，并报告仍未暂存的用户文件。
