package com.daniel.presentation.view;

import com.daniel.core.domain.entity.Flow;
import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.domain.entity.Transaction;
import com.daniel.core.domain.repository.IFlowRepository;
import com.daniel.core.domain.repository.IInvestmentTypeRepository;
import com.daniel.core.domain.repository.ISnapshotRepository;
import com.daniel.core.domain.repository.ITransactionRepository;
import com.daniel.core.service.DailyTrackingUseCase;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Shared stubs for view tests: a DailyTrackingUseCase without ledger and without data. */
public final class FxStubs {

    private FxStubs() {}

    public static DailyTrackingUseCase emptyDaily() {
        return dailyWithTypes(List.of());
    }

    /** Same legacy (no-ledger) wiring, but {@code listAll()} returns the given registrations. */
    public static DailyTrackingUseCase dailyWithTypes(List<InvestmentType> registrations) {
        IInvestmentTypeRepository types = new IInvestmentTypeRepository() {
            @Override public List<InvestmentType> listAll() { return registrations; }
            @Override public void save(String name) {}
            @Override public void rename(int id, String newName) {}
            @Override public void delete(int id) {}
        };
        IFlowRepository flows = new IFlowRepository() {
            @Override public List<Flow> listForDate(LocalDate date) { return List.of(); }
            @Override public void delete(long id) {}
            @Override public long create(Flow flow) { return 0; }
        };
        ISnapshotRepository snapshots = new ISnapshotRepository() {
            @Override public long getCash(LocalDate date) { return 0; }
            @Override public Map<Long, Long> getAllInvestimentsForDate(LocalDate date) { return Map.of(); }
            @Override public Map<String, Long> seriesForInvestiments(long investimentsTypeId) { return Map.of(); }
        };
        ITransactionRepository txs = new ITransactionRepository() {
            @Override public long insert(Transaction transaction) { return 0; }
            @Override public List<Transaction> listBetween(LocalDate start, LocalDate end) { return List.of(); }
        };
        return new DailyTrackingUseCase(flows, types, snapshots, txs, ticker -> null);
    }
}
