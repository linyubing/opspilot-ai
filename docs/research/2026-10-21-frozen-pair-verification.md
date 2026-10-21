# 冻结10月14日研究配对：来源口径不一致，拒绝评分

## 本轮问题与固定边界

验收已发布的10月14日研究配对，不创建新候选，不重跑LLM，不改收益阈值或悉尼07:00候选窗口，不重新选择更容易匹配的时段。沿用10月13日配对协议及独立频率基线协议。最终留出标签不读取。

此前“目标尚未结束”的等待条件现已解除；本次是实际尝试到期验收，而不是再次总结旧成绩。首次真实取数共3次，之后全部从同一冻结回执零请求复验。

## 实际证据

- 10月13日、14日原生日线分别HTTP200、各1行；UTC请求的小时线HTTP200、48行，覆盖10月12日20:00至10月14日19:00，每日24根、逐小时无缺口。
- 两天各自的开、高、低、收与候选窗口小时聚合均不一致（8项均为false）。不能为得到分数而改日期、偏移窗口或使用另一个基准价。
- 独立Java核验器调用生产`GoldSessionCheck`，两天均返回`OHLC_MISMATCH`、各24小时，与Node整数十进制检查一致。
- 小时响应未回显时区。记录为`UTC_REQUEST_NOT_ECHOED`：明确请求UTC，不冒充供应商回显或官方时段证明。若响应明确回显非UTC，验收器拒绝。
- 结果为`SOURCE_NOT_VERIFIED`，失败阶段`OHLC_AGGREGATION`；actual、收益、命中和概率评分全部保持null。两组原NO_SIGNAL仍各0/1覆盖；频率基线1/1覆盖，但同样不得评分。

原始行情只保留在Git忽略的`backend/target/oct21-event-pair-score.json.source.json`，不公开再分发。首次HTTP归档SHA256固定为`a78b45cc330438d43439cb14fcb11bf617b9d3ffe8349a36dc04105e9227632f`。

三份原始正文SHA256分别为：

- base：`e4b9d1a20c2b5766d8751d71f9f8643b5c3deb6983ca02338837e66993b12f8a`
- target：`6830cab86033a26e0c909e8d648035eb2ab71f33e59adf9c4597a3b1dee2cc78`
- hours：`a6a86766ef6fd7b310dbcfaca309e05c92ad05d58e6a1d36d89c870cbd23fbe0`

## 验收器修复

旧实现对`response.values`原地排序，使归档的解析副本顺序与原始正文不同。现改用数组副本，并允许以已锁定归档重放；重放不需要密钥、不请求网络，从raw正文重建解析副本，不信任旧排序副本。

独立审查指出“同文件正文和摘要自洽”不等于原HTTP来源，已增加首轮归档字节哈希锚点。即使只追加换行、JSON语义不变，也在来源阶段拒绝，不能生成已结算成绩。保留首次归档，不覆盖原冻结预测、基线或正式数据库记录。

可复现命令（从仓库根目录运行，需本机保留该真实回执）：

```powershell
node docs/research/probes/ScoreEventPairChecks.mjs
node docs/research/probes/ScoreEventPairReceiptChecks.mjs backend/target/oct21-event-pair-score.json.source.json
node docs/research/probes/ScoreEventPair.mjs docs/research/2026-10-13-event-pair-input.json docs/research/2026-10-13-event-pair-result.json backend/target/event-pair-new-replay.json docs/research/2026-10-13-forward-prior.json backend/target/oct21-event-pair-score.json.source.json
```

输出文件必须不存在；不要删除旧输出以制造“首次”运行。若回执丢失，该轮原证据不可复验，不能把新下载的数据冒称旧回执。

## 路线决策

本轮不支持“新增CPI共识提高方向准确率”，也不支持基线或模型命中。不是市场尚未结束，而是冻结合同的日线/小时口径未通过。停止在这一目标上重复生成、换窗口或换分数。

后续若要建立新预测合同，必须事先确认日线标签、时区与闭市边界，并以未来新记录独立验收；不能回填本次失败。正式预测仍为候选时段分层，trustedCount不能由该研究核验升级。本轮不修改正式模型、不宣称准确率提高。

## 工程验证

先以真实回执复现零请求重放缺失，再修复；归档字节锚点测试也先失败再通过。原未结束/基线篡改检查通过，真实回执零请求重放、OHLC不一致拒评分、原输入不变检查通过，生产Java独立检查通过。Java全量1049项，0失败、0错误、3跳过；页面Node17项通过。独立审查未发现阻塞问题。非阻塞限制：Java独立终端摘要需与本报告共同解读，其简短状态行不单独声明UTC及候选合同边界，不是官方认证证明。
