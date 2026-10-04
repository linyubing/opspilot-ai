# 黄金收盘差异复查：结束边界候选

## 有限真实请求

上一轮有真实23小时完整覆盖，但收盘不一致；起点前移一小时四项均不一致。
本轮只发两次只读请求，不调用模型、不修改数据库、不读取最终留出集：

- 1day：XAU/USD，start_date=2026-10-03，end_date=2026-10-04，outputsize=3。
  开始2026-10-04T17:13:13.2761498Z，完成17:13:14.7206125Z，返回1行2026-10-03。
- 1h：XAU/USD，UTC，2026-10-02 21:00:00至2026-10-03 20:00:00，outputsize=24。
  开始17:13:14.7507720Z，完成17:13:15.0394075Z，返回24行，含请求右端点20:00小时。

供应商采集时间距上一轮小时请求约11分钟，不是跨日修订验证。
本轮日线请求带日期范围，上一轮最新3行请求不带范围，两者不同；
因此本轮没有重复标签不能证明生产5000行响应中的重复标签已消失。

## 修订假设未获支持

比较相同日期/时间戳：原生日线四项均未改变；两次小时响应的重叠行四项均未改变。
使用生产GoldSessionCheck重新核验，仍为OHLC_MISMATCH：23小时完整，开高低相同、收盘不同。
本轮没有观察到修订，但不能由11分钟无变化推导供应商永不修订。

## 结束边界假设得到单日支持

原生日线收盘不等于19:00小时收盘，也不等于20:00小时开盘，
但**等于20:00小时收盘**。

保留起点2026-10-02T21:00Z，将结束边界从10月3日20:00Z后移至21:00Z，
采用半开区间[start,end)，包含24个连续且不重复的真实小时。
BoundaryReceiptProbe使用BigDecimal精确聚合，不用浮点容差或改价：

| 字段 | 与原生日线一致 |
| --- | --- |
| open | true |
| high | true |
| low | true |
| close | true |

工具新编译、真实回执重放退出0。该结果支持此日期的后移结束边界假设，
反驳仅依赖Sydney本地7点在两端独立换算即可覆盖此日的假设。
它没有证明供应商官方时段、发布时间、闭市确认或通用DST算法。
officialSessionCertified=false；没有修改GoldSession或正式预测。

## 证据保存与复现

原始回执（包含原始响应字符串及解析结果）只保存在Git忽略本机文件：
backend/target/2026-10-05-source-revision-recheck.json。
文件SHA-256：d8554cbe97b2d75253c20e8c17c7350643a31eb7a58110cae3a1ed9de421b6c8。
这不是HTTP原文哈希；不发布供应商原始价格或含凭据URL。
target可能被clean清除，需保留本机证据。

```powershell
$probePath = 'D:\workFile\demo-ai\backend\target;D:\workFile\demo-ai\backend\target\classes;' + (Get-Content backend/target/probe-classpath.txt -Raw).Trim()
javac -encoding UTF-8 -cp $probePath -d backend/target docs/research/probes/BoundaryReceiptProbe.java
java -cp $probePath BoundaryReceiptProbe backend/target/2026-10-05-source-revision-recheck.json
```

## 多轮路线判断

前移起点失败，不代表必须换供应商；新的结束边界匹配意味着优先研究标签/时段切换，
而不是继续随机扫描所有窗口或立刻购买另一来源。
但最新日线重复标签仍未证明解决，不能绕过生产拒绝来生成新准确率成绩。

下一步有限验证该候选边界与相邻日线是否连续、是否重叠，以及其他DST切换日能否复现。
在形成跨日期证据前不修改通用时段规则；时段成立也不等于预测会更准确。
多轮模型缺少稳健优势的结论保留，仍需新合格输入下的事前预测及真实目标结算。
