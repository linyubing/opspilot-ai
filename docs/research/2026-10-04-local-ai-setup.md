# 本地 Qwen 接通与验证

## 范围

本次完成本地模型运行和项目聊天接入，不是黄金预测模型晋级，也不是准确率实验。
仅新增可选的 `local-ai` 配置；默认智谱配置、提示词版本和历史记录不修改。
启用本地配置时，预测和解读的模型名为 `qwen3.5:9b`，避免把新结果记作 GLM。
行情 API 的额度、数据时效、发布截止线和金融安全校验仍然有效。

## 当前环境

- 显卡：NVIDIA GeForce RTX 5070 Ti，16303 MiB 显存。
- 内存：约 64 GB。
- Ollama：0.33.3，程序位于 `D:\workFile\ollama\app`。
- 模型目录：`D:\workFile\ollama\models`。
- 本地服务：`http://127.0.0.1:11435`，只监听回环地址。
- 聊天模型：`qwen3.5:9b`，Q4_K_M，下载约 6.6 GB。
- 本次模型摘要：`6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7`。
- 向量模型：已有 `nomic-embed-text`，同一服务可见。

旧的 11434 服务未继承 D 盘环境变量，所以本次使用独立 11435 服务。
已删除本次误落到 C 盘的 Qwen 未完成下载残片，不删除其他模型或用户文件。
模型标签可被供应商更新；本次摘要不代表以后同名标签永远相同。

## 参数与截断处理

上下文 8192，最大输出 2048，温度 0，不自动拉取模型。
`LocalAiConfiguration` 仅在 `local-ai` 下为 ChatClient 关闭额外思考输出。
不在全局强制 JSON，因为普通聊天需要文本；结构化服务仍按各自提示词及解析器工作。

默认开启思考的故障已实跑复现：输出 2048 token、`done_reason=length`、
思考文本 7352 字符、正文 0 字符。不能把这种响应当作成功的结构化结果。
关闭思考是运行参数选择，不是黄金准确率提升的证明。

## 验证证据

- 中文回复成功，首次包含冷启动约 40.62 秒。
- `/api/ps` 显示模型全部加载到显存，`size_vram == size`，上下文 8192。
- 使用已有真实快照 `26d256f3-7614-4683-9bb5-c78162a1453b` 的冻结提示词调用。
- 原提示词 SHA-256：`6c23e30aa82a088ecb984c0f5e782015d7276821125a941bf052de3073da933e`。
- 原生接口关闭思考、启用 JSON 格式时，热调用约 2.18 秒，246 token，正常结束并返回三字段 JSON。
- 这次输出中出现未由输入证明的“高位”“获利了结”措辞；仅验证接通和结构，不认可其因果解释。
- 未据此创建正式预测、回测成绩或晋级记录，不宣称其预测准确率。
- 首轮配置测试：4 项，3 项预期行为失败，0 错误；补配置后定向 19 项全部通过。
- 思考开关回归先出现预期失败，之后最终完整回归：869 项，0 失败，0 错误，3 跳过。
- Windows 中运行中的 JAR 导致首次重打包失败；仅停止本次自己启动的进程后，重打包成功。
- 最终项目实跑：`get /actuator/health` 为 `UP`；`post /api/chat` 中文问候返回 HTTP 200。
- 用同一冻结真实提示词调用项目 `post /api/chat`，HTTP 200，约 2.24 秒，完整 JSON 可解析；
  这次未强制全局 JSON 格式，验证了项目实际 ChatClient 链路。输出仍包含上述未证明的表述。
- 本次后台项目进程编号 27348，Ollama 11435 服务进程编号 8880；这些编号只适用于本次运行。

## 下次启动

如果 11435 服务已经运行，不要重复启动。关闭窗口或重启机器后，需要重新启动服务。
在单独的 PowerShell 窗口运行：

```powershell
$env:OLLAMA_MODELS = 'D:\workFile\ollama\models'
$env:OLLAMA_HOST = '127.0.0.1:11435'
& 'D:\workFile\ollama\app\ollama.exe' serve
```

再在另一窗口启动项目，已有数据库密码环境变量需要保留；无需智谱付费调用：

```powershell
Set-Location 'D:\workFile\demo-ai\backend'
java '-Dfile.encoding=UTF-8' -jar .\target\backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local-ai
```

源码开发可使用：

```powershell
Set-Location 'D:\workFile\demo-ai\backend'
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local-ai'
```

不启用 `local-ai` 即恢复原来的智谱默认配置。
不要向 C 盘旧服务运行不带正确 `OLLAMA_HOST` 的拉取命令。
不要同时在相同 8080 端口启动两个项目实例，也不要结束不属于本次工作的进程。

## 查看与验证

- 项目页面：`http://localhost:8080/forecast.html`。
- 健康接口：`http://localhost:8080/actuator/health`。
- Ollama 模型清单：`http://127.0.0.1:11435/api/tags`。
- 项目真实聊天入口：`post /api/chat`，请求字段 `message`，响应字段 `content`。

已保存的 GLM 预测仍是历史记录；页面不会因更换运行模型自动产生新的预测。
本地模型本身不会获取最新行情，仍需要真实来源数据满足现有校验。
确认结构化输出不等于确认事实正确，更不等于确认可用于买卖决策。
