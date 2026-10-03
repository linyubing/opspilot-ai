package com.opspilot.ai.analysis;

import com.opspilot.ai.marketdata.GoldBarConfirmation;
import com.opspilot.ai.marketdata.GoldDailyBar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 数学窗口测试，不是市场预测效果或真实供应商凭证。 */
class GoldSnapshotInputTests {
    private static final LocalDate DATE = LocalDate.parse("2026-09-30");
    private static final OffsetDateTime CHECKED = OffsetDateTime.parse("2026-10-01T01:00:00Z");
    private static final BigDecimal PRICE = new BigDecimal("100");

    @Test
    @DisplayName("完整恒价窗口与手算零收益零波动指标匹配")
    void matchesWindow() {
        assertThat(new GoldSnapshotInput(bars(), CHECKED).matches(DATE, metrics(), CHECKED)).isTrue();
    }

    @Test
    @DisplayName("相同绝对时刻的不同偏移量不误拒绝")
    void matchesOffset() {
        assertThat(new GoldSnapshotInput(bars(), CHECKED).matches(DATE, metrics(),
                OffsetDateTime.parse("2026-10-01T09:00:00+08:00"))).isTrue();
    }

    @Test
    @DisplayName("不足21根、重复或乱序日期不能成为有效窗口")
    void rejectsBadWindow() {
        List<GoldDailyBar> values = bars();
        assertThat(new GoldSnapshotInput(values.subList(0, 20), CHECKED)
                .matches(DATE, metrics(), CHECKED)).isFalse();
        values.set(7, values.get(6));
        assertThat(new GoldSnapshotInput(values, CHECKED).matches(DATE, metrics(), CHECKED)).isFalse();
        values = bars();
        Collections.swap(values, 7, 8);
        assertThat(new GoldSnapshotInput(values, CHECKED).matches(DATE, metrics(), CHECKED)).isFalse();
    }

    @Test
    @DisplayName("中间日线无确认或核验时间在未来时拒绝")
    void rejectsUnconfirmed() {
        List<GoldDailyBar> values = bars();
        GoldDailyBar bar = values.get(7);
        values.set(7, new GoldDailyBar(bar.symbol(), bar.priceDate(), bar.open(), bar.high(),
                bar.low(), bar.close(), bar.currency(), bar.unit(), bar.provider(), bar.collectedAt()));
        assertThat(new GoldSnapshotInput(values, CHECKED).matches(DATE, metrics(), CHECKED)).isFalse();
        assertThat(new GoldSnapshotInput(bars(), CHECKED.plusSeconds(1))
                .matches(DATE, metrics(), CHECKED)).isFalse();
    }

    @Test
    @DisplayName("价格虽有效但中间收盘变动不能匹配原有零波动快照")
    void rejectsChangedPrice() {
        List<GoldDailyBar> values = bars();
        values.set(7, bar(DATE.minusDays(7), new BigDecimal("110")));
        assertThat(new GoldSnapshotInput(values, CHECKED).matches(DATE, metrics(), CHECKED)).isFalse();
    }

    @Test
    @DisplayName("黄金基准日期或快照指标不一致时拒绝")
    void rejectsChangedMetrics() {
        GoldSnapshotInput input = new GoldSnapshotInput(bars(), CHECKED);
        assertThat(input.matches(DATE.minusDays(1), metrics(), CHECKED)).isFalse();
        GoldReturnMetrics wrong = new GoldReturnMetrics(PRICE, BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, CHECKED);
        assertThat(input.matches(DATE, wrong, CHECKED)).isFalse();
    }

    @Test
    @DisplayName("留痕窗口不可被调用方后续修改")
    void freezesWindow() {
        List<GoldDailyBar> values = bars();
        GoldSnapshotInput input = new GoldSnapshotInput(values, CHECKED);
        values.clear();
        assertThat(input.bars()).hasSize(21);
        assertThatThrownBy(() -> input.bars().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private GoldReturnMetrics metrics() {
        return new GoldReturnMetrics(PRICE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, CHECKED);
    }

    private List<GoldDailyBar> bars() {
        List<GoldDailyBar> result = new ArrayList<>();
        for (int i = 0; i < 21; i++) result.add(bar(DATE.minusDays(i), PRICE));
        return result;
    }

    private GoldDailyBar bar(LocalDate date, BigDecimal price) {
        return new GoldDailyBar("XAUUSD", date, price, price, price, price, "usd", "troy_ounce",
                "twelve_data", CHECKED,
                new GoldBarConfirmation(GoldBarConfirmation.SOURCE, DATE, CHECKED, "a".repeat(64)));
    }
}
