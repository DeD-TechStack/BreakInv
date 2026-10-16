package com.daniel.core.service;

import com.daniel.core.domain.entity.*;
import com.daniel.core.domain.ledger.Cents;
import com.daniel.core.domain.ledger.InvestmentLedgerInfo;
import com.daniel.core.domain.ledger.LedgerException;
import com.daniel.core.domain.ledger.Movement;
import com.daniel.core.domain.ledger.MovementKind;
import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.domain.ledger.OpeningRequest;
import com.daniel.core.domain.ledger.PortfolioMetrics;
import com.daniel.core.domain.ledger.PortfolioState;
import com.daniel.core.domain.ledger.Position;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.core.domain.ledger.PriceSource;
import com.daniel.core.domain.ledger.ReconciliationRequest;
import com.daniel.core.domain.repository.*;
import com.daniel.core.util.MoneyFormat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;

/**
 * Application use cases behind every page.
 *
 * <p>Two wirings exist:
 * <ul>
 *   <li><b>Ledger-backed</b> (production, {@link LedgerAccess} injected): position, cost and cash
 *       are derived from the {@code portfolio_movement} ledger via {@link PortfolioService};
 *       the legacy {@code investment_type.quantity/invested_value} columns are shown only for
 *       registrations the ledger has not recognised yet (no opening marker). Legacy mutators
 *       ({@link #recordBuy}, {@link #recordSell}, {@link #saveEntry}, {@link #createFlow},
 *       {@link #deleteFlow}) are refused so nothing bypasses the ledger.</li>
 *   <li><b>Legacy</b> ({@code ledger == null}, unit tests with stubs): the pre-ledger behaviour is
 *       kept unchanged.</li>
 * </ul>
 */
public final class DailyTrackingUseCase {

    private static final Logger LOG = Logger.getLogger(DailyTrackingUseCase.class.getName());

    /** Note stored on snapshots whose value came from the ledger (position × quote or cost). */
    public static final String SNAPSHOT_NOTE_LEDGER =
            "LEDGER_DERIVED: position from ledger; quote when available, otherwise cost";
    /** Note stored on snapshots of registrations the ledger has not recognised. */
    public static final String SNAPSHOT_NOTE_LEGACY =
            "CURRENT_ESTIMATE: quote when available; otherwise registered cost/rate";

    private final IFlowRepository flowRepo;
    private final IInvestmentTypeRepository typeRepo;
    private final ISnapshotRepository snapshotRepo;
    private final ITransactionRepository txRepo;
    private final IStockPriceProvider priceProvider;
    private final LedgerAccess ledger;

    public DailyTrackingUseCase(
            IFlowRepository flowRepo,
            IInvestmentTypeRepository typeRepo,
            ISnapshotRepository snapshotRepo,
            ITransactionRepository txRepo,
            IStockPriceProvider priceProvider) {
        this(flowRepo, typeRepo, snapshotRepo, txRepo, priceProvider, null);
    }

    public DailyTrackingUseCase(
            IFlowRepository flowRepo,
            IInvestmentTypeRepository typeRepo,
            ISnapshotRepository snapshotRepo,
            ITransactionRepository txRepo,
            IStockPriceProvider priceProvider,
            LedgerAccess ledger) {
        this.flowRepo = flowRepo;
        this.typeRepo = typeRepo;
        this.snapshotRepo = snapshotRepo;
        this.txRepo = txRepo;
        this.priceProvider = priceProvider;
        this.ledger = ledger;
    }

    // =====================================================================
    // Records exchanged with the UI
    // =====================================================================

    /** Registration data (metadata only; nothing here changes wealth). */
    public record NewInvestment(
            String name, String category, String liquidity, LocalDate investmentDate,
            BigDecimal profitability, BigDecimal investedValue, String typeOfInvestment,
            String indexType, BigDecimal indexPercentage, String ticker,
            BigDecimal purchasePrice, Integer quantity, String note) {}

    /**
     * How the ledger opens a new registration: {@link #zero} (nothing owned yet — the first
     * purchase is a normal BUY) or {@link #imported} (balance already held elsewhere, declared
     * explicitly; it is opening capital, never a deposit nor a gain).
     */
    public record OpeningSpec(String intentKey, LocalDate date, PositionMode mode,
                              Long quantity, long costCents, String note, boolean imported) {

        public OpeningSpec {
            if (intentKey == null || intentKey.isBlank()) throw new LedgerException("intentKey", "chave de intenção obrigatória");
            if (date == null) throw new LedgerException("date", "data de abertura obrigatória");
            if (mode == null) throw new LedgerException("positionMode", "modo de posição obrigatório");
        }

        public static OpeningSpec zero(String intentKey, LocalDate date, PositionMode mode) {
            return new OpeningSpec(intentKey, date, mode, mode == PositionMode.QUANTITY ? 0L : null, 0L, null, false);
        }

        public static OpeningSpec imported(String intentKey, LocalDate date, PositionMode mode,
                                           Long quantity, long costCents, String note) {
            return new OpeningSpec(intentKey, date, mode, mode == PositionMode.QUANTITY ? quantity : null, costCents, note, true);
        }

        OpeningRequest toRequest(long investmentTypeId) {
            if (!imported) {
                return OpeningRequest.newInvestment(intentKey, date, investmentTypeId, mode, note == null ? "cadastro" : note);
            }
            return OpeningRequest.existingPosition(intentKey, date, investmentTypeId, mode, quantity, costCents, false,
                    note == null ? "saldo de abertura informado no cadastro" : note);
        }
    }

