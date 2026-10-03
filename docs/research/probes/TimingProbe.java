import com.opspilot.ai.forecast.*;
import com.opspilot.ai.marketdata.*;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** 只读复现生产结算的时点边界；数学夹具不是行情，也不计入准确率。 */
public final class TimingProbe {
    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");

    public static void main(String[] args) {
        check("past", "2026-09-01", "2026-09-02T01:00:00Z", true);
        check("current-day", "2026-09-02", "2026-09-02T11:00:00Z", true);
        check("future", "2026-09-03", "2026-09-04T01:00:00Z", false);
        check("future-date", "2026-09-03", "2026-09-02T11:00:00Z", false);
        check("future-collection", "2026-09-01", "2026-09-02T13:00:00Z", false);
        check("missing", null, null, false);
        System.out.println("PASS: 6 production timing observations; current-day close remains unverified");
    }

    private static void check(String name, String date, String collected, boolean accepted) {
        StoredGoldDirectionForecast pending = forecast(null);
        AtomicReference<ForecastResolution> saved = new AtomicReference<>();
        GoldForecastRepository forecasts = proxy(GoldForecastRepository.class, (method, values) -> {
            return switch (method) {
                case "findPending" -> List.of(pending);
                case "resolve" -> {
                    saved.set((ForecastResolution) values[1]);
                    yield forecast(saved.get());
                }
                default -> throw new AssertionError("Unexpected forecast call: " + method);
            };
        });
        GoldDailyBarRepository bars = proxy(GoldDailyBarRepository.class, (method, values) -> {
            if (!method.equals("findNext")) throw new AssertionError("Unexpected bar call: " + method);
            if (!values[2].equals(LocalDate.parse("2026-08-31"))) {
                throw new AssertionError("Base date was changed");
            }
            return date == null ? Optional.empty() : Optional.of(new GoldDailyBar(
                    "XAUUSD", LocalDate.parse(date), new BigDecimal("100"),
                    new BigDecimal("102"), new BigDecimal("99"), new BigDecimal("101"),
                    "usd", "troy_ounce", "twelve_data", OffsetDateTime.parse(collected)));
        });
        GoldForecastResolutionService service = new GoldForecastResolutionService(
                forecasts, bars, new GoldForecastRule(), Clock.fixed(NOW, ZoneOffset.UTC));
        ResolveGoldForecastsResult result = service.resolvePending(1);
        boolean resolved = result.resolvedCount() == 1;
        // 固定已修复的时间边界；当前日线仍可被使用，不宣称已完成收盘保护。
        if (resolved != accepted || (saved.get() != null) != accepted
                || result.pendingCount() != (accepted ? 0 : 1)) {
            throw new AssertionError("Observed timing behavior changed: " + name);
        }
        if (accepted && (!saved.get().targetDate().equals(LocalDate.parse(date))
                || saved.get().actualReturn().compareTo(new BigDecimal("1.000000")) != 0
                || !saved.get().resolvedAt().toInstant().equals(NOW))) {
            throw new AssertionError("Production calculation was not observed: " + name);
        }
        System.out.printf(Locale.ROOT, "%s: target=%s, collected=%s, resolved=%s, now=%s%n",
                name, date, collected, resolved, NOW);
    }

    private static StoredGoldDirectionForecast forecast(ForecastResolution resolution) {
        return new StoredGoldDirectionForecast(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                LocalDate.parse("2026-08-31"), new BigDecimal("100"),
                ForecastDirection.BULLISH, "数学诊断", List.of("非真实行情"), "diagnostic",
                "diagnostic", "a".repeat(64), GoldForecastRule.RULE_VERSION, "{}",
                resolution == null ? ForecastStatus.PENDING : ForecastStatus.RESOLVED,
                resolution == null ? null : resolution.targetDate(),
                resolution == null ? null : resolution.targetPrice(),
                resolution == null ? null : resolution.actualReturn(),
                resolution == null ? null : resolution.actualDirection(),
                resolution == null ? null : resolution.hit(),
                resolution == null ? null : resolution.resolvedAt(),
                OffsetDateTime.parse("2026-09-01T01:00:00Z"));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Calls calls) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, values) -> calls.call(method.getName(), values));
    }

    @FunctionalInterface
    private interface Calls {
        Object call(String method, Object[] values);
    }
}
