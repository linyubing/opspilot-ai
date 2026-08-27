package com.opspilot.ai.analysis.history;

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
            assessment_status,
            rule_version,
            explanation,
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
                        new ResearchFactorAssessment(
                                GoldFactorStatus.valueOf(
                                        resultSet.getString(
                                                "assessment_status"
                                        ).toUpperCase(Locale.ROOT)
                                ),
                                resultSet.getString("rule_version"),
                                resultSet.getString("explanation")
                        ),
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

        String sql = """
                insert into gold_research_snapshot (
                    id,
                    analysis_date,
                    latest_gold_date,
                    latest_real_rate_date,
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
                    assessment_status,
                    rule_version,
                    explanation,
                    disclaimer,
                    created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (analysis_date, rule_version)
                do nothing
                """;

        int inserted = jdbcTemplate.update(
                sql,
                id,
                snapshot.analysisDate(),
                snapshot.latestGoldDate(),
                snapshot.latestRealRateDate(),
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
                snapshot.assessment().status()
                        .name()
                        .toLowerCase(Locale.ROOT),
                snapshot.assessment().ruleVersion(),
                snapshot.assessment().explanation(),
                snapshot.disclaimer(),
                createdAt
        );

        boolean created = inserted == 1;

        StoredGoldResearchSnapshot record = findByKey(
                snapshot.analysisDate(),
                snapshot.assessment().ruleVersion()
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

    /**
     * 按数据库幂等键读取最终保留下来的正式记录。
     */
    private Optional<StoredGoldResearchSnapshot> findByKey(
            LocalDate analysisDate,
            String ruleVersion
    ) {
        List<StoredGoldResearchSnapshot> records = jdbcTemplate.query(
                "select " + COLUMNS + """
                        from gold_research_snapshot
                        where analysis_date = ?
                          and rule_version = ?
                        """,
                rowMapper,
                analysisDate,
                ruleVersion
        );

        return records.stream().findFirst();
    }

    private static BigDecimal toBasisPoints(
            BigDecimal percentagePoints
    ) {
        return percentagePoints.multiply(new BigDecimal("100"));
    }
}
