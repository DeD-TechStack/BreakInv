package com.daniel.core.domain.ledger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure replay of movements in (date, id) order. Every invariant (cash >= 0, quantity >= 0,
 * cost >= 0, QUANTITY position with quantity 0 has cost 0, opening before activity and before
 * any reconciliation, VALUE sell <= cost, REVERSAL reconstructible) is checked at each step; a violation
 * throws {@link LedgerException} naming the movement's intent key, so a retroactive entry that
 * breaks a later state is rejected as a whole.
 */
public final class LedgerReplay {

    private LedgerReplay() {
    }

    /** Effects applied by one movement; kept so a REVERSAL can invert them exactly. */
    record Effect(long cashDelta, Long investmentTypeId, long quantityDelta, long costDelta,
                  long realizedDelta, long externalDelta) {
        Effect inverse() {
            return new Effect(Math.negateExact(cashDelta), investmentTypeId, Math.negateExact(quantityDelta),
                    Math.negateExact(costDelta), Math.negateExact(realizedDelta), Math.negateExact(externalDelta));
        }
    }

    public static PortfolioState replay(List<Movement> ordered) {
        try {
            return replayChecked(ordered);
        } catch (ArithmeticException overflow) {
            throw new LedgerException("amount", "soma de valores/quantidades excede o limite suportado");
        }
    }

