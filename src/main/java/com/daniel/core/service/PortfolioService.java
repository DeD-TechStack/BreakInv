package com.daniel.core.service;

import com.daniel.core.domain.ledger.AdjustmentClassification;
import com.daniel.core.domain.ledger.Cents;
import com.daniel.core.domain.ledger.InvestmentLedgerInfo;
import com.daniel.core.domain.ledger.LedgerException;
import com.daniel.core.domain.ledger.LedgerReplay;
import com.daniel.core.domain.ledger.LedgerStore;
import com.daniel.core.domain.ledger.Movement;
import com.daniel.core.domain.ledger.MovementKind;
import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.domain.ledger.OpeningRequest;
import com.daniel.core.domain.ledger.PortfolioMetrics;
import com.daniel.core.domain.ledger.PortfolioRepository;
import com.daniel.core.domain.ledger.PortfolioState;
import com.daniel.core.domain.ledger.Position;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.core.domain.ledger.PriceSource;
import com.daniel.core.domain.ledger.ReconciliationRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ledger use cases. Every write runs in one {@link LedgerStore#transact} unit: validate inputs,
 * check the idempotency key, append the row, replay the WHOLE ledger in (date, id) order and
 * roll back if any invariant breaks. UI-independent; prices are injected through
 * {@link PriceSource} or an explicit map.
 */
public final class PortfolioService {

    private final LedgerStore store;

    public PortfolioService(LedgerStore store) {
        this.store = store;
    }

    /** Service bound to the configured {@code Database} (one independent connection per unit of work). */
    public static PortfolioService onDatabase() {
        return new PortfolioService(new com.daniel.infrastructure.persistence.repository.DatabaseLedgerStore());
    }

    /** Service bound to one caller-owned connection (tests, scripts). */
    public static PortfolioService onConnection(java.sql.Connection connection) {
        return new PortfolioService(new com.daniel.infrastructure.persistence.repository.DatabaseLedgerStore(connection));
    }

    // =====================================================================
    // Opening / legacy recognition
    // =====================================================================

    /**
     * Proposes an opening for an investment from its legacy registration columns and legacy
     * transactions. Nothing is written. The proposal is flagged for reconciliation whenever the
     * legacy sources disagree or are incomplete; values are converted HALF_UP, nulls stay absent.
     */
    public OpeningRequest proposeOpening(String intentKey, LocalDate date, long investmentTypeId) {
        InvestmentLedgerInfo info = store.read(r -> r.investmentInfo(investmentTypeId))
                .orElseThrow(() -> new LedgerException("investmentTypeId", "investimento inexistente: " + investmentTypeId));
        boolean legacyTx = info.legacyTransactionCount() > 0;
        boolean incomplete = info.legacyQuantityIncomplete();
        if (info.legacyQuantity() != null && info.legacyPurchasePrice() != null) {
            long qty = info.legacyQuantity();
            if (qty < 0) throw new LedgerException("quantity", "quantidade legada negativa exige reconciliação manual");
            // A legacy price <= 0 cannot derive a cost (Cents.total would refuse it). The proposal
            // must still exist so the dialog opens: cost 0 as a placeholder, flagged, with the
            // reason in the note; qty 0 ⇒ cost 0 holds. Nothing is inferred or written.
            boolean priceUnusable = info.legacyPurchasePrice().signum() <= 0;
            long cost = priceUnusable ? 0L : Cents.total(qty, info.legacyPurchasePrice(), "purchasePrice");
            long txNet = info.legacyBuyQuantity() - info.legacySellQuantity();
            boolean flag = priceUnusable || incomplete || (legacyTx && txNet != qty);
            String note = !flag ? "abertura a partir do cadastro"
                    : priceUnusable ? "preço de compra cadastrado " + info.legacyPurchasePrice().toPlainString()
                            + " não permite apurar o custo: custo 0 é marcador, confirmar quantidade " + qty + " e custo"
                    : incomplete ? "transações legadas com quantidade em branco (" + info.legacyNullQuantityCount()
                            + "): saldo histórico incompleto, confirmar quantidade cadastrada " + qty
                    : "quantidade cadastrada " + qty + " difere do saldo das transações legadas " + txNet;
            return OpeningRequest.existingPosition(intentKey, date, investmentTypeId, PositionMode.QUANTITY, qty, cost, flag, note);
        }
        if (info.legacyInvestedValue() != null) {
            long cost = Cents.fromDecimal(info.legacyInvestedValue(), "investedValue");
            if (cost < 0) throw new LedgerException("investedValue", "valor investido legado negativo exige reconciliação manual");
            boolean flag = legacyTx || info.legacyQuantity() != null;
            return OpeningRequest.existingPosition(intentKey, date, investmentTypeId, PositionMode.VALUE, null, cost, flag,
                    flag ? "valor investido cadastrado com transações/quantidade legadas: confirmar" : "abertura a partir do valor investido");
        }
        // No derivable cost. Flag whenever any legacy signal exists (transactions, quantity or a
        // price without quantity): a 0 cost is then a placeholder to confirm, never a silent fact.
        boolean flag = legacyTx || info.legacyQuantity() != null || info.legacyPurchasePrice() != null;
        PositionMode mode = info.legacyQuantity() != null ? PositionMode.QUANTITY : PositionMode.VALUE;
        return OpeningRequest.existingPosition(intentKey, date, investmentTypeId, mode,
                mode == PositionMode.QUANTITY ? (long) info.legacyQuantity() : null, 0L, flag,
                flag ? "cadastro sem custo apurável: confirmar" : "abertura vazia");
    }

    /** Proposes a cash opening from the latest legacy cash snapshot (flagged, since it is a manual figure). */
    public OpeningRequest proposeCashOpening(String intentKey, LocalDate date) {
        Optional<Long> legacy = store.read(PortfolioRepository::latestLegacyCashCents);
        return legacy.map(c -> OpeningRequest.cash(intentKey, date, Math.max(0L, c), true, "caixa a partir do último snapshot manual"))
                .orElseGet(() -> OpeningRequest.cash(intentKey, date, 0L, false, "sem caixa legado"));
    }

    /**
     * Registers the opening marker. Idempotent by intent key. An investment may have only one
     * OPENING; later corrections go through {@link #reconcile}. Also records the position mode
     * on the registration row.
     */
    public Movement registerOpening(OpeningRequest req) {
        return store.transact(repo -> {
            Optional<Movement> existing = idempotent(repo, req.intentKey(), req.payloadHash());
            if (existing.isPresent()) return existing.get();
            if (req.investmentTypeId() != null) {
                InvestmentLedgerInfo info = requireInvestment(repo, req.investmentTypeId());
                if (info.archived()) throw new LedgerException("archived", "investimento arquivado");
                boolean already = repo.listAll().stream().anyMatch(m -> m.kind() == MovementKind.OPENING
                        && req.investmentTypeId().equals(m.investmentTypeId()));
                if (already) throw new LedgerException("opening", "investimento já possui marco de abertura; use reconcile");
                repo.setPositionMode(req.investmentTypeId(), req.positionMode());
            } else {
                boolean already = repo.listAll().stream().anyMatch(m -> m.kind() == MovementKind.OPENING && m.isCashOnly());
                if (already) throw new LedgerException("opening", "caixa já possui marco de abertura; use reconcile");
            }
            Movement row = new Movement(0, req.intentKey(), req.date(), MovementKind.OPENING, req.investmentTypeId(),
                    req.positionMode(), req.quantity(), null, req.costCents(), null, null, req.note(), null,
                    req.reconciliationFlag(), "USER", Instant.now());
            return appendAndReplay(repo, row, req.payloadHash());
        });
    }

    /** Explicit re-statement; clears the pending flag for that investment/cash. */
    public Movement reconcile(ReconciliationRequest req) {
        return store.transact(repo -> {
            Optional<Movement> existing = idempotent(repo, req.intentKey(), req.payloadHash());
            if (existing.isPresent()) return existing.get();
            PositionMode mode = null;
            Optional<Movement> opening = repo.listAll().stream()
                    .filter(m -> m.kind() == MovementKind.OPENING && java.util.Objects.equals(m.investmentTypeId(), req.investmentTypeId()))
                    .findFirst();
            if (opening.isEmpty()) {
                throw new LedgerException("opening", req.investmentTypeId() == null
                        ? "reconciliação de caixa exige marco de abertura prévio"
                        : "reconciliação exige marco de abertura prévio");
            }
            if (req.date().isBefore(opening.get().date())) {
                throw new LedgerException("date", "reconciliação anterior ao marco de abertura (" + opening.get().date() + ")");
            }
            if (req.investmentTypeId() != null) {
                InvestmentLedgerInfo info = requireInvestment(repo, req.investmentTypeId());
                if (info.archived()) throw new LedgerException("archived", "investimento arquivado");
                mode = info.positionMode();
                if (mode == null) throw new LedgerException("opening", "reconciliação exige marco de abertura prévio");
                if (mode == PositionMode.QUANTITY && req.quantity() == null) throw new LedgerException("quantity", "quantidade obrigatória");
                if (mode == PositionMode.VALUE && req.quantity() != null) throw new LedgerException("quantity", "investimento por valor não tem quantidade");
            }
            Movement row = new Movement(0, req.intentKey(), req.date(), MovementKind.RECONCILIATION, req.investmentTypeId(),
                    mode, req.quantity(), null, req.costCents(), null, req.reason(), req.note(), null, false, "USER", Instant.now());
            return appendAndReplay(repo, row, req.payloadHash());
        });
    }

    // =====================================================================
    // Movements
    // =====================================================================

    /**
     * Appends a movement atomically. Same key + same payload returns the stored row (double
     * submit safe); same key + different payload is rejected ({@code field = intentKey}).
     * Rejections leave the database untouched.
     */
    public Movement executeMovement(MovementRequest req) {
        return store.transact(repo -> {
            String hash = req.payloadHash();
            Optional<Movement> existing = idempotent(repo, req.intentKey(), hash);
            if (existing.isPresent()) return existing.get();

            Movement row = switch (req.kind()) {
                case DEPOSIT, WITHDRAWAL -> {
                    requirePositiveAmount(req);
                    requireCashRecognized(repo);
                    yield base(req, null, null, null, req.amountCents());
                }
                case BUY, SELL -> {
                    InvestmentLedgerInfo info = requireActiveOpened(repo, req);
                    requireCashRecognized(repo);
                    if (info.positionMode() == PositionMode.QUANTITY) {
                        if (req.quantity() == null || req.quantity() <= 0) throw new LedgerException("quantity", "quantidade deve ser inteiro > 0");
                        if (req.amountCents() != null) throw new LedgerException("amount", "em modo quantidade o total é calculado de quantidade x preço");
                        long total = Cents.total(req.quantity(), req.unitPrice(), "unitPrice");
                        yield base(req, PositionMode.QUANTITY, req.quantity(), req.unitPrice(), total);
                    }
                    if (req.quantity() != null || req.unitPrice() != null) throw new LedgerException("quantity", "investimento por valor não usa quantidade/preço");
                    requirePositiveAmount(req);
                    yield base(req, PositionMode.VALUE, null, null, req.amountCents());
                }
                case ADJUSTMENT -> {
                    if (req.classification() == null) throw new LedgerException("classification", "ajuste exige classificação");
                    if (req.reason() == null) throw new LedgerException("reason", "ajuste exige motivo");
                    if (req.amountCents() == null) throw new LedgerException("amount", "valor do ajuste obrigatório");
                    PositionMode mode = null;
                    if (req.investmentTypeId() != null) {
                        mode = requireActiveOpened(repo, req).positionMode();
                        if (mode == PositionMode.VALUE && req.quantity() != null) throw new LedgerException("quantity", "investimento por valor não tem quantidade");
                    } else {
                        requireCashRecognized(repo);
                    }
                    if (req.amountCents() == 0 && (req.quantity() == null || req.quantity() == 0)) throw new LedgerException("amount", "ajuste sem efeito");
                    if (req.classification() == AdjustmentClassification.INCOME && req.amountCents() <= 0) {
                        throw new LedgerException("amount", "ajuste INCOME exige valor positivo");
                    }
                    if (req.classification() == AdjustmentClassification.EXPENSE && req.amountCents() >= 0) {
                        throw new LedgerException("amount", "ajuste EXPENSE exige valor negativo");
                    }
                    yield base(req, mode, req.quantity(), null, req.amountCents());
                }
                case REVERSAL -> {
                    if (req.reason() == null) throw new LedgerException("reason", "estorno exige motivo");
                    if (req.reversesId() == null) throw new LedgerException("reversesId", "movimento original obrigatório");
                    Movement original = repo.findById(req.reversesId())
                            .orElseThrow(() -> new LedgerException("reversesId", "movimento original inexistente"));
                    if (req.date().isBefore(original.date())) throw new LedgerException("date", "estorno não pode ser anterior ao movimento original");
                    if (original.investmentTypeId() != null) {
                        InvestmentLedgerInfo info = requireInvestment(repo, original.investmentTypeId());
                        if (info.archived()) throw new LedgerException("archived", "investimento arquivado");
                    }
                    if (repo.listAll().stream().anyMatch(m -> m.reversesId() != null && m.reversesId() == original.id())) {
                        throw new LedgerException("reversesId", "movimento já estornado");
                    }
                    // Reconstructibility (no SELL/reconciliation between original and reversal that
                    // already consumed its effect) is enforced by LedgerReplay for EVERY insertion,
                    // so a later retroactive row cannot bypass it; appendAndReplay rolls back.
                    // Quantity/price are NOT copied: the replay inverts the original's recorded
                    // effect, and the schema CHECK only admits negative quantities on ADJUSTMENT.
                    yield new Movement(0, req.intentKey(), req.date(), MovementKind.REVERSAL, original.investmentTypeId(),
                            original.positionMode(), null, null, original.amountCents(),
                            null, req.reason(), req.note(), original.id(), false, "USER", Instant.now());
                }
                default -> throw new LedgerException("kind", "tipo não suportado: " + req.kind());
            };
            return appendAndReplay(repo, row, hash);
        });
    }

    // =====================================================================
    // Queries
    // =====================================================================

    public PortfolioState state() {
        return store.read(repo -> LedgerReplay.replay(repo.listAll()));
    }

    public Position position(long investmentTypeId) {
        return state().position(investmentTypeId);
    }

    /** Full history, (date, id) order. Reversed entries stay visible, linked by reversesId. */
    public List<Movement> history() {
        return store.read(PortfolioRepository::listAll);
    }

    public List<Movement> history(long investmentTypeId) {
        return history().stream().filter(m -> Long.valueOf(investmentTypeId).equals(m.investmentTypeId())).toList();
    }

    public Optional<Movement> findByIntentKey(String intentKey) {
        return store.read(r -> r.findByIntentKey(intentKey));
    }

    public PortfolioMetrics metrics(Map<Long, BigDecimal> explicitValues) {
        return metrics(PriceSource.of(explicitValues));
    }

    /** Valuation with injected prices; QUANTITY = unit price x qty, VALUE = total value. Missing -> cost, flagged. */
    public PortfolioMetrics metrics(PriceSource prices) {
        try {
            return metricsChecked(prices);
        } catch (ArithmeticException overflow) {
            throw new LedgerException("value", "valor agregado excede o limite suportado");
        }
    }

    private PortfolioMetrics metricsChecked(PriceSource prices) {
        PortfolioState s = state();
        long value = 0, cost = 0;
        List<Long> atCost = new ArrayList<>();
        for (Position p : s.positions().values()) {
            cost = Math.addExact(cost, p.costCents());
            Optional<BigDecimal> v = prices.valueFor(p.investmentTypeId());
            if (v.isEmpty()) {
                atCost.add(p.investmentTypeId());
                value = Math.addExact(value, p.costCents());
            } else if (p.mode() == PositionMode.QUANTITY) {
                value = Math.addExact(value, p.quantity() == 0 ? 0L
                        : Cents.fromDecimal(v.get().multiply(BigDecimal.valueOf(p.quantity())), "price"));
            } else {
                value = Math.addExact(value, Cents.fromDecimal(v.get().setScale(2, RoundingMode.HALF_UP), "value"));
            }
        }
        long unrealized = Math.subtractExact(value, cost);
        Math.addExact(s.realizedGainCents(), unrealized);
        return new PortfolioMetrics(s.cashCents(), cost, value, s.realizedGainCents(), unrealized,
                Math.addExact(s.cashCents(), value), s.externalNetFlowCents(), List.copyOf(atCost));
    }

    public Optional<InvestmentLedgerInfo> investmentInfo(long investmentTypeId) {
        return store.read(r -> r.investmentInfo(investmentTypeId));
    }

    public List<InvestmentLedgerInfo> listInvestments(boolean includeArchived) {
        return store.read(r -> r.listInvestments(includeArchived));
    }

    /** Ids whose last OPENING/RECONCILIATION is flagged; {@code null} entry = cash. */
    public List<Long> pendingReconciliations() {
        PortfolioState s = state();
        List<Long> out = new ArrayList<>();
        if (s.cashReconciliationPending()) out.add(null);
        s.positions().values().stream().filter(Position::reconciliationPending).map(Position::investmentTypeId).forEach(out::add);
        return out;
    }

    // =====================================================================
    // Registration metadata / archiving
    // =====================================================================

    public void attachNote(long investmentTypeId, String note) {
        store.transact(repo -> {
            requireInvestment(repo, investmentTypeId);
            repo.setNote(investmentTypeId, note);
            return null;
        });
    }

    /** Archives: history and positions stay; new movements are refused. */
    public void archive(long investmentTypeId) {
        store.transact(repo -> {
            requireInvestment(repo, investmentTypeId);
            repo.setArchivedAt(investmentTypeId, Instant.now());
            return null;
        });
    }

    public void unarchive(long investmentTypeId) {
        store.transact(repo -> {
            requireInvestment(repo, investmentTypeId);
            repo.setArchivedAt(investmentTypeId, null);
            return null;
        });
    }

    public boolean canDeletePhysically(long investmentTypeId) {
        return store.read(r -> r.investmentInfo(investmentTypeId).isPresent() && !r.hasHistory(investmentTypeId));
    }

    /** Physical delete only for a registration without any history; otherwise archive. */
    public void deleteRegistration(long investmentTypeId) {
        store.transact(repo -> {
            requireInvestment(repo, investmentTypeId);
            if (repo.hasHistory(investmentTypeId)) throw new LedgerException("history", "investimento possui histórico; arquive em vez de excluir");
            repo.deleteRegistration(investmentTypeId);
            return null;
        });
    }

    // =====================================================================
    // internals
    // =====================================================================

    private static Optional<Movement> idempotent(PortfolioRepository repo, String key, String hash) {
        Optional<Movement> existing = repo.findByIntentKey(key);
        if (existing.isEmpty()) return existing;
        String storedHash = repo.payloadHashOf(key).orElse("");
        if (!hash.equals(storedHash)) {
            throw new LedgerException("intentKey", "chave de intenção já usada com conteúdo diferente");
        }
        return existing;
    }

    private static Movement appendAndReplay(PortfolioRepository repo, Movement row, String hash) {
        Movement saved = repo.insert(row, hash);
        LedgerReplay.replay(repo.listAll()); // throws -> transaction rolls back
        return saved;
    }

    private static Movement base(MovementRequest req, PositionMode mode, Long qty, BigDecimal price, long amount) {
        return new Movement(0, req.intentKey(), req.date(), req.kind(), req.investmentTypeId(), mode, qty, price, amount,
                req.classification() == null ? null : req.classification().name(), req.reason(), req.note(), null, false, "USER", Instant.now());
    }

    private static void requirePositiveAmount(MovementRequest req) {
        if (req.amountCents() == null || req.amountCents() <= 0) throw new LedgerException("amount", "valor deve ser maior que zero");
    }

    private static InvestmentLedgerInfo requireInvestment(PortfolioRepository repo, long id) {
        return repo.investmentInfo(id).orElseThrow(() -> new LedgerException("investmentTypeId", "investimento inexistente: " + id));
    }

    private static InvestmentLedgerInfo requireActiveOpened(PortfolioRepository repo, MovementRequest req) {
        if (req.investmentTypeId() == null) throw new LedgerException("investmentTypeId", "investimento obrigatório");
        InvestmentLedgerInfo info = requireInvestment(repo, req.investmentTypeId());
        if (info.archived()) throw new LedgerException("archived", "investimento arquivado: desarquive para movimentar");
        if (info.positionMode() == null) throw new LedgerException("opening", "investimento sem marco de abertura: registre a abertura antes de movimentar");
        PortfolioState s = LedgerReplay.replay(repo.listAll());
        Position p = s.position(req.investmentTypeId());
        if (!p.isOpen()) throw new LedgerException("opening", "investimento sem marco de abertura no ledger");
        if (p.reconciliationPending()) throw new LedgerException("reconciliation", "estado legado ambíguo: reconcilie antes de movimentar");
        return info;
    }

    /** Cash with legacy snapshots but no opening marker is ambiguous: block until recognized. */
    private static void requireCashRecognized(PortfolioRepository repo) {
        PortfolioState s = LedgerReplay.replay(repo.listAll());
        if (s.cashReconciliationPending()) throw new LedgerException("reconciliation", "caixa com abertura pendente de reconciliação");
        if (!s.cashOpened() && repo.latestLegacyCashCents().isPresent()) {
            throw new LedgerException("opening", "caixa possui snapshots legados: registre a abertura de caixa antes de movimentar");
        }
    }
}