    /** Ledger view of one registration. {@code opened == false} means "legacy, not recognised yet". */
    public record InvestmentStatus(long id, String name, boolean opened, PositionMode mode, long quantity,
                                   long costCents, boolean reconciliationPending, boolean archived, String note,
                                   boolean hasLegacyData, int legacyTransactionCount,
                                   Integer legacyQuantity, BigDecimal legacyInvestedValue) {
        public boolean closed() {
            return opened && quantity == 0 && costCents == 0;
        }
    }

    /** Ledger view of cash. While {@code opened == false} the figure is the legacy snapshot. */
    public record CashStatus(long cashCents, boolean opened, boolean reconciliationPending, boolean legacySnapshotsPresent) {
        public boolean blocked() {
            return reconciliationPending || (!opened && legacySnapshotsPresent);
        }
    }

    // =====================================================================
    // Ledger access
    // =====================================================================

    public boolean hasLedger() {
        return ledger != null;
    }

    public PortfolioService portfolio() {
        if (ledger == null) throw new IllegalStateException("ledger não configurado");
        return ledger.portfolio();
    }

    private static UnsupportedOperationException bypass(String what) {
        return new UnsupportedOperationException(what + " grava fora do ledger; use movement()/createInvestment()");
    }

    // =====================================================================
    // Investment types
    // =====================================================================

    public List<InvestmentType> listTypes() {
        return listTypes(false);
    }

    /** Active registrations, plus archived ones when asked. Ledger-backed values when recognised. */
    public List<InvestmentType> listTypes(boolean includeArchived) {
        List<InvestmentType> raw = typeRepo.listAll();
        if (ledger == null) return raw;
        Map<Long, InvestmentLedgerInfo> infos = infoMap();
        PortfolioState state = portfolio().state();
        List<InvestmentType> out = new ArrayList<>();
        for (InvestmentType t : raw) {
            InvestmentLedgerInfo info = infos.get((long) t.id());
            if (info != null && info.archived() && !includeArchived) continue;
            out.add(overlay(t, info, state));
        }
        return out;
    }

    private Map<Long, InvestmentLedgerInfo> infoMap() {
        Map<Long, InvestmentLedgerInfo> m = new HashMap<>();
        for (InvestmentLedgerInfo i : portfolio().listInvestments(true)) m.put(i.id(), i);
        return m;
    }

    /** Position/cost from the ledger replace the registered quantity/invested value once opened. */
    private static InvestmentType overlay(InvestmentType t, InvestmentLedgerInfo info, PortfolioState state) {
        if (info == null || info.positionMode() == null) return t;
        Position p = state.position(t.id());
        if (!p.isOpen()) return t;
        Integer qty = p.mode() == PositionMode.QUANTITY ? (int) p.quantity() : null;
        BigDecimal avg = t.purchasePrice();
        if (p.mode() == PositionMode.QUANTITY && p.quantity() > 0) {
            avg = Cents.toDecimal(p.costCents()).divide(BigDecimal.valueOf(p.quantity()), 4, RoundingMode.HALF_UP);
        }
        return new InvestmentType(t.id(), t.name(), t.category(), t.liquidity(), t.investmentDate(),
                t.profitability(), Cents.toDecimal(p.costCents()), t.typeOfInvestment(), t.indexType(),
                t.indexPercentage(), t.ticker(), avg, qty, t.currentPrice());
    }

    public InvestmentStatus investmentStatus(long id) {
        InvestmentLedgerInfo info = portfolio().investmentInfo(id)
                .orElseThrow(() -> new LedgerException("investmentTypeId", "investimento inexistente: " + id));
        Position p = portfolio().position(id);
        boolean opened = info.positionMode() != null && p.isOpen();
        return new InvestmentStatus(id, info.name(), opened, opened ? p.mode() : null, p.quantity(), p.costCents(),
                p.reconciliationPending(), info.archived(), info.note(), info.hasLegacyData(),
                info.legacyTransactionCount(), info.legacyQuantity(), info.legacyInvestedValue());
    }

