package com.opspilot.ai.marketdata;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.opspilot.ai.forecast.GoldTradingCalendar;
import com.opspilot.ai.forecast.ConfiguredGoldTradingCalendar;

import java.time.DayOfWeek;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 从 Twelve Data 同步并保存有效工作日的黄金 OHLC 日线。 */
@Service
public class GoldDailyBarSyncService {

    private final TwelveDataGoldBarProvider provider;
    private final GoldDailyBarRepository repository;
    private final GoldTradingCalendar calendar;
    private final Clock clock;

    public GoldDailyBarSyncService(
            TwelveDataGoldBarProvider provider,
            GoldDailyBarRepository repository
    ) {
        this(provider, repository, new ConfiguredGoldTradingCalendar(), Clock.systemUTC());
    }

    @Autowired
    public GoldDailyBarSyncService(TwelveDataGoldBarProvider provider,
            GoldDailyBarRepository repository, GoldTradingCalendar calendar, Clock clock) {
        this.provider = provider;
        this.repository = repository;
        this.calendar = calendar;
        this.clock = clock;
    }

    public GoldDailyBarSyncResult sync() {
        List<GoldDailyBar> received = provider.fetchDailyBars();
        if (received == null || received.isEmpty()) {
            throw new MarketDataUnavailableException(
                    "Twelve Data 黄金 OHLC 日线为空"
            );
        }

        List<GoldDailyBar> valid = received.stream()
                .filter(this::weekday)
                .sorted(Comparator.comparing(GoldDailyBar::priceDate))
                .toList();
        if (valid.isEmpty()) {
            throw new MarketDataUnavailableException(
                    "Twelve Data 没有有效工作日黄金日线"
            );
        }

        repository.saveAll(valid);
        return new GoldDailyBarSyncResult(
                received.size(),
                valid.size(),
                received.size() - valid.size(),
                valid.getLast().priceDate()
        );
    }

    /** 最新行情与全历史同步分离；旧历史异常仍由原同步入口报告。 */
    public GoldDailyBarSyncResult syncLatest() {
        GoldDailyBar bar = provider.fetchLatestBar();
        if (!weekday(bar)) {
            throw new MarketDataUnavailableException("Twelve Data 最新确认日不是有效工作日");
        }
        repository.findLatest(bar.symbol(), bar.provider()).ifPresent(old -> {
            if (old.priceDate().isAfter(bar.priceDate())) {
                throw new MarketDataUnavailableException("Twelve Data 最新确认日落后于已保存行情");
            }
            if (old.priceDate().equals(bar.priceDate())
                    && (old.open().compareTo(bar.open()) != 0 || old.high().compareTo(bar.high()) != 0
                    || old.low().compareTo(bar.low()) != 0 || old.close().compareTo(bar.close()) != 0)) {
                throw new MarketDataUnavailableException("Twelve Data 最新价格发生修订，拒绝覆盖已保存行情");
            }
        });
        repository.saveAll(List.of(bar));
        return new GoldDailyBarSyncResult(1, 1, 0, bar.priceDate());
    }

