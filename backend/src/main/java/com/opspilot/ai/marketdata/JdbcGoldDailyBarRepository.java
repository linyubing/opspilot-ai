package com.opspilot.ai.marketdata;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 使用 PostgreSQL 持久化黄金 OHLC 日线。 */
@Repository
public class JdbcGoldDailyBarRepository implements GoldDailyBarRepository {

    private static final String COLUMNS = """
            symbol, price_date, open_price, high_price, low_price,
            close_price, currency, unit, provider, collected_at
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
                    resultSet.getObject("collected_at", OffsetDateTime.class)
            );

    public JdbcGoldDailyBarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveAll(List<GoldDailyBar> bars) {
        if (bars.isEmpty()) return;
        String sql = """
                insert into gold_daily_bar (
                    symbol, price_date, open_price, high_price, low_price,
                    close_price, currency, unit, provider, collected_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (provider, symbol, price_date)
                do update set
                    open_price = excluded.open_price,
                    high_price = excluded.high_price,
                    low_price = excluded.low_price,
                    close_price = excluded.close_price,
                    currency = excluded.currency,
                    unit = excluded.unit,
                    collected_at = excluded.collected_at
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
}
