package com.opspilot.ai.forecast;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 将正式黄金研究快照转换为受约束的方向预测提示词。 */
@Component
public class GoldForecastPromptBuilder {

    public static final String PROMPT_VERSION =
            "gold-direction-forecast-prompt-v2";
    public static final String CANDIDATE_VERSION = "gold-neutral-contract-candidate-v1";
    public static final String MACRO_VERSION = "gold-macro-values-candidate-v1";
    public static final String EVIDENCE_VERSION = "gold-evidence-candidate-v1";

    /** 独立研究格式：方向预测外包一层可核对引用，不用于正式生成。 */
    public GoldForecastPrompt buildEvidence(StoredGoldResearchSnapshot record) {
        var macro = buildMacro(record);
        String contract = "{\"direction\":\"BULLISH|NEUTRAL|BEARISH\",\"reasoning\":\"研究依据\",\"invalidationConditions\":[\"失效条件\"]}";
        // 只替换唯一的输出合同，不替换任何行情事实；已有两个候选保留原格式。
        String content = macro.content().replace(contract,
                "{\"forecast\":" + contract + ",\"evidence\":[]}") + """

                【结构化引用合同】
                evidence必须覆盖九项，不能为空：gold1,gold5,gold20,rate1,rate5,rate20,usd1,usd5,usd20。
                gold对应黄金收益率（%），rate对应实际利率变化（基点），usd对应广义美元收益率（%）。
                数字1/5/20对应相应观测周期，不得混用字段、单位或周期。
                每项仅含key、value、trend；key是上述字段名，value为原始JSON数值或null。
                trend使用UP/DOWN/FLAT/MISSING：正值UP、负值DOWN、零FLAT，缺失为null与MISSING。
                trend描述已提供变化值的符号，不是未来预测方向；不得改写原值、补零、遗漏或重复。
                forecast.reasoning应与所引用的原值及符号一致，不得把负变化说成上涨。
                不增加新闻、价格位置、反弹需求等未提供的事实。只返回外层forecast与evidence两个字段。
                """;
        return new GoldForecastPrompt(EVIDENCE_VERSION, content, sha256(content));
    }

    private static final String BASE_RULE = "7. 波动率较高时，优先考虑 NEUTRAL 或反转风险。";
    private static final String CANDIDATE_RULE = "7. 波动率较高只表示价格变动可能更大，不能因此优先选择 NEUTRAL；"
            + "证据不确定不等于涨跌幅位于 [-0.5%, 0.5%]，仍须按三方向合同选择最有依据的类别，"
            + "并在 reasoning 中说明不确定性。";

    /** 研究候选入口；不由正式生成服务调用。 */
    public GoldForecastPrompt buildCandidate(StoredGoldResearchSnapshot record) {
        return build(record, CANDIDATE_VERSION, CANDIDATE_RULE);
    }

    public GoldForecastPrompt build(StoredGoldResearchSnapshot record) {
        return build(record, PROMPT_VERSION, BASE_RULE);
    }

    /** 宏观数值研究候选；正式生成路径不调用。 */
    public GoldForecastPrompt buildMacro(StoredGoldResearchSnapshot record) {
        var base = build(record);
        var snapshot = record.snapshot();
        var rate = snapshot.realRate();
        var dollar = snapshot.dollarIndex();
        // 保留原方向合同，仅追加快照已保存的事实；不查询外部行情或反推缺失值。
        String content = base.content() + """

                【宏观数值补充】
                1/5/20期表示相应宏观序列的观测间隔，不是自然日，也不等于黄金交易日。
                广义美元指数不是DXY；本快照未留存来源码，不能据此推断具体序列编号。
                因子解释是既有摘要，数值发生周期分歧时须结合各期原值说明，不得把5期称作1期。
                实际利率观察日期：%s
                广义美元观察日期：%s
                实际利率当前值（%%）：%s
                实际利率1期变化（基点）：%s
                实际利率5期变化（基点）：%s
                实际利率20期变化（基点）：%s
                广义美元当前值（指数点）：%s
                广义美元1期变化（%%）：%s
                广义美元5期变化（%%）：%s
                广义美元20期变化（%%）：%s
                缺失表示快照未提供该值，不得补零、估算或从因子标签反推。
                """.formatted(
                snapshot.latestRealRateDate() == null ? "缺失" : snapshot.latestRealRateDate(),
                snapshot.latestDollarIndexDate() == null ? "缺失" : snapshot.latestDollarIndexDate(),
                value(rate == null ? null : rate.currentRate()),
                value(rate == null ? null : rate.basisPointChange1()),
                value(rate == null ? null : rate.basisPointChange5()),
                value(rate == null ? null : rate.basisPointChange20()),
                value(dollar == null ? null : dollar.currentIndex()),
                value(dollar == null ? null : dollar.return1()),
                value(dollar == null ? null : dollar.return5()),
                value(dollar == null ? null : dollar.return20()));
        return new GoldForecastPrompt(MACRO_VERSION, content, sha256(content));
    }

