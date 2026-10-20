package com.opspilot.ai.marketdata;

import com.opspilot.ai.forecast.ConfiguredGoldTradingCalendar;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 验证长时间停机后的有界恢复，不将分批请求变成部分落库。 */
class GoldRecentRecoveryTests {
    final TwelveDataGoldBarProvider provider = mock(TwelveDataGoldBarProvider.class);
    final GoldDailyBarRepository repository = mock(GoldDailyBarRepository.class);
    final LocalDate end = LocalDate.parse("2026-10-20");
    final OffsetDateTime now = OffsetDateTime.parse("2026-10-21T12:00:00Z");
    final GoldDailyBarSyncService service = new GoldDailyBarSyncService(provider, repository,
            new ConfiguredGoldTradingCalendar(), Clock.fixed(now.toInstant(), ZoneOffset.UTC));

    void prepare() {
        prepare(8);
    }

    void prepare(int missing) {
        var days = new ArrayList<LocalDate>();
        for (LocalDate d=end; days.size()<21; d=d.minusDays(1)) {
            if(d.getDayOfWeek()!=DayOfWeek.SATURDAY && d.getDayOfWeek()!=DayOfWeek.SUNDAY) days.add(d);
        }
        when(provider.fetchLatestBar()).thenReturn(bar(end,"a"));
        when(repository.findRecent("XAUUSD","twelve_data",end,80))
                .thenReturn(days.subList(missing,21).stream().map(d->bar(d,"a")).toList());
    }

    GoldDailyBar bar(LocalDate day,String hash) {
        return new GoldDailyBar("XAUUSD",day,new BigDecimal("100"),new BigDecimal("101"),
                new BigDecimal("99"),new BigDecimal("100"),"usd","troy_ounce","twelve_data",now,
                new GoldBarConfirmation(GoldBarConfirmation.SOURCE,end,now,hash.repeat(64)));
    }

    @Test
    void automaticSyncStillRefusesLargeGap() {
        prepare();
        assertThatThrownBy(service::syncRecent).hasMessageContaining("超过5个");
        verify(provider,never()).fetchDays(anyList());
        verify(repository,never()).saveMissing(anyList());
    }

    @Test
    void recoveryReadsBoundedBatchesAndSavesOnce() {
        prepare();
        var sizes = new ArrayList<Integer>();
        when(provider.fetchDays(anyList())).thenAnswer(call->{
            List<LocalDate> days=call.getArgument(0); sizes.add(days.size());
            return days.stream().map(d->bar(d,"a")).toList();
        });
        when(repository.saveMissing(anyList())).thenAnswer(call->((List<?>)call.getArgument(0)).size());
        var result=service.recoverRecent();
        assertThat(sizes).containsExactly(5,3);
        assertThat(result.savedCount()).isEqualTo(8);
        verify(repository,times(1)).saveMissing(argThat(rows->rows.size()==8));
    }

    @Test
    void laterBatchFailureNeverSavesEarlierBatch() {
        prepare();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(provider.fetchDays(anyList())).thenAnswer(call->{
            if(calls.incrementAndGet()==2) throw new MarketDataUnavailableException("第二批失败");
            List<LocalDate> days=call.getArgument(0);
            return days.stream().map(d->bar(d,"a")).toList();
        });
        assertThatThrownBy(service::recoverRecent).hasMessageContaining("第二批失败");
        verify(repository,never()).saveMissing(anyList());
    }

    @Test
    void changedReceiptInLaterBatchRefusesEntireRecovery() {
        prepare();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(provider.fetchDays(anyList())).thenAnswer(call->{
            String hash=calls.incrementAndGet()==1 ? "a":"b";
            List<LocalDate> days=call.getArgument(0);
            return days.stream().map(d->bar(d,hash)).toList();
        });
        assertThatThrownBy(service::recoverRecent).hasMessageContaining("确认依据不匹配");
        verify(repository,never()).saveMissing(anyList());
    }

    @Test
    void entireWindowRemainsBoundedToTwentyOneDates() {
        prepare(21);
        var sizes = new ArrayList<Integer>();
        when(provider.fetchDays(anyList())).thenAnswer(call->{
            List<LocalDate> days=call.getArgument(0); sizes.add(days.size());
            return days.stream().map(d->bar(d,"a")).toList();
        });
        when(repository.saveMissing(anyList())).thenReturn(21);
        assertThat(service.recoverRecent().savedCount()).isEqualTo(21);
        assertThat(sizes).containsExactly(5,5,5,5,1);
    }

    @Test
    void wrongDateInLaterBatchRefusesEntireRecovery() {
        prepare();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(provider.fetchDays(anyList())).thenAnswer(call->{
            List<LocalDate> days=call.getArgument(0);
            return calls.incrementAndGet()==1 ? days.stream().map(d->bar(d,"a")).toList()
                    : days.stream().map(d->bar(d.minusDays(30),"a")).toList();
        });
        assertThatThrownBy(service::recoverRecent).hasMessageContaining("本批请求不一致");
        verify(repository,never()).saveMissing(anyList());
    }
}
