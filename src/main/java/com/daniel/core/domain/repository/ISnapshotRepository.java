package com.daniel.core.domain.repository;

import java.time.LocalDate;
import java.util.Map;

public interface ISnapshotRepository {
    long getCash(LocalDate date);
    void setCash(LocalDate date);
    Map<Long, Long> getAllInvestimentsForDate(LocalDate date);
    void setInvestimentValue(LocalDate date, long typeId, long cents);
    Map<String, Long> seriesForInvestiments(long investimentsTypeId);

    /** Upsert the cash snapshot for a given date. */
    default void upsertCash(LocalDate date, long cashCents) {}

    /** Upsert an investment snapshot for a given date and investment type. */
    default void upsertInvestment(LocalDate date, long investmentTypeId,
                                  long valueCents, String note) {}
}