    // 保留原始精度，区分真实零值与未提供的值。
    private String value(BigDecimal number) {
        return number == null ? "缺失" : number.toPlainString();
    }

    // 共用模板和事实输入，只改变预先指定的规则；不对事实文本做全局替换。
    private GoldForecastPrompt build(StoredGoldResearchSnapshot record, String version, String rule) {
        Objects.requireNonNull(record, "正式快照记录不能为空");
        GoldResearchSnapshot snapshot = record.snapshot();

        String content = """
                你是个人黄金投资研究助手。请基于给定正式快照，
                预测黄金下一个有效交易日的方向。

                只能使用下列事实，不得修改、重新计算或补充外部数据。

                【正式快照】
                快照编号：%s
                分析日期：%s
                黄金数据日期：%s
                实际利率数据日期：%s
                美元指数数据日期：%s
                研究版本：%s
                黄金当前价格：%s
                黄金1期收益率：%s%%
                黄金5期收益率：%s%%
                黄金20期收益率：%s%%
                黄金20期波动率：%s
                实际利率因子：%s
                实际利率解释：%s
                美元指数因子：%s
                美元指数解释：%s

                【方向合同】
                后续真实涨跌幅大于 0.5%% 为 BULLISH；
                小于 -0.5%% 为 BEARISH；
                其余包括两个边界值均为 NEUTRAL（中性）。

                【第二版校正规则】
                1. 预测的是下一个有效交易日，不是中长期趋势。
                2. 不得因为 20 期收益率为正就自动判断为 BULLISH。
                3. 1 期收益率为负时，必须说明短线转弱风险。
                4. 5 期和 1 期方向冲突时，必须降低方向确信度。
                5. 20 期涨幅很大但 1 期转负时，必须考虑高位回落或获利了结。
                6. 美元指数数据明显滞后时，必须降低美元指数因子的权重。
                %s
                8. 如果证据只支持轻微涨跌，不能判断为 BULLISH 或 BEARISH，应判断为 NEUTRAL。
                9. reasoning 必须说明短线动量、中期趋势、宏观因子和最终取舍。

                【安全边界】
                只返回 JSON，不要使用 Markdown 代码块。
                不得生成新闻、市场事件或任何未提供的事实。
                不得给出目标价、涨跌概率、止损位、仓位或买卖建议。

                严格返回：
                {"direction":"BULLISH|NEUTRAL|BEARISH","reasoning":"研究依据","invalidationConditions":["失效条件"]}
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
                snapshot.gold().volatility20() == null
                        ? "无"
                        : snapshot.gold().volatility20().toPlainString(),
                snapshot.realRateAssessment().status().name(),
                snapshot.realRateAssessment().explanation(),
                snapshot.dollarIndexAssessment().status().name(),
                snapshot.dollarIndexAssessment().explanation(),
                rule
        );
        return new GoldForecastPrompt(version, content, sha256(content));
    }

    /** 使用 UTF-8 生成稳定摘要，用于预测审计和幂等判断。 */
    private String sha256(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "当前 Java 环境不支持 SHA-256",
                    exception
            );
        }
    }
}
