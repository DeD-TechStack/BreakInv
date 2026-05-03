package com.daniel.core.domain.repository;

import java.time.LocalDate;
import java.util.Map;

public interface ISnapshotRepository {
    long getCash(LocalDate date);

    /**
     * Returns the latest known cash balance on or before {@code date}, or 0 if no cash snapshot
     * exists on or before that date. Carries the cash balance forward across days with no entry.
     */
    default long getCashOnOrBefore(LocalDate date) { return 0L; }

    Map<Long, Long> getAllInvestimentsForDate(LocalDate date);
    Map<String, Long> seriesForInvestiments(long investimentsTypeId);

    /** Upsert the cash snapshot for a given date. */
    default void upsertCash(LocalDate date, long cashCents) {}

    /** Upsert an investment snapshot for a given date and investment type. */
    default void upsertInvestment(LocalDate date, long investmentTypeId,
                                  long valueCents, String note) {}
}
