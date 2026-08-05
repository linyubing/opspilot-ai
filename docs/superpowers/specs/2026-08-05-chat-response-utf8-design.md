# 对话接口 UTF-8 响应兼容设计

## 背景

`POST /api/chat` 已经能够调用智谱 `glm-4.7` 并返回中文内容，但 Windows PowerShell 5.1 在响应未显式声明字符集时会错误解码中文，导致终端显示乱码。

## 目标

- 让 `/api/chat` 的响应头明确包含 UTF-8 字符集。
- 保持现有请求格式、响应 JSON 结构和业务逻辑不变。
- 用自动化测试防止响应编码配置回退。

## 方案

在 `ChatController` 的 `@PostMapping` 上显式声明响应媒体类型为 `application/json;charset=UTF-8`。Spring MVC 仍由 Jackson 生成 JSON，只改变响应头中的字符集声明。

测试在 `ChatControllerTests` 中验证：

- 正常对话请求仍返回 HTTP 200。
- 响应内容类型与 UTF-8 兼容。
- 原有中文响应内容断言继续通过。

## 非目标

- 不改变 `ChatResponse` 字段。
- 不增加新的接口。
- 不修改智谱 API 配置。
- 不处理流式响应。

## 验收标准

1. 控制器测试先因缺少显式 UTF-8 声明而失败。
2. 添加最小控制器配置后测试通过。
3. 全部 Maven 测试通过。
4. Windows PowerShell 5.1 直接调用 `/api/chat` 时能正确显示中文。
