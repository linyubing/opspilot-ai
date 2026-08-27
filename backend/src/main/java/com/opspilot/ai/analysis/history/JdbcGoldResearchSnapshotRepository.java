package com.opspilot.ai.analysis.history;

import com.opspilot.ai.analysis.DollarIndexChangeMetrics;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用 PostgreSQL 保存不可变黄金研究快照，并依靠唯一约束保证并发幂等。
 */
@Repository
public class JdbcGoldResearchSnapshotRepository
        implements GoldResearchSnapshotRepository {

    private static final String COLUMNS = """
            id,
            analysis_date,
            latest_gold_date,
            latest_real_rate_date,
            latest_dollar_index_date,
            gold_price,
            gold_return_1,
            gold_return_5,
            gold_return_20,
            gold_collected_at,
            real_rate,
            real_rate_change_1,
            real_rate_change_5,
            real_rate_change_20,
            real_rate_collected_at,
            real_rate_status,
            real_rate_rule_version,
            real_rate_explanation,
            dollar_index,
            dollar_index_return_1,
            dollar_index_return_5,
            dollar_index_return_20,
            dollar_index_collected_at,
            dollar_index_status,
            dollar_index_rule_version,
            dollar_index_explanation,
            research_version,
            disclaimer,
            created_at
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 将数据库行还原成不可变研究快照；基点变化由百分点变化确定性换算。
     */
    private final RowMapper<StoredGoldResearchSnapshot> rowMapper =
            (resultSet, rowNum) -> {
                BigDecimal change1 =
                        resultSet.getBigDecimal("real_rate_change_1");
                BigDecimal change5 =
                        resultSet.getBigDecimal("real_rate_change_5");
                BigDecimal change20 =
                        resultSet.getBigDecimal("real_rate_change_20");
                LocalDate latestDollarIndexDate = resultSet.getObject(
                        "latest_dollar_index_date",
                        LocalDate.class
                );

                DollarIndexChangeMetrics dollarIndex = null;
                ResearchFactorAssessment dollarIndexAssessment = null;
                if (latestDollarIndexDate != null) {
                    dollarIndex = new DollarIndexChangeMetrics(
                            resultSet.getBigDecimal("dollar_index"),
                            resultSet.getBigDecimal("dollar_index_return_1"),
                            resultSet.getBigDecimal("dollar_index_return_5"),
                            resultSet.getBigDecimal("dollar_index_return_20"),
                            resultSet.getObject(
                                    "dollar_index_collected_at",
                                    OffsetDateTime.class
                            )
                    );
                    dollarIndexAssessment = new ResearchFactorAssessment(
                            toFactorStatus(resultSet.getString(
                                    "dollar_index_status"
                            )),
                            resultSet.getString("dollar_index_rule_version"),
                            resultSet.getString("dollar_index_explanation")
                    );
                }

                GoldResearchSnapshot snapshot = new GoldResearchSnapshot(
                        resultSet.getObject(
                                "analysis_date",
                                LocalDate.class
                        ),
                        resultSet.getObject(
                                "latest_gold_date",
                                LocalDate.class
                        ),
                        resultSet.getObject(
                                "latest_real_rate_date",
                                LocalDate.class
                        ),
                        latestDollarIndexDate,
                        new GoldReturnMetrics(
                                resultSet.getBigDecimal("gold_price"),
                                resultSet.getBigDecimal("gold_return_1"),
                                resultSet.getBigDecimal("gold_return_5"),
                                resultSet.getBigDecimal("gold_return_20"),
                                resultSet.getObject(
                                        "gold_collected_at",
                                        OffsetDateTime.class
                                )
                        ),
                        new RealRateChangeMetrics(
                                resultSet.getBigDecimal("real_rate"),
                                change1,
                                change5,
                                change20,
                                toBasisPoints(change1),
                                toBasisPoints(change5),
                                toBasisPoints(change20),
                                resultSet.getObject(
                                        "real_rate_collected_at",
                                        OffsetDateTime.class
                                )
                        ),
                        dollarIndex,
                        new ResearchFactorAssessment(
                                toFactorStatus(resultSet.getString(
                                        "real_rate_status"
                                )),
                                resultSet.getString(
                                        "real_rate_rule_version"
                                ),
                                resultSet.getString("real_rate_explanation")
                        ),
                        dollarIndexAssessment,
                        resultSet.getString("research_version"),
                        resultSet.getString("disclaimer")
                );

                return new StoredGoldResearchSnapshot(
                        resultSet.getObject("id", UUID.class),
                        snapshot,
                        resultSet.getObject(
                                "created_at",
                                OffsetDateTime.class
                        )
                );
            };

    public JdbcGoldResearchSnapshotRepository(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public SaveGoldResearchSnapshotResult saveIfAbsent(
            GoldResearchSnapshot snapshot,
            OffsetDateTime createdAt
    ) {
        UUID id = UUID.randomUUID();
        DollarIndexChangeMetrics dollarIndex = snapshot.dollarIndex();
        ResearchFactorAssessment dollarAssessment =
                snapshot.dollarIndexAssessment();

        String sql = """
                insert into gold_research_snapshot (
                    id,
                    analysis_date,
                    latest_gold_date,
                    latest_real_rate_date,
                    latest_dollar_index_date,
                    gold_price,
                    gold_return_1,
                    gold_return_5,
                    gold_return_20,
                    gold_collected_at,
                    real_rate,
                    real_rate_change_1,
                    real_rate_change_5,
                    real_rate_change_20,
                    real_rate_collected_at,
                    real_rate_status,
                    real_rate_rule_version,
                    real_rate_explanation,
                    dollar_index,
                    dollar_index_return_1,
                    dollar_index_return_5,
                    dollar_index_return_20,
                    dollar_index_collected_at,
                    dollar_index_status,
                    dollar_index_rule_version,
                    dollar_index_explanation,
                    research_version,
                    disclaimer,
                    created_at
                )
                values (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                on conflict (analysis_date, research_version)
                do nothing
                """;

        int inserted = jdbcTemplate.update(
                sql,
                id,
                snapshot.analysisDate(),
                snapshot.latestGoldDate(),
                snapshot.latestRealRateDate(),
                snapshot.latestDollarIndexDate(),
                snapshot.gold().currentPrice(),
                snapshot.gold().return1(),
                snapshot.gold().return5(),
                snapshot.gold().return20(),
                snapshot.gold().collectedAt(),
                snapshot.realRate().currentRate(),
                snapshot.realRate().percentagePointChange1(),
                snapshot.realRate().percentagePointChange5(),
                snapshot.realRate().percentagePointChange20(),
                snapshot.realRate().collectedAt(),
                snapshot.realRateAssessment().status()
                        .name()
                        .toLowerCase(Locale.ROOT),
                snapshot.realRateAssessment().ruleVersion(),
                snapshot.realRateAssessment().explanation(),
                dollarIndex == null ? null : dollarIndex.currentIndex(),
                dollarIndex == null ? null : dollarIndex.return1(),
                dollarIndex == null ? null : dollarIndex.return5(),
                dollarIndex == null ? null : dollarIndex.return20(),
                dollarIndex == null ? null : dollarIndex.collectedAt(),
                dollarAssessment == null
                        ? null
                        : dollarAssessment.status()
                                .name()
                                .toLowerCase(Locale.ROOT),
                dollarAssessment == null
                        ? null
                        : dollarAssessment.ruleVersion(),
                dollarAssessment == null
                        ? null
                        : dollarAssessment.explanation(),
                snapshot.researchVersion(),
                snapshot.disclaimer(),
                createdAt
        );

        boolean created = inserted == 1;

        StoredGoldResearchSnapshot record = findByKey(
                snapshot.analysisDate(),
                snapshot.researchVersion()
        ).orElseThrow(() -> new IllegalStateException(
                "黄金研究快照保存后未能读取"
        ));

        return new SaveGoldResearchSnapshotResult(
                record,
                created
        );
    }

    @Override
    public List<StoredGoldResearchSnapshot> findRecent(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "limit 必须在 1 到 100 之间"
            );
        }

        return jdbcTemplate.query(
                "select " + COLUMNS + """
                        from gold_research_snapshot
                        order by analysis_date desc, created_at desc
                        limit ?
                        """,
                rowMapper,
                limit
        );
    }

    @Override
    public Optional<StoredGoldResearchSnapshot> findById(UUID id) {
        Objects.requireNonNull(id, "id 不能为空");

        List<StoredGoldResearchSnapshot> records = jdbcTemplate.query(
                "select " + COLUMNS + """
                        from gold_research_snapshot
                        where id = ?
                        """,
                rowMapper,
                id
        );

        return records.stream().findFirst();
    }

    /**
     * 按数据库幂等键读取最终保留下来的正式记录。
     */
    private Optional<StoredGoldResearchSnapshot> findByKey(
            LocalDate analysisDate,
            String researchVersion
    ) {
        List<StoredGoldResearchSnapshot> records = jdbcTemplate.query(
                "select " + COLUMNS + """
                        from gold_research_snapshot
                        where analysis_date = ?
                          and research_version = ?
                        """,
                rowMapper,
                analysisDate,
                researchVersion
        );

        return records.stream().findFirst();
    }

    private static BigDecimal toBasisPoints(
            BigDecimal percentagePoints
    ) {
        return percentagePoints.multiply(new BigDecimal("100"));
    }

    private static GoldFactorStatus toFactorStatus(String databaseValue) {
        return GoldFactorStatus.valueOf(
                databaseValue.toUpperCase(Locale.ROOT)
        );
    }
}
