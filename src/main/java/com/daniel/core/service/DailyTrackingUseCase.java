package com.daniel.core.service;

import com.daniel.core.domain.entity.*;
import com.daniel.core.domain.repository.*;
import com.daniel.core.util.MoneyFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;

public final class DailyTrackingUseCase {

    private static final Logger LOG = Logger.getLogger(DailyTrackingUseCase.class.getName());

    private final IFlowRepository flowRepo;
    private final IInvestmentTypeRepository typeRepo;
    private final ISnapshotRepository snapshotRepo;
    private final ITransactionRepository txRepo;
    private final IStockPriceProvider priceProvider;

    public DailyTrackingUseCase(
            IFlowRepository flowRepo,
            IInvestmentTypeRepository typeRepo,
            ISnapshotRepository snapshotRepo,
            ITransactionRepository txRepo,
            IStockPriceProvider priceProvider) {
        this.flowRepo = flowRepo;
        this.typeRepo = typeRepo;
        this.snapshotRepo = snapshotRepo;
        this.txRepo = txRepo;
        this.priceProvider = priceProvider;
    }

    // ========== INVESTMENT TYPES ==========

    public List<InvestmentType> listTypes() {
        return typeRepo.listAll();
    }

    public void createType(String name) {
        typeRepo.save(name);
    }

    public void renameType(int id, String newName) {
        typeRepo.rename(id, newName);
    }

    public void deleteType(int id) {
        typeRepo.delete(id);
    }

    public int createTypeFull(String name, String category, String liquidity,
                              LocalDate investmentDate, BigDecimal profitability,
                              BigDecimal investedValue, String typeOfInvestment,
                              String indexType, BigDecimal indexPercentage,
                              String ticker, BigDecimal purchasePrice, Integer quantity) {
        return typeRepo.createFull(name, category, liquidity, investmentDate,
                profitability, investedValue, typeOfInvestment,
                indexType, indexPercentage, ticker, purchasePrice, quantity);
    }

    public void updateTypeFull(int id, String name, String category, String liquidity,
                               LocalDate investmentDate, BigDecimal profitability,
                               BigDecimal investedValue, String typeOfInvestment,
                               String indexType, BigDecimal indexPercentage,
                               String ticker, BigDecimal purchasePrice, Integer quantity) {
        typeRepo.updateFull(id, name, category, liquidity, investmentDate,
                profitability, investedValue, typeOfInvestment,
                indexType, indexPercentage, ticker, purchasePrice, quantity);
    }

    // ========== TRANSAÇÕES (COMPRA/VENDA) ==========

    public void recordBuy(int investmentTypeId, String name, String ticker,
                          Integer quantity, Long unitPriceCents, long totalCents,
                          LocalDate date) {
        Transaction tx = new Transaction(0, date, investmentTypeId,
                Transaction.BUY, name, ticker, quantity, unitPriceCents, totalCents, null);
        txRepo.insert(tx);
    }

    public void recordSell(int investmentTypeId, String name, String ticker,
                           Integer quantity, Long unitPriceCents, long totalCents,
                           LocalDate date, String note) {
        Transaction tx = new Transaction(0, date, investmentTypeId,
                Transaction.SELL, name, ticker, quantity, unitPriceCents, totalCents, note);
        txRepo.insert(tx);
    }

