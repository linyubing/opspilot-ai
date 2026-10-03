package com.opspilot.ai.marketdata;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 使用 PostgreSQL 持久化黄金 OHLC 日线。 */
@Repository
public class JdbcGoldDailyBarRepository implements GoldDailyBarRepository {

    private static final String COLUMNS = """
            symbol, price_date, open_price, high_price, low_price,
            close_price, currency, unit, provider, collected_at,
            closed_day, confirmed_at, confirmation_source, confirmation_hash
            """;

    private final JdbcTemplate jdbc;
    private final RowMapper<GoldDailyBar> mapper = (resultSet, rowNum) ->
            new GoldDailyBar(
                    resultSet.getString("symbol"),
                    resultSet.getObject("price_date", LocalDate.class),
                    resultSet.getBigDecimal("open_price"),
                    resultSet.getBigDecimal("high_price"),
                    resultSet.getBigDecimal("low_price"),
                    resultSet.getBigDecimal("close_price"),
                    resultSet.getString("currency"),
                    resultSet.getString("unit"),
                    resultSet.getString("provider"),
                    resultSet.getObject("collected_at", OffsetDateTime.class),
                    resultSet.getObject("closed_day", LocalDate.class) == null ? null :
                            new GoldBarConfirmation(resultSet.getString("confirmation_source"),
                                    resultSet.getObject("closed_day", LocalDate.class),
                                    resultSet.getObject("confirmed_at", OffsetDateTime.class),
                                    resultSet.getString("confirmation_hash"))
            );

    public JdbcGoldDailyBarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void saveAll(List<GoldDailyBar> bars) {
        if (bars.isEmpty()) return;
        // 入库前拒绝确认关系无效、或 numeric(19,8) 将悄悄舍入的价格。
        for (GoldDailyBar bar : bars) {
            if (bar.confirmation() != null) {
                if (!bar.isConfirmedAt(bar.confirmation().checkedAt())) {
                    throw new IllegalArgumentException("黄金日线确认依据无效");
                }
                for (var price : List.of(bar.open(), bar.high(), bar.low(), bar.close())) {
                    if (price.stripTrailingZeros().scale() > 8) {
                        throw new IllegalArgumentException("已确认黄金价格超出数据库精度，拒绝舍入");
                    }
                }
            }
        }
        String sql = """
                insert into gold_daily_bar (
                    symbol, price_date, open_price, high_price, low_price,
                    close_price, currency, unit, provider, collected_at,
                    closed_day, confirmed_at, confirmation_source, confirmation_hash
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (provider, symbol, price_date)
                do update set
                    open_price = excluded.open_price,
                    high_price = excluded.high_price,
                    low_price = excluded.low_price,
                    close_price = excluded.close_price,
                    currency = excluded.currency,
                    unit = excluded.unit,
                    collected_at = excluded.collected_at,
                    closed_day = excluded.closed_day,
                    confirmed_at = excluded.confirmed_at,
                    confirmation_source = excluded.confirmation_source,
                    confirmation_hash = excluded.confirmation_hash
                """;
        jdbc.batchUpdate(sql, bars, bars.size(), (statement, bar) -> {
            statement.setString(1, bar.symbol());
            statement.setObject(2, bar.priceDate());
            statement.setBigDecimal(3, bar.open());
            statement.setBigDecimal(4, bar.high());
            statement.setBigDecimal(5, bar.low());
            statement.setBigDecimal(6, bar.close());
            statement.setString(7, bar.currency());
            statement.setString(8, bar.unit());
            statement.setString(9, bar.provider());
            statement.setObject(10, bar.collectedAt());
            var proof = bar.confirmation();
            // 未确认覆盖必须清空旧依据，不能把新价格挂到旧确认上。
            statement.setObject(11, proof == null ? null : proof.closedDay());
            statement.setObject(12, proof == null ? null : proof.checkedAt());
            statement.setString(13, proof == null ? null : proof.source());
            statement.setString(14, proof == null ? null : proof.receiptHash());
        });
    }

    @Override
    public Optional<GoldDailyBar> findLatest(
            String symbol,
            String provider
    ) {
        return jdbc.query(
                "select " + COLUMNS + """
                        from gold_daily_bar
                        where symbol = ? and provider = ?
                        order by price_date desc
                        limit 1
                        """,
                mapper,
                symbol,
                provider
        ).stream().findFirst();
    }

    @Override
    public List<GoldDailyBar> findRecent(
            String symbol,
            String provider,
            int limit
    ) {
        return jdbc.query(
                "select " + COLUMNS + """
                        from gold_daily_bar
                        where symbol = ? and provider = ?
                        order by price_date desc
                        limit ?
                        """,
                mapper,
                symbol,
                provider,
                limit
        );
    }

    @Override
    public List<GoldDailyBar> findRecent(
            String symbol,
            String provider,
            LocalDate endDate,
            int limit
    ) {
        return jdbc.query(
                "select " + COLUMNS + """
                        from gold_daily_bar
                        where symbol = ?
                          and provider = ?
                          and price_date <= ?
                        order by price_date desc
                        limit ?
                        """,
                mapper,
                symbol,
                provider,
                endDate,
                limit
        );
    }

    @Override
    public List<GoldDailyBar> findAll(
            String symbol,
            String provider
    ) {
        return jdbc.query(
                "select " + COLUMNS + """
                        from gold_daily_bar
                        where symbol = ? and provider = ?
                        order by price_date
                        """,
                mapper,
                symbol,
                provider
        );
    }

    @Override
    public Optional<GoldDailyBar> findNext(
            String symbol,
            String provider,
            LocalDate baseDate
    ) {
        return jdbc.query(
                "select " + COLUMNS + """
                        from gold_daily_bar
                        where symbol = ?
                          and provider = ?
                          and price_date > ?
                        order by price_date
                        limit 1
                        """,
                mapper,
                symbol,
                provider,
                baseDate
        ).stream().findFirst();
    }

    @Override
    public List<GoldDailyBar> findAfter(
            String symbol,
            String provider,
            LocalDate baseDate,
            int limit
    ) {
        return jdbc.query(
                "select " + COLUMNS + """
                        from gold_daily_bar
                        where symbol = ?
                          and provider = ?
                          and price_date > ?
                        order by price_date
                        limit ?
                        """,
                mapper,
                symbol,
                provider,
                baseDate,
                limit
        );
    }
}
