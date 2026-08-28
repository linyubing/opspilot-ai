package com.opspilot.ai.macrodata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class JdbcMacroObservationRepositoryTests {

    private static final String SERIES_ID = "DFII10_REPOSITORY_TEST";
    private static final OffsetDateTime FIRST_COLLECTED_AT =
            OffsetDateTime.parse("2026-08-20T01:00:00Z");
    private static final OffsetDateTime REVISED_AT =
            OffsetDateTime.parse("2026-08-20T03:00:00Z");

    @Autowired
    private MacroObservationRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanTestData() {
        jdbcTemplate.update(
                "delete from macro_observation where series_id = ?",
                SERIES_ID
        );
    }

    @Test
    @DisplayName("首次同步某日观测时创建当前版本")
    void insertsFirstVersion() {
        SaveObservationResult result = repository.save(
                observation("2026-08-19", "1.850000"),
                FIRST_COLLECTED_AT
        );

        assertThat(result).isEqualTo(SaveObservationResult.INSERTED);
        assertThat(repository.findLatest(SERIES_ID))
                .get()
                .extracting(MacroObservation::value)
                .isEqualTo(new BigDecimal("1.850000"));
    }

    @Test
    @DisplayName("相同观测值重复同步时不创建新版本")
    void keepsSameValueUnchanged() {
        repository.save(
                observation("2026-08-19", "1.850000"),
                FIRST_COLLECTED_AT
        );

        SaveObservationResult result = repository.save(
                observation("2026-08-19", "1.850"),
                REVISED_AT
        );

        assertThat(result).isEqualTo(SaveObservationResult.UNCHANGED);
        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("观测值发生修订时关闭旧版本并创建新版本")
    void preservesRevisionHistory() {
        repository.save(
                observation("2026-08-19", "1.850000"),
                FIRST_COLLECTED_AT
        );

        SaveObservationResult result = repository.save(
                observation("2026-08-19", "1.820000"),
                REVISED_AT
        );

        assertThat(result).isEqualTo(SaveObservationResult.REVISED);
        assertThat(countRows()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                """
                select superseded_at
                from macro_observation
                where series_id = ?
                  and observation_value = 1.850000
                """,
                OffsetDateTime.class,
                SERIES_ID
        )).isEqualTo(REVISED_AT);
        assertThat(repository.findLatest(SERIES_ID))
                .get()
                .extracting(MacroObservation::value)
                .isEqualTo(new BigDecimal("1.820000"));
    }

    @Test
    @DisplayName("最近观测查询只返回每个日期的当前版本")
    void findsOnlyCurrentRecentVersions() {
        repository.save(
                observation("2026-08-18", "1.900000"),
                FIRST_COLLECTED_AT
        );
        repository.save(
                observation("2026-08-19", "1.850000"),
                FIRST_COLLECTED_AT
        );
        repository.save(
                observation("2026-08-19", "1.820000"),
                REVISED_AT
        );

        assertThat(repository.findRecent(SERIES_ID, 10))
                .extracting(
                        MacroObservation::observationDate,
                        MacroObservation::value
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                LocalDate.parse("2026-08-19"),
                                new BigDecimal("1.820000")
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                LocalDate.parse("2026-08-18"),
                                new BigDecimal("1.900000")
                        )
                );
    }

    @Test
    @DisplayName("历史查询不返回截止日期之后的宏观观测")
    void excludesFutureObservations() {
        repository.save(
                observation("2026-08-19", "1.900000"),
                FIRST_COLLECTED_AT
        );
        repository.save(
                observation("2026-08-20", "1.850000"),
                FIRST_COLLECTED_AT
        );
        repository.save(
                observation("2026-08-21", "9.990000"),
                FIRST_COLLECTED_AT
        );

        assertThat(repository.findRecent(
                SERIES_ID,
                LocalDate.parse("2026-08-20"),
                10
        )).extracting(MacroObservation::observationDate)
                .containsExactly(
                        LocalDate.parse("2026-08-20"),
                        LocalDate.parse("2026-08-19")
                );
    }

    @Test
    @DisplayName("研究时间位于修订前后时返回各自当时可见的版本")
    void findsLatestVersionAsOfResearchTime() {
        repository.save(
                observation("2026-08-19", "1.850000"),
                FIRST_COLLECTED_AT
        );
        repository.save(
                observation("2026-08-19", "1.820000"),
                REVISED_AT
        );

        /*
         * 固定值只验证版本时间语义，不代表 FRED 的真实实时利率。
         */
        assertThat(repository.findLatestAsOf(
                SERIES_ID,
                FIRST_COLLECTED_AT.plusMinutes(30)
        )).get()
                .extracting(MacroObservation::value)
                .isEqualTo(new BigDecimal("1.850000"));

        assertThat(repository.findLatestAsOf(SERIES_ID, REVISED_AT))
                .get()
                .extracting(MacroObservation::value)
                .isEqualTo(new BigDecimal("1.820000"));
    }

    private IncomingMacroObservation observation(
            String date,
            String value
    ) {
        return new IncomingMacroObservation(
                SERIES_ID,
                LocalDate.parse(date),
                new BigDecimal(value),
                "percent",
                "fred"
        );
    }

    private int countRows() {
        return jdbcTemplate.queryForObject(
                "select count(*) from macro_observation where series_id = ?",
                Integer.class,
                SERIES_ID
        );
    }
}
