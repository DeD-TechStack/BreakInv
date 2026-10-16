package com.daniel.core.service;

import com.daniel.core.domain.entity.InvestmentType;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotCompletenessTest {
    @Test
    void refreshesEveryPositionEvenAfterAnotherSnapshotExistsToday() {
        var types = new DailyTrackingUseCaseTest.StubTypeRepo();
        var snapshots = new DailyTrackingUseCaseTest.StubSnapshotRepo();
        LocalDate date = LocalDate.now();
        snapshots.putInvestment(date, 1, 100);
        types.add(position(1, 200));
        types.add(position(2, 300));
        var useCase = new DailyTrackingUseCase(new DailyTrackingUseCaseTest.StubFlowRepo(),
                types, snapshots, new DailyTrackingUseCaseTest.StubTxRepo(), ticker -> null);
        useCase.takeSnapshotIfNeeded(date);
        assertNotNull(snapshots.upsertedInvestments.get(date));
        assertEquals(200L, snapshots.upsertedInvestments.get(date).get(1L));
        assertEquals(300L, snapshots.upsertedInvestments.get(date).get(2L));
    }

    @Test
    void storesExplicitZeroForClosedPositionAndRepeatedRefreshIsStable() {
        var types = new DailyTrackingUseCaseTest.StubTypeRepo();
        var snapshots = new DailyTrackingUseCaseTest.StubSnapshotRepo();
        types.add(position(1, 0));
        var useCase = new DailyTrackingUseCase(new DailyTrackingUseCaseTest.StubFlowRepo(),
                types, snapshots, new DailyTrackingUseCaseTest.StubTxRepo(), ticker -> null);
        useCase.takeSnapshotIfNeeded(LocalDate.now());
        useCase.takeSnapshotIfNeeded(LocalDate.now());
        assertEquals(java.util.Map.of(1L, 0L), snapshots.upsertedInvestments.get(LocalDate.now()));
    }

    @Test
    void neverRebuildsHistoricalSnapshotUsingTodaysPosition() {
        var types = new DailyTrackingUseCaseTest.StubTypeRepo();
        var snapshots = new DailyTrackingUseCaseTest.StubSnapshotRepo();
        types.add(position(1, 200));
        LocalDate yesterday = LocalDate.now().minusDays(1);
        snapshots.putInvestment(yesterday, 1, 100);
        var useCase = new DailyTrackingUseCase(new DailyTrackingUseCaseTest.StubFlowRepo(),
                types, snapshots, new DailyTrackingUseCaseTest.StubTxRepo(), ticker -> null);
        useCase.takeSnapshotIfNeeded(yesterday);
        assertTrue(snapshots.upsertedInvestments.isEmpty());
        assertEquals(100L, snapshots.getAllInvestimentsForDate(yesterday).get(1L));
    }

    private InvestmentType position(int id, long cents) {
        return new InvestmentType(id, "Synthetic " + id, "OUTROS", "ALTA", LocalDate.now(),
                null, BigDecimal.valueOf(cents, 2), "OUTRO", null, null, null, null, null, null);
    }
}