    private boolean weekday(GoldDailyBar bar) {
        DayOfWeek day = bar.priceDate().getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    /** 显式恢复最近21日窗口；分批读取，全部核验后才一次性写入。 */
    public GoldDailyBarSyncResult recoverRecent() {
        return syncRecent(21);
    }

    /** 自动准备仍保留最多5个缺日的限制，不隐式放大请求。 */
    public GoldDailyBarSyncResult syncRecent() {
        return syncRecent(5);
    }

    private GoldDailyBarSyncResult syncRecent(int maxMissing) {
        GoldDailyBar latest = provider.fetchLatestBar();
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!latest.isConfirmedAt(now)) {
            throw new MarketDataUnavailableException("最新黄金来源锚点未确认");
        }
        LocalDate sourceDay = latest.priceDate();
        LocalDate previous = sourceDay;
        while (!previous.equals(calendar.nextBusinessDay(previous.minusDays(1)))) previous = previous.minusDays(1);
        LocalDate day = previous;
        repository.findLatest(latest.symbol(), latest.provider()).ifPresent(old -> {
            if (old.priceDate().isAfter(day)) {
                throw new MarketDataUnavailableException("Twelve Data 最新确认日落后于已保存行情");
            }
        });
        List<LocalDate> expected = new ArrayList<>();
        LocalDate cursor = day;
        while (expected.size() < 21) {
            if (cursor.equals(calendar.nextBusinessDay(cursor.minusDays(1)))) expected.add(cursor);
            cursor = cursor.minusDays(1);
        }
        var stored = new HashMap<LocalDate, GoldDailyBar>();
        for (GoldDailyBar bar : repository.findRecent(latest.symbol(), latest.provider(), day, 80)) {
            if (stored.putIfAbsent(bar.priceDate(), bar) != null) {
                throw new MarketDataUnavailableException("已保存黄金日线存在重复日期");
            }
            if (expected.contains(bar.priceDate()) && !bar.isConfirmedAt(now)) {
                throw new MarketDataUnavailableException("已保存窗口含未确认价格，不能自动盖章");
            }
        }
        List<LocalDate> missing = expected.stream().filter(date -> !stored.containsKey(date))
                .sorted().toList();
        if (missing.size() > maxMissing) {
            throw new MarketDataUnavailableException("近期黄金缺日超过" + maxMissing + "个，拒绝自动挑选部分历史");
        }
        // 周末/休市源日与窗口日分离；必须另外读取窗口日真实价格，不能借锚点改日期。
        List<LocalDate> requested = new ArrayList<>(missing);
        if (!sourceDay.equals(day) && !requested.contains(day)) requested.add(day);
        requested.sort(Comparator.naturalOrder());
        if (requested.size() > maxMissing) {
            throw new MarketDataUnavailableException("黄金补缺与窗口日核验超过" + maxMissing + "个日期，拒绝部分同步");
        }
        List<GoldDailyBar> received = new ArrayList<>();
        // 来源单次仍最多5日；批次间锚点必须相同，不能把早批成功当成整轮成功。
        for (int start = 0; start < requested.size(); start += 5) {
            var days = List.copyOf(requested.subList(start, Math.min(start + 5, requested.size())));
            var rows = provider.fetchDays(days);
            if (rows == null || rows.size() != days.size()
                    || !rows.stream().map(GoldDailyBar::priceDate).sorted().toList().equals(days)) {
                throw new MarketDataUnavailableException("黄金补缺返回日期与本批请求不一致");
            }
            received.addAll(rows);
        }
        if (received.size() != requested.size()
                || !received.stream().map(GoldDailyBar::priceDate).sorted().toList().equals(requested)) {
            throw new MarketDataUnavailableException("黄金补缺返回日期与请求不一致");
        }
        List<GoldDailyBar> batch = new ArrayList<>(received);
        if (sourceDay.equals(day) && !requested.contains(day)) batch.add(latest);
        for (GoldDailyBar bar : batch) {
            if (!bar.symbol().equals(latest.symbol()) || !bar.provider().equals(latest.provider())
                    || !bar.isConfirmedAt(OffsetDateTime.now(clock))
                    || !bar.confirmation().closedDay().equals(sourceDay)
                    // 指纹包含同日四项OHLC；锚点未返回时也不能混入修订后的批次。
                    || !bar.confirmation().receiptHash().equals(latest.confirmation().receiptHash())) {
                throw new MarketDataUnavailableException("黄金补缺标的或确认依据不匹配");
            }
            if (bar.priceDate().equals(sourceDay)
                    && (bar.open().compareTo(latest.open()) != 0 || bar.high().compareTo(latest.high()) != 0
                    || bar.low().compareTo(latest.low()) != 0 || bar.close().compareTo(latest.close()) != 0)) {
                throw new MarketDataUnavailableException("两次确认读取的锚点发生修订，拒绝混合批次");
            }
            stored.put(bar.priceDate(), bar);
        }
        if (!new GoldBarWindow(calendar).isContinuous(expected.stream().map(stored::get).toList())) {
            throw new MarketDataUnavailableException("黄金补缺后仍不是连续配置交易日窗口");
        }
        // 仓储在事务内处理并发冲突；同价幂等，不同价整批回滚。
        int saved = repository.saveMissing(batch);
        return new GoldDailyBarSyncResult(batch.size(), saved, 0, day);
    }
}