    public CashStatus cashStatus() {
        PortfolioState s = portfolio().state();
        OpeningRequest legacy = portfolio().proposeCashOpening("cash-status-probe", LocalDate.now());
        boolean legacyPresent = !s.cashOpened() && legacy.reconciliationFlag();
        long cash = legacyPresent ? legacy.costCents() : s.cashCents();
        return new CashStatus(cash, s.cashOpened(), s.cashReconciliationPending(), legacyPresent);
    }

    /**
     * Registration + opening marker in one atomic unit. With the ledger, a rejected opening
     * leaves no registration behind.
     */
    public int createInvestment(NewInvestment inv, OpeningSpec opening) {
        if (ledger == null) {
            return typeRepo.createFull(inv.name(), inv.category(), inv.liquidity(), inv.investmentDate(),
                    inv.profitability(), inv.investedValue(), inv.typeOfInvestment(), inv.indexType(),
                    inv.indexPercentage(), inv.ticker(), inv.purchasePrice(), inv.quantity());
        }
        return ledger.createInvestment(inv, opening::toRequest);
    }

    /** Legacy signature: with the ledger it opens a zero position (never a fake purchase). */
    public int createTypeFull(String name, String category, String liquidity,
                              LocalDate investmentDate, BigDecimal profitability,
                              BigDecimal investedValue, String typeOfInvestment,
                              String indexType, BigDecimal indexPercentage,
                              String ticker, BigDecimal purchasePrice, Integer quantity) {
        NewInvestment inv = new NewInvestment(name, category, liquidity, investmentDate, profitability, investedValue,
                typeOfInvestment, indexType, indexPercentage, ticker, purchasePrice, quantity, null);
        if (ledger == null) return createInvestment(inv, null);
        PositionMode mode = (ticker != null && !ticker.isBlank()) || quantity != null ? PositionMode.QUANTITY : PositionMode.VALUE;
        return createInvestment(inv, OpeningSpec.zero(UUID.randomUUID().toString(),
                investmentDate != null ? investmentDate : LocalDate.now(), mode));
    }

    /** Metadata update only: a recognised ledger position/cost is never touched here. */
    public void updateTypeFull(int id, String name, String category, String liquidity,
                               LocalDate investmentDate, BigDecimal profitability,
                               BigDecimal investedValue, String typeOfInvestment,
                               String indexType, BigDecimal indexPercentage,
                               String ticker, BigDecimal purchasePrice, Integer quantity) {
        typeRepo.updateFull(id, name, category, liquidity, investmentDate,
                profitability, investedValue, typeOfInvestment,
                indexType, indexPercentage, ticker, purchasePrice, quantity);
    }

    public void attachNote(long id, String note) {
        portfolio().attachNote(id, note);
    }

    /** Physical delete only without any history; otherwise archive. */
    public void deleteType(int id) {
        if (ledger == null) {
            typeRepo.delete(id);
            return;
        }
        if (!portfolio().canDeletePhysically(id)) {
            throw new LedgerException("history", "investimento possui histórico; arquive em vez de excluir");
        }
        portfolio().deleteRegistration(id);
    }

    /** Archiving keeps history and refuses a live position (wealth would vanish from the lists). */
    public void archiveType(long id) {
        InvestmentStatus st = investmentStatus(id);
        if (st.opened() && !st.closed()) {
            throw new LedgerException("position", "posição ainda aberta (" + describe(st) + "); venda/ajuste antes de arquivar");
        }
        if (!st.opened() && st.hasLegacyData()) {
            throw new LedgerException("opening", "cadastro legado não reconhecido: registre a abertura antes de arquivar");
        }
        portfolio().archive(id);
    }

    public void unarchiveType(long id) {
        portfolio().unarchive(id);
    }

    private static String describe(InvestmentStatus st) {
        return st.mode() == PositionMode.QUANTITY
                ? st.quantity() + " un., custo " + MoneyFormat.brl(st.costCents())
                : "custo " + MoneyFormat.brl(st.costCents());
    }

    // =====================================================================
    // Ledger operations
    // =====================================================================

    public OpeningRequest proposeOpening(String intentKey, LocalDate date, long investmentTypeId) {
        return portfolio().proposeOpening(intentKey, date, investmentTypeId);
    }

    public OpeningRequest proposeCashOpening(String intentKey, LocalDate date) {
        return portfolio().proposeCashOpening(intentKey, date);
    }

    public Movement registerOpening(OpeningRequest req) {
        return portfolio().registerOpening(req);
    }

    public Movement reconcile(ReconciliationRequest req) {
        return portfolio().reconcile(req);
    }

    /**
     * Deposit/withdrawal/buy/sell/adjustment/reversal — atomic, validated by the domain. On a
     * database without legacy cash snapshots the first cash-touching movement registers the
     * zero cash marker the domain proposes ("sem caixa legado"); legacy cash still demands an
     * explicit opening/reconciliation by the user.
     */
    public Movement movement(MovementRequest req) {
        if (ledger == null) throw new IllegalStateException("Ledger indisponível");
        return ledger.transact(service -> {
            if (touchesCash(req)) ensureCashOpened(service);
            return service.executeMovement(req);
        });
    }

