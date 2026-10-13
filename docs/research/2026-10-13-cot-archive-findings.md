# 2023年黄金持仓档案核验：内容完整，首次可得性仍需分级

## 本轮证据

实际下载并逐份核对了52份CFTC Disaggregated Futures Only黄金报表（合约088691），覆盖2023-01-03至2023-12-26。每份检查市场、报告类型、统计日期和4个数值：总未平仓、Managed Money长仓、短仓、跨期仓；共208项数值全部与已冻结的2023年度CSV一致。每份原始回执有SHA256，见 `2026-10-13-cot-archive-record.json`。

可复查命令：`powershell.exe -NoProfile -File docs/research/probes/CheckCotArchive.ps1`。默认只验证本地回执；显式 `-Fetch` 仅下载缺失文件。52份下载完成后再次无网络运行，退出码0。脚本使用UTF-8 BOM，避免Windows PowerShell按ANSI把中文注释与下一行参数吞并。

## 日期不能统一加三天

52份中10份的档案Updated日不是统计日加3天：此前核实的7份积压报告，以及下列3份：

| 统计日 | 档案Updated日 | 间隔 |
| --- | --- | --- |
| 2023-07-03 | 2023-07-07 | 4日 |
| 2023-11-07 | 2023-11-13 | 6日 |
| 2023-11-21 | 2023-11-27 | 6日 |

这3项是档案日期观察，不在没有公告时推断延迟的具体原因，更不把Updated称为首次发布证明。官方[历史报表索引](https://www.cftc.gov/MarketReports/CommitmentsofTraders/HistoricalViewable/index.htm)区分统计日期与发布日期；[特别公告](https://www.cftc.gov/MarketReports/CommitmentsofTraders/HistoricalSpecialAnnouncements/index.htm)可用于核对异常实际发行。

## 研究准入判断

- 7份为NOTICE_AND_ARCHIVE：此前实际发行公告与档案日期一致，可证明发行日到日级别；不假造小时。
- 45份为ARCHIVE_UPDATED_ONLY：保留Updated字段，但actualPublicationDate为null。
- 当前回执与年度CSV一致，只证明今天取得的两种档案一致，不认证2023年首次发布字节。首次观察发生在2026年，不能倒写成历史观察时间。
- 不将45个Updated日自动升级成严格时点输入，也不对未知日默认加两天就声称消除了版本风险。全年严格历史训练准入继续为false。

## 对提高准确率目标的影响

本轮没有新增准确率结果，没有读最终留出行情，没有改正式预测或训练模型。它排除了一个会制造虚假准确率的做法：使用统计日或固定周五时间，把当时未发布的持仓放入历史预测。

持仓路线尚未得到支持或被统计否定。下一步应针对45份补发行时程/版本证据，并先冻结输入可得性和实验规则，再读取对应开发标签。若历史版本证据无法补齐，可以明确降级做回溯探索，但不能把这种结果当成正式样本外提升，也不能通过换指标、改阈值掩盖此前多轮没有稳健优势的事实。
