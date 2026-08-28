package com.opspilot.ai.marketdata;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcMarketPriceRepository implements MarketPriceRepository {

    /**
     * 统一维护查询字段，避免不同查询遗漏字段。
     */
    private static final String COLUMNS = """
            symbol,
            price_date,
            reference_price,
            currency,
            unit,
            provider,
            collected_at
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 把数据库的一行数据转换成 MarketPrice。
     */
    private final RowMapper<MarketPrice> rowMapper = (resultSet, rowNum) ->
            new MarketPrice(
                    resultSet.getString("symbol"),
                    resultSet.getObject("price_date", LocalDate.class),
                    resultSet.getBigDecimal("reference_price"),
                    resultSet.getString("currency"),
                    resultSet.getString("unit"),
                    resultSet.getString("provider"),
                    resultSet.getObject(
                            "collected_at",
                            OffsetDateTime.class
                    )
            );

    public JdbcMarketPriceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void saveAll(List<MarketPrice> prices) {
        if (prices.isEmpty()) {
            return;
        }

        String sql = """
                insert into market_price (
                    symbol,
                    price_date,
                    reference_price,
                    currency,
                    unit,
                    provider,
                    collected_at
                )
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (provider, symbol, price_date)
                do update set
                    reference_price = excluded.reference_price,
                    currency = excluded.currency,
                    unit = excluded.unit,
                    collected_at = excluded.collected_at
                """;

        /*
         * batchUpdate 会把多条价格组成一批提交，
         * 避免每条价格都单独访问一次数据库。
         */
        jdbcTemplate.batchUpdate(
                sql,
                prices,
                prices.size(),
                (statement, price) -> {
                    statement.setString(1, price.symbol());
                    statement.setObject(2, price.priceDate());
                    statement.setBigDecimal(3, price.referencePrice());
                    statement.setString(4, price.currency());
                    statement.setString(5, price.unit());
                    statement.setString(6, price.provider());
                    statement.setObject(7, price.collectedAt());
                }
        );
    }

    @Override
    public Optional<MarketPrice> findLatest(String symbol) {
        List<MarketPrice> prices = jdbcTemplate.query(
                "select " + COLUMNS + """
                        from market_price
                        where symbol = ?
                        order by price_date desc
                        limit 1
                        """,
                rowMapper,
                symbol
        );

        return prices.stream().findFirst();
    }

    @Override
    public List<MarketPrice> findRecent(String symbol, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }

        return jdbcTemplate.query(
                "select " + COLUMNS + """
                        from market_price
                        where symbol = ?
                        order by price_date desc
                        limit ?
                        """,
                rowMapper,
                symbol,
                limit
        );
    }

    @Override
    public List<MarketPrice> findRecent(
            String symbol,
            LocalDate endDate,
            int limit
    ) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }

        return jdbcTemplate.query(
                "select " + COLUMNS + """
                        from market_price
                        where symbol = ?
                          and price_date <= ?
                        order by price_date desc
                        limit ?
                        """,
                rowMapper,
                symbol,
                endDate,
                limit
        );
    }

    @Override
    public List<MarketPrice> findAfter(String symbol, LocalDate baseDate, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit 必须在 1 到 100 之间");
        }

        return jdbcTemplate.query(
                "select " + COLUMNS + """
                        from market_price
                        where symbol = ?
                          and price_date > ?
                        order by price_date asc
                        limit ?
                        """,
                rowMapper,
                symbol,
                baseDate,
                limit
        );
    }
}
