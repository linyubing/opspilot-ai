package com.opspilot.ai.marketdata;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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

    /** 原子补入缺日；并发同值必须已有有效确认，任何修订或未确认冲突都拒绝。 */
    @Override
    @Transactional
    public int saveMissing(List<GoldDailyBar> bars) {
        var keys = new HashSet<BarKey>();
        for (var bar : bars) {
            if (bar == null || bar.confirmation() == null
                    || !bar.isConfirmedAt(bar.confirmation().checkedAt())) {
                throw new IllegalArgumentException("补入黄金日线必须有有效确认依据");
            }
            for (var price : List.of(bar.open(), bar.high(), bar.low(), bar.close())) {
                if (price.stripTrailingZeros().scale() > 8) {
                    throw new IllegalArgumentException("已确认黄金价格超出数据库精度，拒绝舍入");
                }
            }
            if (!keys.add(new BarKey(bar.provider(), bar.symbol(), bar.priceDate()))) {
                throw new IllegalArgumentException("补入黄金日线包含重复日期键");
            }
        }
        String sql = """
                insert into gold_daily_bar (
                    symbol, price_date, open_price, high_price, low_price,
                    close_price, currency, unit, provider, collected_at,
                    closed_day, confirmed_at, confirmation_source, confirmation_hash
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (provider, symbol, price_date) do nothing
                """;
        int inserted = 0;
        for (var bar : bars) {
            int changed;
            try {
                changed = jdbc.update(sql, statement -> {
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
                    statement.setObject(11, proof.closedDay());
                    statement.setObject(12, proof.checkedAt());
                    statement.setString(13, proof.source());
                    statement.setString(14, proof.receiptHash());
                });
            } catch (DataIntegrityViolationException exception) {
                // 冲突前的口径/价格约束失败也不能被当作已成功写入。
                throw new MarketDataUnavailableException("补入黄金日线违反数据约束", exception);
            }
            if (changed == 1) {
                inserted++;
                continue;
            }
            // do nothing 可能等待并发事务提交；另一次查询锁住最终可见的冲突行。
            var existing = jdbc.query("select " + COLUMNS + """
                    from gold_daily_bar
                    where provider = ? and symbol = ? and price_date = ?
                    for update
                    """, mapper, bar.provider(), bar.symbol(), bar.priceDate())
                    .stream().findFirst()
                    .orElseThrow(() -> new MarketDataUnavailableException("补入黄金日线冲突记录不可见"));
            if (!sameConfirmedPrices(existing, bar)) {
                throw new MarketDataUnavailableException("补入黄金日线与旧价格或确认状态冲突");
            }
        }
        return inserted;
    }

    /** 仅比较价格口径；原记录的采集时间与确认依据必须保持不变。 */
    private boolean sameConfirmedPrices(GoldDailyBar existing, GoldDailyBar incoming) {
        return existing.confirmation() != null
                && existing.isConfirmedAt(existing.confirmation().checkedAt())
                && existing.open().compareTo(incoming.open()) == 0
                && existing.high().compareTo(incoming.high()) == 0
                && existing.low().compareTo(incoming.low()) == 0
                && existing.close().compareTo(incoming.close()) == 0
                && Objects.equals(existing.currency(), incoming.currency())
                && Objects.equals(existing.unit(), incoming.unit());
    }

    /** 与数据库主键一致的日线标识，不把日期相同的不同品种混为一条。 */
    private record BarKey(String provider, String symbol, LocalDate priceDate) { }

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
                    collected_at = case when gold_daily_bar.confirmation_hash is null
                        then excluded.collected_at else gold_daily_bar.collected_at end,
                    closed_day = case when gold_daily_bar.confirmation_hash is null
                        then excluded.closed_day else gold_daily_bar.closed_day end,
                    confirmed_at = case when gold_daily_bar.confirmation_hash is null
                        then excluded.confirmed_at else gold_daily_bar.confirmed_at end,
                    confirmation_source = case when gold_daily_bar.confirmation_hash is null
                        then excluded.confirmation_source else gold_daily_bar.confirmation_source end,
                    confirmation_hash = case when gold_daily_bar.confirmation_hash is null
                        then excluded.confirmation_hash else gold_daily_bar.confirmation_hash end
                where gold_daily_bar.confirmation_hash is null
                   or (gold_daily_bar.open_price = excluded.open_price
                       and gold_daily_bar.high_price = excluded.high_price
                       and gold_daily_bar.low_price = excluded.low_price
                       and gold_daily_bar.close_price = excluded.close_price
                       and gold_daily_bar.currency = excluded.currency
                       and gold_daily_bar.unit = excluded.unit)
                """;
        int[][] changed = jdbc.batchUpdate(sql, bars, bars.size(), (statement, bar) -> {
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
            // 仅未确认旧行可更新；已确认同价重放由SQL保留原采集时间和确认依据。
            statement.setObject(11, proof == null ? null : proof.closedDay());
            statement.setObject(12, proof == null ? null : proof.checkedAt());
            statement.setString(13, proof == null ? null : proof.source());
            statement.setString(14, proof == null ? null : proof.receiptHash());
        });
        // where冲突分支返回0行，必须抛错使之前写入的新行也回滚。
        // JDBC若只返回“成功但数量未知”，同样不能假定整批已按约定完成。
        for (int[] batch : changed) {
            for (int rows : batch) {
                if (rows != 1) {
                    throw new MarketDataUnavailableException("黄金全量同步存在已确认价格冲突或无法确认写入结果，整批拒绝保存");
                }
            }
        }
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