    /** Fixed key: the marker is created at most once per database. */
    public static final String CASH_OPENING_AUTO_KEY = "cash-opening-auto";

    private static boolean touchesCash(MovementRequest req) {
        return switch (req.kind()) {
            case DEPOSIT, WITHDRAWAL, BUY, SELL -> true;
            case ADJUSTMENT -> req.investmentTypeId() == null;
            default -> false;
        };
    }

    private void ensureCashOpened(PortfolioService service) {
        PortfolioState s = service.state();
        if (s.cashOpened()) return;
        // This technical zero marker carries no historical money. Unlike a declared legacy
        // balance, it must not prevent otherwise reconstructible earlier movements.
        OpeningRequest proposal = service.proposeCashOpening(CASH_OPENING_AUTO_KEY,
                LocalDate.of(MovementRequest.MIN_YEAR, 1, 1));
        if (proposal.reconciliationFlag()) return; // legacy cash: user must recognise it explicitly
        service.registerOpening(proposal);
    }

    public List<Long> pendingReconciliations() {
        return portfolio().pendingReconciliations();
    }

    // =====================================================================
    // Legacy transactions (read) and refused mutators
    // =====================================================================

    public void recordBuy(int investmentTypeId, String name, String ticker,
                          Integer quantity, Long unitPriceCents, long totalCents,
                          LocalDate date) {
        if (ledger != null) throw bypass("recordBuy");
        Transaction tx = new Transaction(0, date, investmentTypeId,
                Transaction.BUY, name, ticker, quantity, unitPriceCents, totalCents, null);
        txRepo.insert(tx);
    }

    public void recordSell(int investmentTypeId, String name, String ticker,
                           Integer quantity, Long unitPriceCents, long totalCents,
                           LocalDate date, String note) {
        if (ledger != null) throw bypass("recordSell");
        Transaction tx = new Transaction(0, date, investmentTypeId,
                Transaction.SELL, name, ticker, quantity, unitPriceCents, totalCents, note);
        txRepo.insert(tx);
    }