    public List<Transaction> listTransactions(java.time.YearMonth month) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        return txRepo.listBetween(start, end);
    }

    // ========== SNAPSHOT AUTOMÁTICO ==========

    public void takeSnapshotIfNeeded(LocalDate date) {
        Map<Long, Long> existing = snapshotRepo.getAllInvestimentsForDate(date);
        if (!existing.isEmpty()) {
            return;
        }

        List<InvestmentType> all = typeRepo.listAll();
        if (all.isEmpty()) {
            return;
        }

        for (InvestmentType inv : all) {
            long valueCents = getCurrentValue(inv, date);
            if (valueCents > 0) {
                snapshotRepo.upsertInvestment(date, inv.id(), valueCents, null);
            }
        }
    }

    // ========== CÁLCULO AUTOMÁTICO DE VALOR ATUAL ==========

    public Map<Long, Long> getAllCurrentValues(LocalDate date) {
        List<InvestmentType> all = typeRepo.listAll();
        Map<Long, Long> values = new HashMap<>();

        for (InvestmentType inv : all) {
            long value = getCurrentValue(inv, date);
            values.put((long) inv.id(), value);

            if (value > 0) {
                LOG.fine(String.format("%s: %s", inv.name(), brl(value)));
            } else {
                LOG.fine(String.format("%s: SEM VALOR", inv.name()));
            }
        }

        return values;
    }

    public long getCurrentValue(InvestmentType inv, LocalDate today) {
        if (inv.ticker() != null && !inv.ticker().isBlank() &&
                inv.quantity() != null && inv.purchasePrice() != null) {

            try {
                Double currentPrice = priceProvider.fetchPrice(inv.ticker());
                if (currentPrice != null) {
                    int quantity = inv.quantity();
                    long valueCents = (long)(currentPrice * quantity * 100);

                    LOG.fine(String.format(
                            "[ACAO] %s: Qtd=%d x R$%.2f = %s",
                            inv.ticker(), quantity, currentPrice, brl(valueCents)
                    ));

                    return valueCents;
                }
            } catch (Exception e) {
                LOG.warning(String.format("[BRAPI] %s: %s", inv.ticker(), e.getMessage()));
            }

            // Fallback: usar preço de compra
            double purchasePrice = inv.purchasePrice().doubleValue();
            int quantity = inv.quantity();
            long valueCents = (long)(purchasePrice * quantity * 100);

            LOG.fine(String.format(
                    "[ACAO FALLBACK] %s: Qtd=%d x R$%.2f (preco compra) = %s",
                    inv.ticker(), quantity, purchasePrice, brl(valueCents)
            ));

            return valueCents;
        }

        if (inv.profitability() != null && inv.investedValue() != null &&
                inv.investmentDate() != null) {

            long investedCents = inv.investedValue()
                    .multiply(BigDecimal.valueOf(100))
                    .longValue();

            double annualRate = inv.profitability().doubleValue() / 100.0;

            long months = ChronoUnit.MONTHS.between(inv.investmentDate(), today);
            if (months < 0) months = 0;

            // Converter taxa anual para mensal (juros compostos)
            double monthlyRate = Math.pow(1 + annualRate, 1.0/12) - 1;
            double currentValue = investedCents * Math.pow(1 + monthlyRate, months);

            LOG.fine(String.format(
                    "[RENDA FIXA] %s: %s x %.2f%% a.a. x %d meses = %s",
                    inv.name(), brl(investedCents), annualRate * 100,
                    months, brl((long)currentValue)
            ));

            return (long)currentValue;
        }

        if (inv.investedValue() != null) {
            long valueCents = inv.investedValue()
                    .multiply(BigDecimal.valueOf(100))
                    .longValue();

            LOG.fine(String.format("[OUTRO] %s: %s (valor investido)", inv.name(), brl(valueCents)));

            return valueCents;
        }

        LOG.warning(String.format("[SEM VALOR] %s: sem ticker, rentabilidade ou valor investido", inv.name()));

        return 0L;
    }

    public long getTotalPatrimony(LocalDate today) {
        Map<Long, Long> values = getAllCurrentValues(today);
        long total = values.values().stream()
                .mapToLong(Long::longValue)
                .sum();

        LOG.fine(String.format("PATRIMONIO TOTAL: %s", brl(total)));
        return total;
    }

    public long getTotalProfit(LocalDate today) {
        List<InvestmentType> all = typeRepo.listAll();
        Map<Long, Long> currentValues = getAllCurrentValues(today);

        long totalInvested = 0L;
        long totalCurrent = 0L;

        for (InvestmentType inv : all) {
            if (inv.investedValue() != null) {
                long invested = inv.investedValue()
                        .multiply(BigDecimal.valueOf(100))
                        .longValue();
                totalInvested += invested;
            }

            long current = currentValues.getOrDefault((long)inv.id(), 0L);
            totalCurrent += current;
        }

        long profit = totalCurrent - totalInvested;

        LOG.fine(String.format("LUCRO/PREJUIZO: %s - %s = %s", brl(totalCurrent), brl(totalInvested), brl(profit)));

        return profit;
    }

    /**
     * Calcula o preço médio ponderado de um ticker
     */
    public double getAveragePrice(String ticker) {
        List<InvestmentType> investments = typeRepo.listAll();

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
        List<InvestmentType> investments = typeRepo.listAll();

        int total = 0;
        for (InvestmentType inv : investments) {
            if (ticker.equals(inv.ticker()) && inv.quantity() != null) {
                total += inv.quantity();
            }
        }

        return total;
    }

    /**
     * Agrega snapshots históricos de todos os investimentos por data dentro do intervalo.
     * Útil para construir gráficos de performance com dados reais em vez de projeções.
     *
     * @return TreeMap data→total em centavos, ordenado por data.
     *         Retorna mapa vazio se não houver snapshots no período.
     */
    public TreeMap<LocalDate, Long> getPortfolioSnapshotSeries(LocalDate from, LocalDate to) {
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
     * Agrupa investimentos por ticker
     */
    public Map<String, List<InvestmentType>> groupByTicker() {
        List<InvestmentType> all = typeRepo.listAll();
        Map<String, List<InvestmentType>> grouped = new HashMap<>();

        for (InvestmentType inv : all) {
            if (inv.ticker() != null && !inv.ticker().isBlank()) {
                grouped.computeIfAbsent(inv.ticker(), k -> new ArrayList<>())
                        .add(inv);
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
        if (entry == null) return;

        // Salvar caixa
        if (entry.cashCents() >= 0) {
            snapshotRepo.upsertCash(entry.date(), entry.cashCents());
        }

        for (var e : entry.investmentValuesCents().entrySet()) {
            InvestmentType type = e.getKey();
            Long cents = e.getValue();
            if (cents != null && cents >= 0) {
                snapshotRepo.setInvestimentValue(entry.date(), type.id(), cents);
                snapshotRepo.upsertInvestment(entry.date(), type.id(), cents, null);
            }
        }
    }

    // ========== FLOWS ==========

    public List<Flow> flowsFor(LocalDate date) {
        return flowRepo.listForDate(date);
    }

    public void createFlow(Flow flow) {
        flowRepo.save(flow);
    }

    public void deleteFlow(long flowId) {
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

        // Pre-index yesterday's values by id for O(1) lookup instead of O(n) inner loop
        Map<Integer, Long> prevValueById = new HashMap<>();
        for (var prevMap : prevEntry.investmentValuesCents().entrySet()) {
            prevValueById.put(prevMap.getKey().id(),
                    prevMap.getValue() != null ? prevMap.getValue() : 0L);
        }

        for (var entryMap : entry.investmentValuesCents().entrySet()) {
            InvestmentType t = entryMap.getKey();
            long todayCents = entryMap.getValue() != null ? entryMap.getValue() : 0L;

            long yesterdayCents = prevValueById.getOrDefault(t.id(), 0L);

            investmentTodayCents.put((long) t.id(), todayCents);

            // Calcular lucro considerando fluxos
            List<Flow> flows = flowsFor(date);
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

            long profitCents = todayCents - yesterdayCents - flowsInCents + flowsOutCents;
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

    // ========== RANGE SUMMARY ==========

    public record RangeSummary(
            long totalProfitCents,
            Map<Long, Long> profitByInvestmentCents
    ) {}

    public RangeSummary rangeSummary(LocalDate from, LocalDate to) {
        DailySummary first = summaryFor(from);
        DailySummary last = summaryFor(to);

        long totalProfit = last.totalProfitTodayCents() - first.totalProfitTodayCents();

        Map<Long, Long> profitByInv = new HashMap<>();
        for (Long invId : last.investmentProfitTodayCents().keySet()) {
            long lastProfit = last.investmentProfitTodayCents().getOrDefault(invId, 0L);
            long firstProfit = first.investmentProfitTodayCents().getOrDefault(invId, 0L);
            profitByInv.put(invId, lastProfit - firstProfit);
        }

        return new RangeSummary(totalProfit, profitByInv);
    }

    // ========== FORMATTING HELPERS ==========

    public String brl(long cents) {
        return MoneyFormat.brl(cents);
    }

    public String brlAbs(long cents) {
        return MoneyFormat.brlAbs(cents);
    }
}