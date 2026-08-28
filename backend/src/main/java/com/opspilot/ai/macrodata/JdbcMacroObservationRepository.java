package com.opspilot.ai.macrodata;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcMacroObservationRepository
        implements MacroObservationRepository {

    /**
     * 统一维护查询字段，防止不同查询遗漏版本相关字段。
     */
    private static final String COLUMNS = """
            id,
            series_id,
            observation_date,
            observation_value,
            unit,
            provider,
            collected_at,
            superseded_at
            """;

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<MacroObservation> rowMapper =
            (resultSet, rowNum) -> new MacroObservation(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("series_id"),
                    resultSet.getObject(
                            "observation_date",
                            LocalDate.class
                    ),
                    resultSet.getBigDecimal(
                            "observation_value"
                    ),
                    resultSet.getString("unit"),
                    resultSet.getString("provider"),
                    resultSet.getObject(
                            "collected_at",
                            OffsetDateTime.class
                    ),
                    resultSet.getObject(
                            "superseded_at",
                            OffsetDateTime.class
                    )
            );

    public JdbcMacroObservationRepository(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public SaveObservationResult save(
            IncomingMacroObservation observation,
            OffsetDateTime collectedAt
    ) {
        /*
         * 先尝试插入当前版本。
         *
         * 部分唯一索引会阻止同一个序列、同一个日期同时存在
         * 两条 superseded_at 为空的记录。
         *
         * on conflict do nothing 还能处理两个同步请求
         * 同时首次写入同一天数据的并发情况。
         */
        int insertedRows = insertCurrentVersion(
                observation,
                collectedAt
        );

        if (insertedRows == 1) {
            return SaveObservationResult.INSERTED;
        }

        /*
         * 当前版本已经存在，因此加行锁后再比较数值。
         * 行锁能够避免两个修订请求同时关闭一个旧版本。
         */
        CurrentObservation current = findCurrentForUpdate(
                observation.seriesId(),
                observation.observationDate()
        );

        /*
         * BigDecimal.equals 会比较小数位数：
         * 1.850 和 1.850000 会被认为不同。
         *
         * compareTo 只比较实际数值，因此这里必须使用 compareTo。
         */
        if (current.value().compareTo(observation.value()) == 0) {
            return SaveObservationResult.UNCHANGED;
        }

        closeCurrentVersion(current.id(), collectedAt);
        int revisedRows = insertCurrentVersion(
                observation,
                collectedAt
        );

        if (revisedRows != 1) {
            /*
             * 抛出运行时异常后，Spring 会回滚整个事务，
             * 已经关闭的旧版本也会恢复。
             */
            throw new IllegalStateException(
                    "宏观观测新版本插入失败"
            );
        }

        return SaveObservationResult.REVISED;
    }

    /**
     * 关闭旧版本，但不删除它。
     */
    private void closeCurrentVersion(UUID id, OffsetDateTime supersededAt) {
        int updatedRows = jdbcTemplate.update(
                """
                update macro_observation
                set superseded_at = ?
                where id = ?
                  and superseded_at is null
                """,
                supersededAt,
                id
        );

        if (updatedRows != 1) {
            throw new IllegalStateException(
                    "宏观观测旧版本关闭失败"
            );
        }
    }

    /**
     * 查询并锁定指定日期的当前版本。
     *
     * for update 的锁会保持到当前事务提交或回滚。
     */
    private CurrentObservation findCurrentForUpdate(
            String seriesId,
            LocalDate observationDate
    ) {
        List<CurrentObservation> observations =
                jdbcTemplate.query(
                """
                select id, observation_value
                from macro_observation
                where series_id = ?
                  and observation_date = ?
                  and superseded_at is null
                for update
                """,
                (resultSet, rowNum) ->
                        new CurrentObservation(
                                resultSet.getObject(
                                        "id",
                                        UUID.class
                                ),
                                resultSet.getBigDecimal("observation_value")
                        ),
                seriesId,
                observationDate
        );

        return observations.stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "宏观观测当前版本不存在"
                ));
    }

    /**
     * 插入一个新的当前版本。
     *
     * 返回 1 表示插入成功，返回 0 表示当前版本已经存在。
     */
    private int insertCurrentVersion(
            IncomingMacroObservation observation,
            OffsetDateTime collectedAt
    ) {
        return jdbcTemplate.update(
                """
                insert into macro_observation (
                    id,
                    series_id,
                    observation_date,
                    observation_value,
                    unit,
                    provider,
                    collected_at,
                    superseded_at
                )
                values (?, ?, ?, ?, ?, ?, ?, null)
                on conflict (
                    series_id,
                    observation_date
                ) where superseded_at is null
                do nothing
                """,
                UUID.randomUUID(),
                observation.seriesId(),
                observation.observationDate(),
                observation.value(),
                observation.unit(),
                observation.provider(),
                collectedAt
        );
    }

    @Override
    public Optional<MacroObservation> findLatest(String seriesId) {
        List<MacroObservation> observations =
                jdbcTemplate.query(
                        "select " + COLUMNS + """
                                from macro_observation
                                where series_id = ?
                                  and superseded_at is null
                                order by observation_date desc,
                                         collected_at desc
                                limit 1
                                """,
                        rowMapper,
                        seriesId
                );

        return observations.stream().findFirst();
    }

    @Override
    public List<MacroObservation> findRecent(String seriesId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "limit 必须大于 0"
            );
        }

        return jdbcTemplate.query(
                "select " + COLUMNS + """
                        from macro_observation
                        where series_id = ?
                          and superseded_at is null
                        order by observation_date desc,
                                 collected_at desc
                        limit ?
                        """,
                rowMapper,
                seriesId,
                limit
        );
    }

    @Override
    public List<MacroObservation> findRecent(
            String seriesId,
            LocalDate endDate,
            int limit
    ) {
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "limit 必须大于 0"
            );
        }

        return jdbcTemplate.query(
                "select " + COLUMNS + """
                        from macro_observation
                        where series_id = ?
                          and observation_date <= ?
                          and superseded_at is null
                        order by observation_date desc,
                                 collected_at desc
                        limit ?
                        """,
                rowMapper,
                seriesId,
                endDate,
                limit
        );
    }

    @Override
    public Optional<MacroObservation> findLatestAsOf(
            String seriesId,
            OffsetDateTime researchTime
    ) {
        List<MacroObservation> observations =
                jdbcTemplate.query(
                        "select " + COLUMNS + """
                                from macro_observation
                                where series_id = ?
                                  and collected_at <= ?
                                  and (
                                      superseded_at is null
                                      or superseded_at > ?
                                  )
                                order by observation_date desc,
                                         collected_at desc
                                limit 1
                                """,
                        rowMapper,
                        seriesId,
                        researchTime,
                        researchTime
                );

        return observations.stream().findFirst();
    }

    /**
     * 保存行锁查询真正需要的两个字段，避免把完整领域对象当临时载体。
     */
    private record CurrentObservation(
            UUID id,
            BigDecimal value
    ) {
    }
}
