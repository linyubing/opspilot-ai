package com.opspilot.ai.forecast.review;

import com.opspilot.ai.forecast.DirectionEvaluation;
import com.opspilot.ai.forecast.ForecastVersionEvaluation;
import com.opspilot.ai.forecast.GoldForecastEvaluation;
import com.opspilot.ai.forecast.GoldSignalEvaluation;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.stream.Collectors;

/** 将历史预测评测结果转换成受约束的 AI 复盘提示词。 */
@Component
public class GoldForecastReviewPromptBuilder {

    public static final String PROMPT_VERSION =
            "gold-forecast-review-prompt-v3";

    private static final int MINIMUM_RESOLVED_COUNT = 30;

    /**
     * 根据真实历史评测数据构建 AI 复盘提示词。
     */
    public GoldForecastReviewPrompt build(
            GoldForecastEvaluation evaluation
    ) {
        Objects.requireNonNull(
                evaluation,
                "黄金预测评测结果不能为空"
        );

        // 弃权虽已结算真实行情，仍不提供方向评分样本；旧汇总没有逐条协议时保留原门槛。
        validateSampleCount(evaluation.signals() == null ? evaluation.resolvedCount() : evaluation.signals().scoredCount());

        String content = """
            你是黄金方向预测系统的评测分析助手。

            请根据下面的真实历史评测数据，分析预测系统的方向偏差、
            近期表现变化和不同版本之间的差异。

            【总体表现】
            总预测数：%d
            待验证样本数：%d
            已解析样本数：%d
            总体命中率：%s
            最近20条命中率：%s
            中性基线命中率：%s

            【信号协议与分母】
            %s
            弃权不进入命中率分母；pending、无实际方向或无命中证据的记录也不进入方向评分。
            已解析总数不等于已结算评分信号数，不得用弃权补足复盘评分样本门槛。
            旧三方向协议保留原模型、提示词和方向规则身份，不能追认成新信号协议。
            不同模型、提示词或规则的信号覆盖不同，不能将各自子集命中率差异称为准确率提高。

            【发布时间资格】
            未知发布时间样本：%d
            候选开盘前样本：%d
            候选盘中样本：%d
            无效候选样本：%d
            可信预测样本：%d
            可信准确率：%s
            上述总体、方向及版本指标仅为历史描述，不能证明未来预测准确率。
            候选时段不是官方合同，也不是历史输入当时可得的证明。
            不得混合候选盘中与开盘前样本宣称全时段预测准确率。

            【分方向表现】
            %s
            %s
            %s

            【版本表现】
            %s

            【复盘要求】
            1. 比较总体命中率和中性基线命中率。
            2. 判断最近20条表现是否低于总体表现。
            3. 分析三个预测方向是否存在样本或命中率偏差。
            4. 比较不同模型、提示词和规则版本的表现。
            5. 明确区分数据支持的结论和仍需验证的假设。
            6. 提出的改进建议必须说明后续验证方法。

            【安全边界】
            不得编造未提供的行情、新闻、宏观事件或预测记录。
            不得给出目标价、仓位、止损位或买卖建议。
            不得自动修改正式预测提示词。
            样本量有限时必须说明统计不确定性。

            只返回 JSON，不要使用 Markdown 代码块。

            严格返回：
            {
              "summary": "总体表现摘要",
              "directionBiases": [
                {
                  "direction": "BULLISH|NEUTRAL|BEARISH",
                  "observation": "方向表现观察",
                  "evidence": "对应的数据证据"
                }
              ],
              "recentPerformance": "近期表现和总体表现的比较",
              "versionFindings": [
                {
                  "modelName": "模型名称",
                  "promptVersion": "提示词版本",
                  "ruleVersion": "规则版本",
                  "observation": "版本表现观察"
                }
              ],
              "improvementHypotheses": [
                {
                  "hypothesis": "待验证的改进假设",
                  "validationMethod": "后续验证方法"
                }
              ],
              "risks": ["统计或数据风险"],
              "disclaimer": "本复盘不构成价格预测、交易或投资建议"
            }
            """.formatted(
                evaluation.totalCount(),
                evaluation.pendingCount(),
                evaluation.resolvedCount(),
                formatAccuracy(evaluation.overallAccuracy()),
                formatAccuracy(evaluation.rolling20Accuracy()),
                formatAccuracy(evaluation.neutralBaselineAccuracy()),
                formatSignals(evaluation.signals()),
                evaluation.timing().unknown().sampleCount(),
                evaluation.timing().beforeSession().sampleCount(),
                evaluation.timing().inSession().sampleCount(),
                evaluation.timing().invalid().sampleCount(),
                evaluation.timing().trustedCount(),
                formatAccuracy(evaluation.timing().trustedAccuracy()),
                formatDirection(evaluation.bullish()),
                formatDirection(evaluation.neutral()),
                formatDirection(evaluation.bearish()),
                formatVersions(evaluation)
        );

        return new GoldForecastReviewPrompt(
                PROMPT_VERSION,
                content
        );
    }

