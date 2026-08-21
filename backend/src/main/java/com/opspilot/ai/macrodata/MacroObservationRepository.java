package com.opspilot.ai.macrodata;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface MacroObservationRepository {

    SaveObservationResult save(
            IncomingMacroObservation observation,
            OffsetDateTime collectedAt
    );

    Optional<MacroObservation> findLatest(String seriesId);

    List<MacroObservation> findRecent(String seriesId, int limit);

    Optional<MacroObservation> findLatestAsOf(
            String seriesId,
            OffsetDateTime researchTime
    );
}