    private static PortfolioState replayChecked(List<Movement> ordered) {
        long cash = 0, realized = 0, external = 0, openingCapital = 0;
        boolean cashOpened = false, cashPending = false;
        Map<Long, Position> positions = new LinkedHashMap<>();
        Map<Long, Effect> effects = new HashMap<>();
        Map<Long, Movement> byId = new HashMap<>();
        Map<Long, Integer> indexById = new HashMap<>();
        Map<Long, Position> positionBefore = new HashMap<>();
        boolean hasCashOpening = ordered.stream().anyMatch(m -> m.kind() == MovementKind.OPENING && m.isCashOnly());

        for (int i = 0; i < ordered.size(); i++) {
            Movement m = ordered.get(i);
            if (hasCashOpening && !cashOpened && changesCash(m)) {
                throw reject(m, "date", "movimento anterior ao marco de abertura do caixa");
            }
            byId.put(m.id(), m);
            indexById.put(m.id(), i);
            if (m.investmentTypeId() != null) positionBefore.put(m.id(), positions.get(m.investmentTypeId()));
            Effect effect;
            switch (m.kind()) {
                case OPENING, RECONCILIATION -> {
                    boolean pending = m.reconciliationFlag();
                    boolean reconciliation = m.kind() == MovementKind.RECONCILIATION;
                    if (m.isCashOnly()) {
                        if (reconciliation && !cashOpened) throw reject(m, "opening", "reconciliação de caixa sem abertura prévia");
                        openingCapital = Math.addExact(openingCapital, Math.subtractExact(m.amountCents(), cash));
                        cash = m.amountCents();
                        cashOpened = true;
                        cashPending = pending;
                    } else {
                        Position prev = positions.get(m.investmentTypeId());
                        if (reconciliation && prev == null) throw reject(m, "opening", "reconciliação sem abertura prévia");
                        PositionMode mode = m.positionMode() != null ? m.positionMode() : (prev != null ? prev.mode() : null);
                        if (mode == null) throw reject(m, "positionMode", "reconciliação sem abertura prévia");
                        long qty = m.quantity() != null ? m.quantity() : 0L;
                        if (mode == PositionMode.QUANTITY && qty == 0 && m.amountCents() != 0) {
                            throw reject(m, "cost", "posição com quantidade zero não pode ter custo");
                        }
                        openingCapital = Math.addExact(openingCapital,
                                Math.subtractExact(m.amountCents(), prev != null ? prev.costCents() : 0L));
                        positions.put(m.investmentTypeId(), new Position(m.investmentTypeId(), mode, qty, m.amountCents(), pending));
                    }
                    effect = new Effect(0, m.investmentTypeId(), 0, 0, 0, 0);
                }
                case DEPOSIT -> effect = new Effect(m.amountCents(), null, 0, 0, 0, m.amountCents());
                case WITHDRAWAL -> effect = new Effect(-m.amountCents(), null, 0, 0, 0, -m.amountCents());
                case BUY -> {
                    Position p = requireOpen(positions, m);
                    long qty = p.mode() == PositionMode.QUANTITY ? requireQty(m) : 0L;
                    effect = new Effect(-m.amountCents(), m.investmentTypeId(), qty, m.amountCents(), 0, 0);
                }
                case SELL -> {
                    Position p = requireOpen(positions, m);
                    if (p.mode() == PositionMode.QUANTITY) {
                        long qty = requireQty(m);
                        if (qty > p.quantity()) {
                            throw reject(m, "quantity", "venda de " + qty + " excede a posição disponível de " + p.quantity());
                        }
                        long costOut = qty == p.quantity() ? p.costCents() : Cents.proportion(p.costCents(), qty, p.quantity());
                        effect = new Effect(m.amountCents(), m.investmentTypeId(), -qty, -costOut, m.amountCents() - costOut, 0);
                    } else {
                        if (m.amountCents() > p.costCents()) {
                            throw reject(m, "amount", "resgate de " + m.amountCents() + " centavos excede o custo registrado de " + p.costCents());
                        }
                        effect = new Effect(m.amountCents(), m.investmentTypeId(), 0, -m.amountCents(), 0, 0);
                    }
                }
                case ADJUSTMENT -> {
                    long gain = gainOf(m);
                    if (m.isCashOnly()) {
                        effect = new Effect(m.amountCents(), null, 0, 0, gain, 0);
                    } else {
                        requireOpen(positions, m);
                        long qd = m.quantity() != null ? m.quantity() : 0L;
                        effect = new Effect(0, m.investmentTypeId(), qd, m.amountCents(), gain, 0);
                    }
                }
                case REVERSAL -> {
                    Movement original = byId.get(m.reversesId());
                    Effect orig = effects.get(m.reversesId());
                    if (original == null || orig == null) {
                        throw reject(m, "reversesId", "estorno anterior ao movimento original ou original inexistente");
                    }
                    if (original.kind() == MovementKind.OPENING || original.kind() == MovementKind.RECONCILIATION
                            || original.kind() == MovementKind.REVERSAL) {
                        throw reject(m, "reversesId", "não é possível estornar " + original.kind());
                    }
                    requireReconstructible(ordered, indexById.get(original.id()), i, original, m,
                            positionBefore.get(original.id()), effects);
                    effect = orig.inverse();
                }
                default -> throw reject(m, "kind", "tipo desconhecido");
            }

            cash = Math.addExact(cash, effect.cashDelta());
            realized = Math.addExact(realized, effect.realizedDelta());
            external = Math.addExact(external, effect.externalDelta());
            if (cash < 0) throw reject(m, "cash", "caixa ficaria negativo (" + cash + " centavos)");
            if (effect.investmentTypeId() != null && (effect.quantityDelta() != 0 || effect.costDelta() != 0)) {
                Position p = positions.get(effect.investmentTypeId());
                long q = Math.addExact(p.quantity(), effect.quantityDelta());
                long c = Math.addExact(p.costCents(), effect.costDelta());
                if (q < 0) throw reject(m, "quantity", "posição ficaria negativa");
                if (c < 0) throw reject(m, "cost", "custo ficaria negativo");
                if (p.mode() == PositionMode.QUANTITY && q == 0 && c != 0) {
                    throw reject(m, "cost", "posição zerada ficaria com custo " + c + " centavos; corrija o custo junto");
                }
                positions.put(p.investmentTypeId(), p.with(q, c));
            }
            effects.put(m.id(), effect);
        }
        return new PortfolioState(cash, cashOpened, cashPending, positions, realized, external, openingCapital);
    }

    private static boolean changesCash(Movement m) {
        return m.kind() == MovementKind.DEPOSIT || m.kind() == MovementKind.WITHDRAWAL
                || m.kind() == MovementKind.BUY || m.kind() == MovementKind.SELL
                || (m.kind() == MovementKind.ADJUSTMENT && m.isCashOnly());
    }

