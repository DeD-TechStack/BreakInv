package com.daniel.core.service;

import com.daniel.core.domain.ledger.LedgerStore;
import com.daniel.core.domain.ledger.OpeningRequest;
import com.daniel.core.domain.ledger.PortfolioRepository;
import com.daniel.core.domain.repository.IInvestmentTypeRepository;
import com.daniel.core.domain.repository.IStockPriceProvider;
import com.daniel.core.domain.repository.ITransactionRepository;
import com.daniel.infrastructure.persistence.repository.DatabaseLedgerAccess;
import com.daniel.infrastructure.persistence.repository.DatabaseLedgerStore;
import com.daniel.infrastructure.persistence.repository.FlowRepository;
import com.daniel.infrastructure.persistence.repository.InvestmentTypeRepository;
import com.daniel.infrastructure.persistence.repository.SnapshotRepository;
import com.daniel.infrastructure.persistence.repository.TransactionRepository;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongFunction;

/**
 * BRK-36 instrument, tests only: the production {@link DatabaseLedgerAccess}, counting what a refresh reads.
 * <ul>
 *   <li>{@code reads}: {@link LedgerAccess#read} units (one connection and one read transaction each);</li>
 *   <li>{@code ledgerFullReads}: whole-ledger reads ({@code PortfolioRepository.listAll}), inside a read or through
 *       {@link #portfolio()};</li>
 *   <li>{@code portfolioUnits}: units run by {@link #portfolio()} (each {@code state()}, {@code history()},
 *       {@code investmentInfo}... is one), i.e. reads outside {@link LedgerAccess#read};</li>
 *   <li>{@code registrationReads}: {@code investment_type} lists, inside a read or through the injected
 *       repository; {@code legacyTxReads}: legacy {@code transactions} reads of a month;</li>
 *   <li>{@code writeUnits}: write transactions; {@link #replays()}: ledger replays of the reads created here
 *       (a {@code portfolio().state()} replays too and shows up as a ledger read in {@code portfolioUnits}).</li>
 * </ul>
 */
public final class CountingLedgerAccess implements LedgerAccess {

    public int reads, ledgerFullReads, portfolioUnits, registrationReads, legacyTxReads, writeUnits;
    private final List<DailyTrackingUseCase.PortfolioRead> created = new ArrayList<>();
    private final DatabaseLedgerAccess delegate = new DatabaseLedgerAccess();
    private final PortfolioService portfolio = new PortfolioService(new LedgerStore() {
        private final DatabaseLedgerStore store = new DatabaseLedgerStore();

        @Override
        public <T> T transact(Function<PortfolioRepository, T> work) {
            portfolioUnits++;
            writeUnits++;
            return store.transact(repo -> work.apply(counting(repo)));
        }

        @Override
        public <T> T read(Function<PortfolioRepository, T> work) {
            portfolioUnits++;
            return store.read(repo -> work.apply(counting(repo)));
        }
    });

    /** A use case on the configured database whose ledger, registrations and legacy transactions are counted. */
    public DailyTrackingUseCase useCase(IStockPriceProvider prices) {
        IInvestmentTypeRepository regs = counting(IInvestmentTypeRepository.class, new InvestmentTypeRepository(),
                "listAll", () -> registrationReads++);
        ITransactionRepository txs = counting(ITransactionRepository.class, new TransactionRepository(),
                "listBetween", () -> legacyTxReads++);
        return new DailyTrackingUseCase(new FlowRepository(), regs, new SnapshotRepository(), txs, prices, this);
    }

    public void reset() {
        reads = ledgerFullReads = portfolioUnits = registrationReads = legacyTxReads = writeUnits = 0;
        created.clear();
    }

    /** Replays done by the reads created since the last {@link #reset()}. */
    public int replays() {
        return created.stream().mapToInt(DailyTrackingUseCase.PortfolioRead::replays).sum();
    }

    /** Short line for assertion messages. */
    public String summary() {
        return "reads=" + reads + " ledgerFullReads=" + ledgerFullReads + " portfolioUnits=" + portfolioUnits
                + " replays=" + replays() + " registrationReads=" + registrationReads + " legacyTxReads=" + legacyTxReads;
    }

    @Override
    public PortfolioService portfolio() {
        return portfolio;
    }

    @Override
    public <T> T transact(Function<PortfolioService, T> work) {
        writeUnits++;
        return delegate.transact(work);
    }

    @Override
    public int createInvestment(DailyTrackingUseCase.NewInvestment investment, LongFunction<OpeningRequest> opening) {
        writeUnits++;
        return delegate.createInvestment(investment, opening);
    }

    @Override
    public <T> T read(BiFunction<PortfolioRepository, IInvestmentTypeRepository, T> work) {
        reads++;
        T result = delegate.read((repo, regs) -> work.apply(counting(repo),
                counting(IInvestmentTypeRepository.class, regs, "listAll", () -> registrationReads++)));
        if (result instanceof DailyTrackingUseCase.PortfolioRead r) created.add(r);
        return result;
    }

    private PortfolioRepository counting(PortfolioRepository repo) {
        return counting(PortfolioRepository.class, repo, "listAll", () -> ledgerFullReads++);
    }

    @SuppressWarnings("unchecked")
    private static <I> I counting(Class<I> type, I target, String method, Runnable tick) {
        return (I) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, m, args) -> {
            if (m.getName().equals(method) && m.getDeclaringClass() != Object.class) tick.run();
            try {
                return m.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }
}
