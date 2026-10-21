package com.opspilot.ai.analysis;

import com.opspilot.ai.macrodata.MacroObservation;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

/** 冻结两类宏观实际使用的0/1/5/20位观测；采集时间不是历史公布时刻证明。 */
public record GoldMacroInput(List<MacroObservation> rates, List<MacroObservation> dollars) {
    public GoldMacroInput {
        rates = rates == null ? List.of() : List.copyOf(rates);
        dollars = dollars == null ? List.of() : List.copyOf(dollars);
    }

    /** 校验原始事实与摘要一致，并拒绝冻结时刻之后才采集的输入。 */
    public boolean matches(GoldResearchSnapshot snapshot, OffsetDateTime checkedAt) {
        if (!valid(rates, "DFII10", checkedAt) || !valid(dollars, "DTWEXBGS", checkedAt)
                || snapshot.realRate() == null || snapshot.dollarIndex() == null
                || snapshot.realRate().collectedAt() == null || snapshot.dollarIndex().collectedAt() == null) return false;
        var rate = snapshot.realRate();
        var dollar = snapshot.dollarIndex();
        return rates.getFirst().observationDate().equals(snapshot.latestRealRateDate())
                && dollars.getFirst().observationDate().equals(snapshot.latestDollarIndexDate())
                && rates.getFirst().collectedAt().isEqual(rate.collectedAt())
                && dollars.getFirst().collectedAt().isEqual(dollar.collectedAt())
                && same(rates.getFirst().value(), rate.currentRate())
                && same(dollars.getFirst().value(), dollar.currentIndex())
                && same(delta(1), rate.percentagePointChange1())
                && same(delta(2), rate.percentagePointChange5())
                && same(delta(3), rate.percentagePointChange20())
                && same(basisPoints(1), rate.basisPointChange1())
                && same(basisPoints(2), rate.basisPointChange5())
                && same(basisPoints(3), rate.basisPointChange20())
                && same(change(1), dollar.return1())
                && same(change(2), dollar.return5())
                && same(change(3), dollar.return20());
    }

    private boolean valid(List<MacroObservation> rows, String series, OffsetDateTime checkedAt) {
        if (checkedAt == null || rows.size() != 4) return false;
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            if (row == null || !series.equals(row.seriesId()) || row.observationDate() == null
                    || row.observationDate().isAfter(checkedAt.toLocalDate()) || row.value() == null
                    || row.collectedAt() == null || row.collectedAt().isAfter(checkedAt)
                    || row.provider() == null || row.provider().isBlank()
                    || row.unit() == null || row.unit().isBlank()
                    || !rows.getFirst().provider().equals(row.provider())
                    || !rows.getFirst().unit().equals(row.unit())
                    || (series.equals("DTWEXBGS") && row.value().signum() <= 0)
                    || (i > 0 && !row.observationDate().isBefore(rows.get(i - 1).observationDate()))) return false;
        }
        return true;
    }

    private BigDecimal delta(int index) {
        return rates.getFirst().value().subtract(rates.get(index).value()).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal change(int index) {
        return dollars.getFirst().value().divide(dollars.get(index).value(), MathContext.DECIMAL128)
                .subtract(BigDecimal.ONE).multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP);
    }

    /** 1 个百分点等于 100 个基点；与快照生成时的两位小数口径一致。 */
    private BigDecimal basisPoints(int index) {
        return delta(index).multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
    }

    private boolean same(BigDecimal expected, BigDecimal actual) {
        return actual != null && expected.compareTo(actual) == 0;
    }
}
