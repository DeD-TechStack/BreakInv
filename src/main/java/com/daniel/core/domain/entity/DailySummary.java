package com.daniel.core.domain.entity;

import java.time.LocalDate;
import java.util.Map;

public record DailySummary(
        LocalDate date,
        long totalTodayCents,
        long totalProfitTodayCents,
        long cashTodayCents,
        long cashDeltaCents,
        Map<Long, Long> investmentTodayCents,
        Map<Long, Long> investmentProfitTodayCents
) {}