    /**
     * A REVERSAL is exact only when inverting the original's recorded effect yields the same
     * state as a history without the original. Checked here, inside the ordered replay, so every
     * insertion (retroactive rows and reconciliations included) is re-validated, not only the
     * moment the reversal is created. Between the original and the reversal, in (date, id) order:
     * <ul>
     *   <li>a RECONCILIATION of the same investment (or of cash, when the original moves cash)
     *       re-stated an absolute figure that already absorbed the original: refused;</li>
     *   <li>QUANTITY mode, original BUY/ADJUSTMENT (feeds the average): any SELL, or reversal
     *       of a SELL, released cost computed with it: refused;</li>
     *   <li>QUANTITY mode, original SELL: a BUY/ADJUSTMENT/REVERSAL changed the average and a
     *       later SELL (or reversal of a SELL) used that average: refused. A SELL followed only
     *       by SELLs keeps the average but each later SELL released a cost rounded HALF_UP to
     *       whole cents from a base that already excluded the original; the window is replayed
     *       for this investment alone without the original and refused when quantity, cost or
     *       realized gain differ by even one cent (see {@link #requireExactSellReversal}).</li>
     * </ul>
     * The field is {@code reversesId}; the message points to reconciliation as the explicit path.
     */
    private static void requireReconstructible(List<Movement> ordered, int originalIndex, int reversalIndex,
                                               Movement original, Movement reversal,
                                               Position beforeOriginal, Map<Long, Effect> effects) {
        boolean quantityMode = original.investmentTypeId() != null && original.positionMode() == PositionMode.QUANTITY;
        boolean feedsAverage = quantityMode
                && (original.kind() == MovementKind.BUY || original.kind() == MovementKind.ADJUSTMENT);
        boolean isSellOriginal = quantityMode && original.kind() == MovementKind.SELL;
        boolean originalMovesCash = changesCash(original);
        boolean averageChanged = false;
        boolean sawSellLike = false;
        for (int k = originalIndex + 1; k < reversalIndex; k++) {
            Movement between = ordered.get(k);
            if (between.kind() == MovementKind.RECONCILIATION) {
                boolean sameInvestment = original.investmentTypeId() != null
                        && original.investmentTypeId().equals(between.investmentTypeId());
                boolean cashRestated = between.isCashOnly() && originalMovesCash;
                if (sameInvestment || cashRestated) {
                    throw notReconstructible(reversal, original, "há reconciliação posterior (" + between.date()
                            + ") que já incorporou este movimento em valor absoluto");
                }
                continue;
            }
            if (!quantityMode || !original.investmentTypeId().equals(between.investmentTypeId())) continue;
            boolean sellLike = between.kind() == MovementKind.SELL
                    || (between.kind() == MovementKind.REVERSAL && between.reversesId() != null
                        && isSellId(ordered, between.reversesId()));
            if (feedsAverage && sellLike) {
                throw notReconstructible(reversal, original, "há venda posterior (" + between.date()
                        + ") calculada com este custo");
            }
            if (isSellOriginal) {
                if (sellLike && averageChanged) {
                    throw notReconstructible(reversal, original, "o custo médio mudou após a venda e outra venda ("
                            + between.date() + ") foi calculada com ele");
                }
                if (!sellLike) averageChanged = true; // BUY, ADJUSTMENT or reversal of one of them
                if (sellLike) sawSellLike = true;
            }
        }
        if (isSellOriginal && sawSellLike) {
            requireExactSellReversal(ordered, originalIndex, reversalIndex, original, reversal, beforeOriginal, effects);
        }
    }

