# 黄金实验历史版本输入

统计模型实验现已使用 FRED 历史有效区间，不再直接使用实时观测表的最新修订值。正式 GLM 预测和实时行情同步不变。

## 下载并启用

在项目根目录的 PowerShell 中执行；目录名自己选择新的批次名，不能覆盖已有目录：

```powershell
.\scripts\Sync-FredHistory.ps1 -EndDate '2026-09-29' -OutputDir 'D:\workFile\demo-ai\data\fred-history\2026-09-29'
$env:FRED_HISTORY_DIR = 'D:\workFile\demo-ai\data\fred-history\2026-09-29'
cd backend
.\mvnw.cmd spring-boot:run
```

沿用已有 `FRED_API_KEY`，脚本不打印密钥。也可以把 `FRED_HISTORY_DIR` 配置为 Windows 用户环境变量，或设置 Spring 的 `opspilot.forecast.gold.history-dir`。服务进程必须能读取该目录。

输入是两个真实 JSON：`DFII10.json` 与 `DTWEXBGS.json`。后者是广义美元指数，不是 DXY。脚本从[官方 FRED 接口](https://fred.stlouisfed.org/docs/api/fred/series_observations.html)下载版本区间；先写唯一 pending 目录，两个序列都成功才发布目标目录。失败时旧批次不受影响；pending 不可配置为实验目录。

## 口径

- 基准日 D 只采用 D-1 完整日已知的版本；这是保守日级规则，不是盘中发布时间模型。
- 缺少历史覆盖的早期样本跳过，缺失值不补零、不回退旧版本。
- 归档末端不足以覆盖实验日期时直接报错，不悄悄缩短回测日期。
- 每次构建冻结整个批次，实验参数 `macroInput` 含策略、两个原始文件 SHA-256 与覆盖区间；数据集指纹也包含该来源。
- 旧实验 `dataPolicy=legacy-latest-version`，表示未证明宏观值当时可得；新实验为 `fred-known-before-day-v1`。两者不能直接按命中数比较，需要同日期配对验证。

原始批次保留在 D 盘，独立于 Maven 的 target。项目忽略 `data/fred-history`，不要把密钥或大批原始数据提交到 Git。已有研究压缩包仍作为固定历史证据保存。

## 本次不代表什么

历史输入修复不是预测能力提升证明；需要进一步完成训练充分性检查和独立样本验收。旧历史快照/大模型回测入口暂未迁移，不要把本次修复描述为所有回测均已消除时间泄漏。
