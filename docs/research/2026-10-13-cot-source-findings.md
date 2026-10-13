# 官方黄金持仓数据可取得，历史时点尚未满足模型实验门槛

当前价格/宏观状态、VIX、CPI状态、单CPI意外与时距检查均未交付稳健次日方向优势。本轮没有自动换指标训练，而是先验证一种不同的信息源：报告中的期货参与者持仓，而非黄金自身技术指标。它可能描述仓位环境，但不等于看涨/看跌答案，尚无增益证据。

## 实际取得的档案

从[CFTC官方历史下载页](https://www.cftc.gov/MarketReports/CommitmentsofTraders/HistoricalCompressed/index.htm)取得2023年Disaggregated Futures Only文本ZIP，HTTP200、2202972字节。直接在内存读取唯一条目 `f_year.txt`（20244825字节），没有向文件系统解压未知路径。CSV共12534行；按合约代码字符串088691选择黄金52行，日期2023-01-03至12-26。

名称为 `GOLD - COMMODITY EXCHANGE INC.`，不是现货XAUUSD报价，也不是黄金ETF持仓。实际存在Open Interest及Managed Money长、短、跨期等字段，必须保留报告类型和市场身份；不能把不同报告或现货价格混成一列。

ZIP摘要：`9fe75dba0256819d476bb0d01f9e8e5ac6ec2c4390f609a7e3259e85796b79ce`。原始档案仅本地保留，结构与拒绝条件见 `2026-10-13-cot-source-record.json`。

## 时间风险有官方反例

CSV的 `Report_Date_as_YYYY-MM-DD` 是统计日期，没有逐条发布时间字段。[官方说明](https://www.cftc.gov/es/node/128971)指出通常周五15:30美东公布前周二统计，节假日可改期，并说明完整历史发布日期列表不是现成可用的资料。因此不能将统计日直接用于当日输入，也不能无条件加三天构造可得性。

[2023年2月10日公告](https://www.cftc.gov/PressRoom/PressReleases/8655-23)确认ION相关事件导致COT持续延迟，证明通常时程存在例外。[历史特别公告](https://www.cftc.gov/MarketReports/CommitmentsofTraders/HistoricalSpecialAnnouncements/index.htm)还明确2019年4月3日对3月26日统计、3月29日发布记录追加持仓，市场包含黄金088691。不能只凭一般FAQ关于历史不更新的概括，忽略这份针对黄金的具体修正公告。

## 本轮判断与下一项工作

数据真实且可下载，不代表历史预测截止时这些值已可用。尚未验证52周真实发布日期及当时版本，故 `historicalTrainingReady=false`。没有填入默认发布日期，没有计算持仓特征、预测涨跌或读最终留出行情，也没有修改正式模型。

下一项安全工作是核对官方异常发布/恢复时程与历史版本；无法定位的周保留不可用，而非假装已知。若只能取得今日回溯表，最多作为明确降级的历史探索，不用于严格PIT晋级。当前也可以从现在开始形成真实观察回执，但不能把2026年首次观察时间倒写成2023年发布时间。

本轮没有提高准确率的结果，只确认新增信息源的内容和实际准入缺口。生产测试通过与这一研究时点门槛是不同问题；未将之前Maven回归当作本轮的新验证。
