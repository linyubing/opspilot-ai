package com.opspilot.ai.macrodata;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** 研究桥接：按生产D-1版本读取实际CPI同比，不修改正式特征类型。 */
public class CpiValues {
    public static Map<LocalDate, Double> read(Path archive, List<LocalDate> days) throws Exception {
        var json = new ObjectMapper();
        var history = new FredHistory("CPIAUCNS", Files.readAllBytes(archive), json);
        if (!history.hash.equals("818a139ddf6056c8fe2b2a728b5d2a79ab76a36a798cc5d0070f90e051f39814"))
            throw new IllegalStateException("真实通胀归档变化");
        var result = new HashMap<LocalDate, Double>();
        for (var day : days) {
            var rows = history.recent(day, 13);
            if (rows.isEmpty()) throw new IllegalStateException("通胀不可得");
            var current = rows.getFirst();
            var year = rows.stream().filter(r -> r.observationDate().equals(current.observationDate().minusYears(1)))
                    .findFirst().orElseThrow();
            double value = current.value().divide(year.value(), 16, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE).multiply(new BigDecimal("100")).doubleValue();
            if (!Double.isFinite(value)) throw new IllegalStateException("同比无效");
            result.put(day, value);
        }
        return Map.copyOf(result);
    }
}
