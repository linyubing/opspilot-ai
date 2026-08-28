package com.opspilot.ai.macrodata;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MacroObservationRepository {

    SaveObservationResult save(
            IncomingMacroObservation observation,
            OffsetDateTime collectedAt
    );

    Optional<MacroObservation> findLatest(String seriesId);

    List<MacroObservation> findRecent(String seriesId, int limit);

    default List<MacroObservation> findRecent(
            String seriesId,
            LocalDate endDate,
            int limit
    ) {
        return findRecent(seriesId, limit);
    }

    Optional<MacroObservation> findLatestAsOf(
            String seriesId,
            OffsetDateTime researchTime
    );
}
