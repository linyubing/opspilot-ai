package com.opspilot.ai.analysis.narrative;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 将正式黄金研究快照转换为受约束且可追踪的模型提示词。 */
@Component
public class ResearchNarrativePromptBuilder {

    public static final String PROMPT_VERSION = "gold-narrative-prompt-v1";

    public ResearchNarrativePrompt build(StoredGoldResearchSnapshot record) {
        Objects.requireNonNull(record, "正式快照记录不能为空");

        GoldResearchSnapshot snapshot = record.snapshot();
        String content = """
                你是个人黄金投资研究助手。
                只能解释下面提供的正式研究快照，不得修改、重新计算或补充任何数据。

                【研究快照】
                快照编号：%s
                分析日期：%s
                黄金最新日期：%s
                实际利率最新日期：%s
                广义美元指数最新日期：%s
                研究版本：%s

                【黄金指标】
                当前价格：%s
                1期收益率：%s%%
                5期收益率：%s%%
                20期收益率：%s%%

                【实际利率指标】
                当前实际利率：%s
                1期变化：%s 个百分点
                5期变化：%s 个百分点
                20期变化：%s 个百分点
                因子状态：%s
                规则解释：%s

                【广义美元指数指标】
                当前指数：%s
                1期变化：%s%%
                5期变化：%s%%
                20期变化：%s%%
                因子状态：%s
                规则解释：%s

                【强制要求】
                1. 只返回 JSON，不要使用 Markdown 代码块。
                2. 不得重新计算或覆盖任何指标和因子状态。
                3. 不得生成新闻、市场事件、政策事件或外部事实。
                4. 不得给出目标价、止损位、涨跌概率或仓位建议。
                5. 不得给出买入或卖出建议。
                6. 必须分别解释实际利率和广义美元指数。
                7. 必须说明数据日期可能不完全一致带来的局限。
                8. 免责声明必须明确说明不构成价格预测、交易或投资建议。

                严格返回以下 JSON 结构：
                {
                  "summary": "一句话概括两个因子的当前含义",
                  "realRateAnalysis": "实际利率因子解读",
                  "dollarIndexAnalysis": "广义美元指数因子解读",
                  "risks": ["风险或局限"],
                  "watchList": ["下一步需要观察的指标变化"],
                  "disclaimer": "不构成价格预测、交易或投资建议"
                }
                """.formatted(
                record.id(),
                snapshot.analysisDate(),
                snapshot.latestGoldDate(),
                snapshot.latestRealRateDate(),
                snapshot.latestDollarIndexDate(),
                snapshot.researchVersion(),
                snapshot.gold().currentPrice().toPlainString(),
                snapshot.gold().return1().toPlainString(),
                snapshot.gold().return5().toPlainString(),
                snapshot.gold().return20().toPlainString(),
                snapshot.realRate().currentRate().toPlainString(),
                snapshot.realRate().percentagePointChange1().toPlainString(),
                snapshot.realRate().percentagePointChange5().toPlainString(),
                snapshot.realRate().percentagePointChange20().toPlainString(),
                snapshot.realRateAssessment().status().name(),
                snapshot.realRateAssessment().explanation(),
                snapshot.dollarIndex().currentIndex().toPlainString(),
                snapshot.dollarIndex().return1().toPlainString(),
                snapshot.dollarIndex().return5().toPlainString(),
                snapshot.dollarIndex().return20().toPlainString(),
                snapshot.dollarIndexAssessment().status().name(),
                snapshot.dollarIndexAssessment().explanation()
        );

        return new ResearchNarrativePrompt(PROMPT_VERSION, content, sha256(content));
    }

    /** 使用 UTF-8 计算稳定摘要，用于判断实际提示词内容是否发生变化。 */
    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            // Java 21 必须支持 SHA-256；该异常意味着运行环境不完整。
            throw new IllegalStateException("当前 Java 环境不支持 SHA-256", exception);
        }
    }
}