    public List<Transaction> listTransactions(YearMonth month) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        return txRepo.listBetween(start, end);
    }

    /**
     * Unified statement for a month: every ledger movement once (markers, reversals linked)
     * plus legacy {@code transactions} rows identified as such. Ordered by date, then id;
     * legacy rows after ledger rows of the same date.
     */
    public List<StatementEntry> statement(YearMonth month) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        List<StatementEntry> out = new ArrayList<>();
        Map<Long, String> names = new HashMap<>();
        for (InvestmentType t : typeRepo.listAll()) names.put((long) t.id(), t.name());

        if (ledger != null) {
            List<Movement> all = portfolio().history();
            Map<Long, Long> reversedBy = new HashMap<>();
            for (Movement m : all) if (m.reversesId() != null) reversedBy.put(m.reversesId(), m.id());
            for (Movement m : all) {
                if (m.date().isBefore(start) || m.date().isAfter(end)) continue;
                out.add(new StatementEntry(m.date(), m.kind(), null, m.id(), null, m.investmentTypeId(),
                        m.investmentTypeId() == null ? null : names.get(m.investmentTypeId()),
                        m.positionMode(), m.quantity(), m.unitPrice(), m.amountCents(), m.classification(),
                        m.reason(), m.note(), m.reversesId(), reversedBy.get(m.id()), m.reconciliationFlag(), false));
            }
        }
        for (Transaction tx : txRepo.listBetween(start, end)) {
            out.add(new StatementEntry(tx.date(), null, tx.type(), null, tx.id(), (long) tx.investmentTypeId(),
                    names.getOrDefault((long) tx.investmentTypeId(), tx.name()), null,
                    tx.quantity() == null ? null : tx.quantity().longValue(),
                    tx.unitPriceCents() == null ? null : Cents.toDecimal(tx.unitPriceCents()),
                    tx.totalCents(), null, null, tx.note(), null, null, false, true));
        }
        out.sort(Comparator.comparing(StatementEntry::date)
                .thenComparing(StatementEntry::legacy)
                .thenComparingLong(e -> e.legacy() ? e.legacyTransactionId() : e.movementId()));
        return out;
    }

    // =====================================================================
    // Snapshot of today
    // =====================================================================

    /**
     * Refreshes today's observed/estimated state, never reconstructing past quotes. Ledger-backed
     * registrations get their derived value (explicit zero for closed positions, archived
     * included so series stay continuous); cash is written when the ledger owns it.
     */
    public void takeSnapshotIfNeeded(LocalDate date) {
        if (!LocalDate.now().equals(date)) return;
        if (ledger == null) {
            for (InvestmentType inv : typeRepo.listAll()) {
                long valueCents = getCurrentValue(inv, date);
                if (valueCents > 0 || inv.investedValue() != null || inv.quantity() != null) {
                    snapshotRepo.upsertInvestment(date, inv.id(), valueCents, SNAPSHOT_NOTE_LEGACY);
                }
            }
            return;
        }
        Map<Long, InvestmentLedgerInfo> infos = infoMap();
        PortfolioState state = portfolio().state();
        for (InvestmentType raw : typeRepo.listAll()) {
            InvestmentLedgerInfo info = infos.get((long) raw.id());
            Position p = state.position(raw.id());
            boolean opened = info != null && info.positionMode() != null && p.isOpen();
            if (opened) {
                snapshotRepo.upsertInvestment(date, raw.id(), ledgerValue(p, raw, date), SNAPSHOT_NOTE_LEDGER);
            } else if (info == null || !info.archived()) {
                long valueCents = legacyValue(raw, date);
                if (valueCents > 0 || raw.investedValue() != null || raw.quantity() != null) {
                    snapshotRepo.upsertInvestment(date, raw.id(), valueCents, SNAPSHOT_NOTE_LEGACY);
                }
            }
        }
        if (state.cashOpened()) {
            snapshotRepo.upsertCash(date, state.cashCents());
        }
    }

    // =====================================================================
    // Valuation
    // =====================================================================

    public Map<Long, Long> getAllCurrentValues(LocalDate date) {
        Map<Long, Long> values = new HashMap<>();
        for (InvestmentType inv : listTypes()) {
            long value = getCurrentValue(inv, date);
            values.put((long) inv.id(), value);
            LOG.fine(() -> inv.name() + ": " + (value > 0 ? brl(value) : "SEM VALOR"));
        }
        return values;
    }

    /** Current value: ledger position × quote (or cost) when recognised, legacy estimate otherwise. */
    public long getCurrentValue(InvestmentType inv, LocalDate today) {
        if (ledger != null) {
            Position p = portfolio().position(inv.id());
            if (p.isOpen()) return ledgerValue(p, inv, today);
        }
        return legacyValue(inv, today);
    }

    /** Quote per unit for QUANTITY positions; estimated total for VALUE positions; empty = at cost. */
    private Optional<BigDecimal> priceFor(Position p, InvestmentType inv, LocalDate today) {
        if (p.mode() == PositionMode.QUANTITY) {
            if (inv.ticker() == null || inv.ticker().isBlank() || p.quantity() == 0) return Optional.empty();
            try {
                Double price = priceProvider.fetchPrice(inv.ticker());
                return price == null ? Optional.empty() : Optional.of(BigDecimal.valueOf(price));
            } catch (Exception e) {
                LOG.warning(String.format("[BRAPI] %s: %s", inv.ticker(), e.getMessage()));
                return Optional.empty();
            }
        }
        if (inv.profitability() != null && inv.investmentDate() != null && p.costCents() > 0) {
            return Optional.of(Cents.toDecimal(compound(p.costCents(), inv.profitability(), inv.investmentDate(), today)));
        }
        return Optional.empty();
    }

    private long ledgerValue(Position p, InvestmentType inv, LocalDate today) {
        Optional<BigDecimal> v = priceFor(p, inv, today);
        if (v.isEmpty()) return p.costCents();
        if (p.mode() == PositionMode.QUANTITY) {
            return Cents.fromDecimal(v.get().multiply(BigDecimal.valueOf(p.quantity())), "price");
        }
        return Cents.fromDecimal(v.get(), "value");
    }

    private PriceSource priceSource(LocalDate today, Map<Long, InvestmentType> types, PortfolioState state) {
        return id -> {
            InvestmentType inv = types.get(id);
            if (inv == null) return Optional.empty();
            return priceFor(state.position(id), inv, today);
        };
    }

    private PortfolioMetrics ledgerMetrics(LocalDate today) {
        Map<Long, InvestmentType> types = new HashMap<>();
        for (InvestmentType t : typeRepo.listAll()) types.put((long) t.id(), t);
        PortfolioState state = portfolio().state();
        return portfolio().metrics(priceSource(today, types, state));
    }

    /** Registrations the ledger has not recognised (no opening) and not archived. */
    private List<InvestmentType> unrecognised() {
        Map<Long, InvestmentLedgerInfo> infos = infoMap();
        PortfolioState state = portfolio().state();
        List<InvestmentType> out = new ArrayList<>();
        for (InvestmentType t : typeRepo.listAll()) {
            InvestmentLedgerInfo info = infos.get((long) t.id());
            if (info != null && info.archived()) continue;
            if (state.position(t.id()).isOpen()) continue;
            out.add(t);
        }
        return out;
    }

    private static long compound(long baseCents, BigDecimal annualRatePct, LocalDate from, LocalDate today) {
        double annualRate = annualRatePct.doubleValue() / 100.0;
        long months = ChronoUnit.MONTHS.between(from, today);
        if (months < 0) months = 0;
        double monthlyRate = Math.pow(1 + annualRate, 1.0 / 12) - 1;
        return Math.round(baseCents * Math.pow(1 + monthlyRate, months));
    }

    private long legacyValue(InvestmentType inv, LocalDate today) {
        if (inv.ticker() != null && !inv.ticker().isBlank() &&
                inv.quantity() != null && inv.purchasePrice() != null) {

            try {
                Double currentPrice = priceProvider.fetchPrice(inv.ticker());
                if (currentPrice != null) {
                    int quantity = inv.quantity();
                    long valueCents = Math.round(currentPrice * quantity * 100);
                    LOG.fine(String.format("[ACAO] %s: Qtd=%d x R$%.2f = %s",
                            inv.ticker(), quantity, currentPrice, brl(valueCents)));
                    return valueCents;
                }
            } catch (Exception e) {
                LOG.warning(String.format("[BRAPI] %s: %s", inv.ticker(), e.getMessage()));
            }

            // Fallback: usar preço de compra
            int quantity = inv.quantity();
            long valueCents = inv.purchasePrice()
                    .multiply(BigDecimal.valueOf(quantity))
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
            LOG.fine(String.format("[ACAO FALLBACK] %s: Qtd=%d x R$%.2f (preco compra) = %s",
                    inv.ticker(), quantity, inv.purchasePrice().doubleValue(), brl(valueCents)));
            return valueCents;
        }

        if (inv.profitability() != null && inv.investedValue() != null &&
                inv.investmentDate() != null) {
            long investedCents = inv.investedValue().multiply(BigDecimal.valueOf(100)).longValue();
            long valueCents = compound(investedCents, inv.profitability(), inv.investmentDate(), today);
            LOG.fine(String.format("[RENDA FIXA] %s: %s x %s%% a.a. = %s",
                    inv.name(), brl(investedCents), inv.profitability(), brl(valueCents)));
            return valueCents;
        }

        if (inv.investedValue() != null) {
            long valueCents = inv.investedValue().multiply(BigDecimal.valueOf(100)).longValue();
            LOG.fine(String.format("[OUTRO] %s: %s (valor investido)", inv.name(), brl(valueCents)));
            return valueCents;
        }

        LOG.warning(String.format("[SEM VALOR] %s: sem ticker, rentabilidade ou valor investido", inv.name()));
        return 0L;
    }

    /**
     * Total patrimony = investments current value + cash. With the ledger, cash is the replayed
     * balance once opened (legacy snapshot carry-forward before that).
     */
    public long getTotalPatrimony(LocalDate today) {
        long investmentTotal = getTotalInvestmentValue(today);
        long cashCents;
        if (ledger != null) {
            CashStatus cs = cashStatus();
            cashCents = cs.legacySnapshotsPresent() ? snapshotRepo.getCashOnOrBefore(today) : cs.cashCents();
        } else {
            cashCents = snapshotRepo.getCashOnOrBefore(today);
        }
        long total = investmentTotal + cashCents;
        LOG.fine(() -> "PATRIMONIO TOTAL: " + brl(total));
        return total;
    }

    /** Sum of current investment values only, without cash. */
    public long getTotalInvestmentValue(LocalDate today) {
        if (ledger == null) {
            return getAllCurrentValues(today).values().stream().mapToLong(Long::longValue).sum();
        }
        long total = ledgerMetrics(today).positionValueCents();
        for (InvestmentType inv : unrecognised()) total += legacyValue(inv, today);
        return total;
    }

    /**
     * Economic gain: realized + unrealized from the ledger (external flows and opening capital
     * never count as profit), plus the legacy estimate (current − registered) for registrations
     * the ledger has not recognised yet.
     */
    public long getTotalProfit(LocalDate today) {
        if (ledger != null) {
            long profit = ledgerMetrics(today).economicGainCents();
            for (InvestmentType inv : unrecognised()) {
                profit += legacyProfit(inv, today);
            }
            return profit;
        }
        long profit = 0L;
        for (InvestmentType inv : typeRepo.listAll()) profit += legacyProfit(inv, today);
        final long logged = profit;
        LOG.fine(() -> "LUCRO/PREJUIZO legado: " + brl(logged));
        return profit;
    }

    private long legacyProfit(InvestmentType inv, LocalDate today) {
        long invested = inv.investedValue() == null ? 0L
                : inv.investedValue().multiply(BigDecimal.valueOf(100)).longValue();
        return legacyValue(inv, today) - invested;
    }

    /** Ledger metrics with the same price source used by the dashboard; {@code null} without ledger. */
    public PortfolioMetrics metrics(LocalDate today) {
        return ledger == null ? null : ledgerMetrics(today);
    }

    /**
     * Calcula o preço médio ponderado de um ticker
     */
    public double getAveragePrice(String ticker) {
        List<InvestmentType> investments = listTypes();

        double totalValue = 0.0;
        int totalQuantity = 0;

        for (InvestmentType inv : investments) {
            if (ticker.equals(inv.ticker()) &&
                    inv.purchasePrice() != null &&
                    inv.quantity() != null) {

                double price = inv.purchasePrice().doubleValue();
                int qty = inv.quantity();

                totalValue += (price * qty);
                totalQuantity += qty;
            }
        }

        if (totalQuantity == 0) return 0.0;
        return totalValue / totalQuantity;
    }

    /**
     * Calcula a quantidade total de um ticker
     */
    public int getTotalQuantity(String ticker) {
        int total = 0;
        for (InvestmentType inv : listTypes()) {
            if (ticker.equals(inv.ticker()) && inv.quantity() != null) {
                total += inv.quantity();
            }
        }
        return total;
    }

    /**
     * Agrega snapshots históricos de todos os investimentos (sem caixa) por data dentro do
     * intervalo, inclusive arquivados (o passado não some ao arquivar).
     *
     * @return TreeMap data→total investimentos em centavos, ordenado por data.
     *         Retorna mapa vazio se não houver snapshots no período.
     */
    public TreeMap<LocalDate, Long> getInvestmentSnapshotSeries(LocalDate from, LocalDate to) {
        TreeMap<LocalDate, Long> totals = new TreeMap<>();
        List<InvestmentType> investments = typeRepo.listAll();
        for (InvestmentType inv : investments) {
            Map<String, Long> series = snapshotRepo.seriesForInvestiments(inv.id());
            for (Map.Entry<String, Long> entry : series.entrySet()) {
                try {
                    LocalDate date = LocalDate.parse(entry.getKey());
                    if (!date.isBefore(from) && !date.isAfter(to)) {
                        totals.merge(date, entry.getValue(), Long::sum);
                    }
                } catch (Exception ignored) {}
            }
        }
        return totals;
    }

    /**
     * Agrega snapshots históricos de todos os investimentos e do caixa por data dentro do
     * intervalo. O caixa é somado a cada data que já possui snapshot de investimento, usando
     * carry-forward (último valor conhecido na data ou antes dela).
     *
     * @return TreeMap data→patrimônio total em centavos (investimentos + caixa), ordenado por data.
     *         Retorna mapa vazio se não houver snapshots de investimento no período.
     */
    public TreeMap<LocalDate, Long> getPortfolioSnapshotSeries(LocalDate from, LocalDate to) {
        TreeMap<LocalDate, Long> totals = getInvestmentSnapshotSeries(from, to);
        // Add latest known cash balance (carry-forward) to each investment snapshot date
        for (LocalDate date : new ArrayList<>(totals.keySet())) {
            long cashCents = snapshotRepo.getCashOnOrBefore(date);
            if (cashCents > 0) {
                totals.merge(date, cashCents, Long::sum);
            }
        }
        return totals;
    }

    /**
     * Agrupa investimentos por ticker
     */
    public Map<String, List<InvestmentType>> groupByTicker() {
        Map<String, List<InvestmentType>> grouped = new HashMap<>();
        for (InvestmentType inv : listTypes()) {
            if (inv.ticker() != null && !inv.ticker().isBlank()) {
                grouped.computeIfAbsent(inv.ticker(), k -> new ArrayList<>()).add(inv);
            }
        }
        return grouped;
    }

    /**
     * Calcula a rentabilidade percentual de um investimento
     */
    public double getProfitability(InvestmentType inv, LocalDate date) {
        if (inv.investedValue() == null) return 0.0;

        long investedCents = inv.investedValue()
                .multiply(BigDecimal.valueOf(100))
                .longValue();

        if (investedCents == 0) return 0.0;

        long currentCents = getCurrentValue(inv, date);

        return ((currentCents - investedCents) / (double)investedCents) * 100;
    }

    // ========== DAILY ENTRY ==========

    public DailyEntry loadEntry(LocalDate date) {
        Map<Long, Long> invMap = snapshotRepo.getAllInvestimentsForDate(date);
        long cashCents = snapshotRepo.getCash(date);

        List<InvestmentType> allTypes = typeRepo.listAll();

        Map<InvestmentType, Long> investments = new LinkedHashMap<>();
        for (InvestmentType t : allTypes) {
            long cents = invMap.getOrDefault((long) t.id(), 0L);
            investments.put(t, cents);
        }

        return new DailyEntry(date, cashCents, investments);
    }

    public void saveEntry(DailyEntry entry) {
        if (ledger != null) throw bypass("saveEntry");
        if (entry == null) return;

        // Salvar caixa
        if (entry.cashCents() >= 0) {
            snapshotRepo.upsertCash(entry.date(), entry.cashCents());
        }

        for (var e : entry.investmentValuesCents().entrySet()) {
            InvestmentType type = e.getKey();
            Long cents = e.getValue();
            if (cents != null && cents >= 0) {
                snapshotRepo.upsertInvestment(entry.date(), type.id(), cents, null);
            }
        }
    }

    // ========== FLOWS ==========

    public List<Flow> flowsFor(LocalDate date) {
        return flowRepo.listForDate(date);
    }

    public void createFlow(Flow flow) {
        if (ledger != null) throw bypass("createFlow");
        flowRepo.create(flow);
    }

    public void deleteFlow(long flowId) {
        if (ledger != null) throw bypass("deleteFlow");
        flowRepo.delete(flowId);
    }

    // ========== SUMMARY ==========

    public boolean hasAnyDataPublic(LocalDate date) {
        DailyEntry entry = loadEntry(date);
        return entry.cashCents() > 0 ||
                entry.investmentValuesCents().values().stream()
                        .anyMatch(v -> v != null && v > 0);
    }

    public DailySummary summaryFor(LocalDate date) {
        DailyEntry entry = loadEntry(date);

        // Buscar dia anterior para calcular delta e lucro
        LocalDate prev = date.minusDays(1);
        DailyEntry prevEntry = loadEntry(prev);

        long cashCents = entry.cashCents();
        long prevCashCents = prevEntry.cashCents();
        long cashDeltaCents = cashCents - prevCashCents;

        Map<Long, Long> investmentTodayCents = new HashMap<>();
        Map<Long, Long> investmentProfitTodayCents = new HashMap<>();

        long totalInvCents = 0;
        long totalProfitCents = 0;

        // Raw yesterday snapshot: keys present only when a snapshot was actually recorded.
        // Avoids treating the first recorded value as profit (no baseline → profit = 0).
        Map<Long, Long> prevRawInvestments = snapshotRepo.getAllInvestimentsForDate(prev);

        // Busca os fluxos do dia uma única vez fora do loop (evita N queries idênticas)
        List<Flow> flows = flowsFor(date);

        for (var entryMap : entry.investmentValuesCents().entrySet()) {
            InvestmentType t = entryMap.getKey();
            long todayCents = entryMap.getValue() != null ? entryMap.getValue() : 0L;

            investmentTodayCents.put((long) t.id(), todayCents);

            long flowsInCents = 0;
            long flowsOutCents = 0;

            for (Flow f : flows) {
                // Fluxo para este investimento
                if (f.toInvestmentTypeId() != null && f.toInvestmentTypeId() == t.id()) {
                    flowsInCents += f.amountCents();
                }
                // Fluxo saindo deste investimento
                if (f.fromInvestmentTypeId() != null && f.fromInvestmentTypeId() == t.id()) {
                    flowsOutCents += f.amountCents();
                }
            }

            // No previous snapshot → first recorded value establishes the baseline; profit = 0.
            long profitCents;
            if (!prevRawInvestments.containsKey((long) t.id())) {
                profitCents = 0L;
            } else {
                long yesterdayCents = prevRawInvestments.get((long) t.id());
                profitCents = todayCents - yesterdayCents - flowsInCents + flowsOutCents;
            }
            investmentProfitTodayCents.put((long) t.id(), profitCents);

            totalInvCents += todayCents;
            totalProfitCents += profitCents;
        }

        long totalTodayCents = cashCents + totalInvCents;

        return new DailySummary(
                date,
                totalTodayCents,
                totalProfitCents,
                cashCents,
                cashDeltaCents,
                investmentTodayCents,
                investmentProfitTodayCents
        );
    }

    // ========== SERIES / CHARTS ==========

    public record SeriesPoint(LocalDate date, long valueCents) {}

    public List<SeriesPoint> seriesForInvestment(int investmentTypeId) {
        Map<String, Long> data = snapshotRepo.seriesForInvestiments(investmentTypeId);
        List<SeriesPoint> points = new ArrayList<>();

        for (var entry : data.entrySet()) {
            LocalDate date = LocalDate.parse(entry.getKey());
            points.add(new SeriesPoint(date, entry.getValue()));
        }

        return points;
    }

    // ========== FORMATTING HELPERS ==========

    public String brl(long cents) {
        return MoneyFormat.brl(cents);
    }

    public String brlAbs(long cents) {
        return MoneyFormat.brlAbs(cents);
    }

    /** Human label for a movement kind (statement, dashboard). */
    public static String kindLabel(MovementKind kind) {
        if (kind == null) return "Legado";
        return switch (kind) {
            case OPENING -> "Abertura";
            case DEPOSIT -> "Aporte";
            case WITHDRAWAL -> "Retirada";
            case BUY -> "Compra";
            case SELL -> "Venda";
            case ADJUSTMENT -> "Ajuste";
            case REVERSAL -> "Estorno";
            case RECONCILIATION -> "Reconciliação";
        };
    }
}