    /** 校验样本量，避免让模型根据少量偶然结果给出误导性建议。 */
    private void validateSampleCount(int resolvedCount) {
        if (resolvedCount < MINIMUM_RESOLVED_COUNT) {
            throw new InsufficientForecastReviewSamplesException(
                    MINIMUM_RESOLVED_COUNT,
                    resolvedCount
            );
        }
    }

    private String formatDirection(DirectionEvaluation evaluation) {
        return "%s：样本数=%d，命中数=%d，命中率=%s".formatted(
                evaluation.direction().name(),
                evaluation.sampleCount(),
                evaluation.hitCount(),
                formatAccuracy(evaluation.accuracy())
        );
    }

    private String formatVersions(GoldForecastEvaluation evaluation) {
        if (evaluation.versions().isEmpty()) {
            return "暂无可比较的版本数据";
        }

        return evaluation.versions()
                .stream()
                .map(this::formatVersion)
                .collect(Collectors.joining("\n"));
    }

    private String formatVersion(ForecastVersionEvaluation version) {
        return "模型=%s，提示词=%s，规则=%s，样本数=%d，命中数=%d，命中率=%s"
                .formatted(
                        version.modelName(),
                        version.promptVersion(),
                        version.forecastRuleVersion(),
                        version.sampleCount(),
                        version.hitCount(),
                        formatAccuracy(version.accuracy())
                ) + "\n" + formatSignals(version.signals());
    }

    /** 如实提供覆盖分母及同信号基线，缺少方向/概率时不补造完整样本成绩。 */
    private String formatSignals(GoldSignalEvaluation signals) {
        if (signals == null) {
            return "旧汇总缺少信号协议证据，覆盖率、弃权数和完整样本成绩不可得；沿用旧已解析样本门槛，不能追认新协议。";
        }
        return """
                协议记录总数：%d
                有方向信号数（包含旧协议历史方向）：%d
                显式信号数：%d
                已结算评分信号数：%d
                弃权数：%d
                旧未知协议数：%d
                显式协议覆盖率：%s
                同信号命中率：%s
                同信号中性基线：%s
                完整样本准确率：%s
                口径说明：%s
                """.formatted(signals.sampleCount(), signals.signalCount(), signals.declaredSignalCount(),
                signals.scoredCount(), signals.abstainedCount(), signals.legacyCount(),
                formatAccuracy(signals.coverage()), formatAccuracy(signals.signalAccuracy()),
                formatAccuracy(signals.neutralBaselineAccuracy()), signals.fullSampleAccuracy() == null
                        ? "不可得（无完整强制方向/概率或仍有未结算记录，不能补成中性）"
                        : signals.fullSampleAccuracy().toPlainString(), signals.note());
    }

    /** 没有有效样本时保留真实语义，不能把未知错误显示为零。 */
    private String formatAccuracy(BigDecimal accuracy) {
        return accuracy == null
                ? "暂无有效样本"
                : accuracy.toPlainString();
    }
}
