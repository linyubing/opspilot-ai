package com.opspilot.ai.forecast;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.opspilot.ai.marketdata.GoldSessionCheckResult;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** 追加保存一次结算检查；通过核验不等于已完成结算或获得官方时段认证。 */
public record GoldResolutionCheck(UUID id, UUID forecastId, OffsetDateTime checkedAt,
        Code code, GoldSessionCheckResult baseCheck, GoldSessionCheckResult targetCheck) {
    public GoldResolutionCheck {
        Objects.requireNonNull(id); Objects.requireNonNull(forecastId);
        Objects.requireNonNull(checkedAt); Objects.requireNonNull(code);
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String reason() { return code.reason; }

    /** 固定中文原因，不保存供应商异常原文，避免密钥和URL进入记录。 */
    public enum Code {
        GENERATION_BASE_CHECKED("生成前基准候选小时核验通过；目标尚未核验，不代表官方认证或历史可得性证明"),
        WAITING_BAR("尚无基准日后的下一根真实日线"),
        INVALID_TIME("目标日线时间尚不可用或无效"),
        UNCONFIRMED_BAR("目标日线尚无可用的闭市凭证"),
        INVALID_TIMING("目标日期或发布时间不符合原承诺"),
        WAITING_END("承诺的目标候选时段尚未结束"),
        BASE_UNAVAILABLE("缺少同日且已确认的基准日线"),
        BASE_CHANGED("仓库基准价与预测冻结价不一致，不能替换"),
        BASE_SESSION_FAILED("基准日线未通过承诺小时窗口核验"),
        TARGET_SESSION_FAILED("目标日线未通过承诺小时窗口核验"),
        SOURCE_UNAVAILABLE("小时行情来源暂不可核验，未写入实际方向和命中"),
        CHECK_PASSED("两端候选时段核验通过，最终结算状态见预测记录；不代表官方认证"),
        LEGACY_PRICE_ONLY("旧合同仅按原日期价格口径处理，不具备新增小时证据");

        private final String reason;
        Code(String reason) { this.reason = reason; }
    }
}