    /**
     * Counterfactual for a SELL reversal: replays only this investment's movements between the
     * original and the reversal, starting from the position just before the original and with the
     * original removed. BUY/ADJUSTMENT effects do not depend on the position and are reused; each
     * SELL releases its cost again from the counterfactual base; a REVERSAL inverts the
     * counterfactual effect of its target. Linear in the window, no nested replay.
     */
    private static void requireExactSellReversal(List<Movement> ordered, int originalIndex, int reversalIndex,
                                                 Movement original, Movement reversal,
                                                 Position beforeOriginal, Map<Long, Effect> effects) {
        if (beforeOriginal == null) {
            throw notReconstructible(reversal, original, "posição anterior à venda desconhecida");
        }
        Long invId = original.investmentTypeId();
        long qMain = beforeOriginal.quantity(), cMain = beforeOriginal.costCents(), realizedMain = 0L;
        long qCf = qMain, cCf = cMain, realizedCf = 0L;
        Map<Long, Effect> cfEffects = new HashMap<>();
        for (int k = originalIndex + 1; k < reversalIndex; k++) {
            Movement b = ordered.get(k);
            if (!invId.equals(b.investmentTypeId())) continue;
            Effect main = effects.get(b.id());
            if (main == null) continue; // not applied (cannot happen inside the window)
            Effect cf;
            switch (b.kind()) {
                case SELL -> {
                    long qty = b.quantity() == null ? 0L : b.quantity();
                    if (qty > qCf) throw notReconstructible(reversal, original, "venda posterior (" + b.date()
                            + ") excederia a posição sem esta venda");
                    long costOut = qty == qCf ? cCf : Cents.proportion(cCf, qty, qCf);
                    cf = new Effect(b.amountCents(), invId, -qty, -costOut, b.amountCents() - costOut, 0);
                }
                case REVERSAL -> {
                    Effect target = b.reversesId() == null ? null
                            : cfEffects.getOrDefault(b.reversesId(), effects.get(b.reversesId()));
                    if (target == null || b.reversesId() == original.id()) {
                        throw notReconstructible(reversal, original, "estorno posterior (" + b.date()
                                + ") não pôde ser reconstituído sem esta venda");
                    }
                    cf = target.inverse();
                }
                default -> cf = main; // BUY / ADJUSTMENT: independent of the position; OPENING/RECONCILIATION handled before
            }
            qMain = Math.addExact(qMain, main.quantityDelta());
            cMain = Math.addExact(cMain, main.costDelta());
            realizedMain = Math.addExact(realizedMain, main.realizedDelta());
            qCf = Math.addExact(qCf, cf.quantityDelta());
            cCf = Math.addExact(cCf, cf.costDelta());
            realizedCf = Math.addExact(realizedCf, cf.realizedDelta());
            cfEffects.put(b.id(), cf);
        }
        // main after inverting the original = beforeOriginal + window (main); counterfactual = beforeOriginal + window (cf)
        if (qMain != qCf || cMain != cCf || realizedMain != realizedCf) {
            throw notReconstructible(reversal, original, "o arredondamento do custo médio nas vendas posteriores difere em "
                    + Math.abs(cMain - cCf) + " centavo(s) do histórico sem esta venda");
        }
    }

    private static boolean isSellId(List<Movement> ordered, long id) {
        for (Movement m : ordered) if (m.id() == id) return m.kind() == MovementKind.SELL;
        return false;
    }

    private static LedgerException notReconstructible(Movement reversal, Movement original, String why) {
        return reject(reversal, "reversesId", "estorno não reconstituível de " + original.kind() + " (" + original.date()
                + "): " + why + "; use a reconciliação para corrigir a posição");
    }

    private static long gainOf(Movement m) {
        if (m.classification() == null) return 0L;
        return switch (AdjustmentClassification.valueOf(m.classification())) {
            case INCOME, EXPENSE -> m.amountCents();
            default -> 0L;
        };
    }

    private static Position requireOpen(Map<Long, Position> positions, Movement m) {
        Position p = positions.get(m.investmentTypeId());
        if (p == null) {
            throw reject(m, "date", "movimento anterior ao marco de abertura do investimento " + m.investmentTypeId());
        }
        return p;
    }

    private static long requireQty(Movement m) {
        if (m.quantity() == null || m.quantity() <= 0) throw reject(m, "quantity", "quantidade deve ser inteiro > 0");
        return m.quantity();
    }

    private static LedgerException reject(Movement m, String field, String msg) {
        return new LedgerException(field, msg + " [intenção " + m.intentKey() + ", " + m.date() + "]");
    }
}
