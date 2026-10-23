package com.daniel.core.service;

import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.domain.ledger.AdjustmentClassification;
import com.daniel.core.domain.ledger.Movement;
import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.core.domain.ledger.ReconciliationRequest;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.repository.InvestmentTypeRepository;
import com.daniel.infrastructure.persistence.repository.SnapshotRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.daniel.core.service.ScreenQueriesCharacterizationTest.D;
import static com.daniel.core.service.ScreenQueriesCharacterizationTest.api;
import static com.daniel.core.service.ScreenQueriesCharacterizationTest.render;
import static com.daniel.core.service.ScreenQueriesCharacterizationTest.viaRead;
import static org.junit.jupiter.api.Assertions.*;

/**
 * BRK-36, unit 3: {@link DailyTrackingUseCase#read()} and {@link DailyTrackingUseCase.PortfolioRead}.
 * Counting (one read and at most one replay per refresh, whatever the number of registrations), isolation between
 * calls of one read, freshness after every kind of write and the field guard. Equivalence of the results is in
 * {@link ScreenQueriesCharacterizationTest}.
 */
class PortfolioReadTest {

    static final int ASSETS = 30;
    static final YearMonth OCT = YearMonth.of(2026, 10);
    static final YearMonth NOV = YearMonth.of(2026, 11);

    @TempDir
    Path tempDir;
    CountingLedgerAccess counted;
    DailyTrackingUseCase uc;
    final List<Long> assets = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Database.configure("jdbc:sqlite:" + tempDir.resolve("leitura.db").toString().replace("\\", "/"));
        Database.open();
        counted = new CountingLedgerAccess();
        uc = counted.useCase(ticker -> ticker.startsWith("T") ? 12.5 : null);
        uc.movement(MovementRequest.deposit("dep", LocalDate.of(2026, 9, 1), 10_000_000L, null));
        for (int i = 0; i < ASSETS; i++) {
            long id = uc.createInvestment(new DailyTrackingUseCase.NewInvestment(String.format("ATIVO %02d", i), "ACOES",
                            "ALTA", LocalDate.of(2026, 9, 1), null, null, "ACAO", null, null, "T" + i, null, null, null),
                    DailyTrackingUseCase.OpeningSpec.zero("open-" + i, LocalDate.of(2026, 9, 1), PositionMode.QUANTITY));
            uc.movement(MovementRequest.buy("buy-" + i, LocalDate.of(2026, 10, 2), id, 10, new BigDecimal("10.00"), null));
            assets.add(id);
        }
        counted.reset();
    }

    @AfterEach
    void tearDown() {
        Database.close();
    }

    void assertOneReadOneReplay(String page, DailyTrackingUseCase.PortfolioRead r, int replays) {
        assertEquals(1, counted.reads, page + ": " + counted.summary());
        assertEquals(1, counted.ledgerFullReads, page + ": " + counted.summary());
        assertEquals(0, counted.portfolioUnits, page + " reads nothing outside the read: " + counted.summary());
        assertEquals(replays, r.replays(), page + ": " + counted.summary());
    }

    /** The sequences of the four pages (DESENHO-BRK36 §3.2) over one read: 1 read, 1 ledger read, at most 1 replay. */
    @Test
    void pageSequences_oneReadAndAtMostOneReplay_whateverTheNumberOfAssets() {
        DailyTrackingUseCase.PortfolioRead r = uc.read();
        r.listTypes();
        r.getAllCurrentValues(D);
        r.getTotalPatrimony(D);
        r.getTotalProfit(D);
        r.getTotalInvestmentValue(D);
        r.statement(OCT);
        r.metrics(D);
        r.cashStatus();
        assertOneReadOneReplay("Painel", r, 1);
        assertEquals(1, counted.legacyTxReads, "legacy transactions of the month: " + counted.summary());

        counted.reset();
        r = uc.read();
        for (InvestmentType t : r.listTypes(true)) r.investmentStatus(t.id());
        r.cashStatus();
        assertOneReadOneReplay("Carteira", r, 1);

        counted.reset();
        r = uc.read();
        r.listTypes();
        r.getAllCurrentValues(D);
        r.getTotalPatrimony(D);
        assertOneReadOneReplay("Diversificação", r, 1);

        counted.reset();
        r = uc.read();
        r.statement(OCT);
        r.getTotalProfit(D);
        r.getTotalPatrimony(D);
        assertOneReadOneReplay("Extrato do mês", r, 1);

        counted.reset();
        r = uc.read();
        assertEquals(List.of(), r.statement(NOV));
        assertOneReadOneReplay("Extrato futuro", r, 0);
    }

    /** Every old signature, called alone, is one read and at most one replay (it was 1 to N+1 before). */
    @Test
    void oldSignatures_oneReadEach() {
        Map<String, Runnable> calls = new java.util.LinkedHashMap<>();
        calls.put("listTypes", () -> uc.listTypes());
        calls.put("listTypes(true)", () -> uc.listTypes(true));
        calls.put("investmentStatus", () -> uc.investmentStatus(assets.get(0)));
        calls.put("cashStatus", () -> uc.cashStatus());
        calls.put("getAllCurrentValues", () -> uc.getAllCurrentValues(D));
        calls.put("getTotalInvestmentValue", () -> uc.getTotalInvestmentValue(D));
        calls.put("getTotalPatrimony", () -> uc.getTotalPatrimony(D));
        calls.put("getTotalProfit", () -> uc.getTotalProfit(D));
        calls.put("metrics", () -> uc.metrics(D));
        calls.put("statement", () -> uc.statement(OCT));
        for (Map.Entry<String, Runnable> call : calls.entrySet()) {
            counted.reset();
            call.getValue().run();
            String why = call.getKey() + ": " + counted.summary();
            assertEquals(1, counted.reads, why);
            assertEquals(1, counted.ledgerFullReads, why);
            assertEquals(0, counted.portfolioUnits, why);
            assertTrue(counted.replays() <= 1, why);
        }
        counted.reset();
        uc.getAllCurrentValues(D);
        assertEquals(1, counted.replays(), "one replay for " + ASSETS + " registrations, not one per registration");
    }

    /** What a caller does with a returned collection never changes the next call of the same read. */
    @Test
    void callsOfOneRead_doNotChangeEachOther() {
        DailyTrackingUseCase.PortfolioRead r = uc.read();
        String before = render(viaRead(r), assets, null);
        List<InvestmentType> types = r.listTypes(true);
        types.clear();
        Map<Long, Long> values = r.getAllCurrentValues(D);
        values.replaceAll((id, v) -> 0L);
        r.statement(OCT).clear();
        assertThrows(UnsupportedOperationException.class, () -> r.metrics(D).valuedAtCost().clear());
        assertEquals(before, render(viaRead(r), assets, null));
        assertEquals(ASSETS, r.listTypes().size());
        assertEquals(1, r.replays());
    }

    /**
     * A read taken before a write never changes; a new read and the old signatures see the write at once. Every
     * kind of write, including one made by another connection (another window).
     */
    @Test
    void freshness_afterEveryKindOfWrite() throws Exception {
        long closed = uc.createInvestment(new DailyTrackingUseCase.NewInvestment("ENCERRADO", "ACOES", "ALTA",
                        LocalDate.of(2026, 9, 1), null, null, "ACAO", null, null, "TZ", null, null, null),
                DailyTrackingUseCase.OpeningSpec.zero("open-closed", LocalDate.of(2026, 9, 1), PositionMode.QUANTITY));
        long legacy = new InvestmentTypeRepository().createFull("LEGADO", "OUTROS", "BAIXA", LocalDate.of(2025, 1, 1),
                null, new BigDecimal("50"), null, null, null, null, null, null);
        List<Long> ids = new ArrayList<>(assets);
        ids.add(closed);
        ids.add(legacy);
        long a = assets.get(0);

        fresh("aporte", ids, () -> uc.movement(MovementRequest.deposit("f-dep", LocalDate.of(2026, 10, 5), 5_000L, null)));
        Movement[] buy = new Movement[1];
        fresh("compra", ids, () -> buy[0] = uc.movement(MovementRequest.buy("f-buy", LocalDate.of(2026, 10, 6), a, 2,
                new BigDecimal("11.00"), null)));
        fresh("estorno", ids, () -> uc.movement(MovementRequest.reversal("f-rev", LocalDate.of(2026, 10, 7), buy[0].id(),
                "lançada por engano")));
        fresh("retroativo", ids, () -> uc.movement(MovementRequest.buy("f-retro", LocalDate.of(2026, 9, 15), a, 1,
                new BigDecimal("9.00"), null)));
        fresh("ajuste", ids, () -> uc.movement(MovementRequest.adjustCash("f-adj", LocalDate.of(2026, 10, 8), 300L,
                AdjustmentClassification.INCOME, "juros", null)));
        fresh("revisão", ids, () -> uc.reconcile(ReconciliationRequest.position("f-rec", LocalDate.of(2026, 10, 9), a,
                11L, 11_500L, "conferido na corretora")));
        fresh("arquivamento", ids, () -> uc.archiveType(closed));
        fresh("cadastro novo", ids, () -> uc.createInvestment(new DailyTrackingUseCase.NewInvestment("NOVO", "ACOES",
                        "ALTA", LocalDate.of(2026, 10, 1), null, null, "ACAO", null, null, "TN", null, null, null),
                DailyTrackingUseCase.OpeningSpec.zero("open-new", LocalDate.of(2026, 10, 1), PositionMode.QUANTITY)));
        fresh("reconhecimento", ids, () -> uc.registerOpening(uc.proposeOpening("f-open-legacy",
                LocalDate.of(2026, 10, 10), legacy)));
        fresh("outra conexão", ids, () -> {
            try (Connection other = Database.newConnection()) {
                PortfolioService.onConnection(other).executeMovement(
                        MovementRequest.deposit("f-other", LocalDate.of(2026, 10, 11), 7_000L, "outra janela"));
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    void fresh(String what, List<Long> ids, Runnable write) {
        DailyTrackingUseCase.PortfolioRead r1 = uc.read();
        String before = render(viaRead(r1), ids, null);
        write.run();
        String after = render(viaRead(uc.read()), ids, null);
        assertNotEquals(before, after, what + ": a new read sees the write");
        assertEquals(before, render(viaRead(r1), ids, null), what + ": the read taken before does not change");
        assertEquals(after, render(api(uc), ids, null), what + ": the old signatures see it at once");
    }

    /** The day's snapshot reads on its own: it records the cash after a write, not what an earlier read saw. */
    @Test
    void snapshotOfTheDay_neverUsesAnEarlierRead() {
        DailyTrackingUseCase.PortfolioRead earlier = uc.read();
        long cashBefore = earlier.cashStatus().cashCents();
        uc.movement(MovementRequest.deposit("snap-dep", LocalDate.of(2026, 10, 12), 4_200L, null));
        uc.takeSnapshotIfNeeded(LocalDate.now());
        assertEquals(cashBefore + 4_200L, new SnapshotRepository().getCash(LocalDate.now()));
        assertEquals(cashBefore, earlier.cashStatus().cashCents());
    }

    /** Field guard (DESENHO-BRK36 §5.1e): no class keeps a read between refreshes. */
    @Test
    void noFieldKeepsAPortfolioRead() throws Exception {
        List<String> owners = List.of("com.daniel.core.service.DailyTrackingUseCase",
                "com.daniel.presentation.view.AppShell",
                "com.daniel.presentation.view.pages.DashboardPage",
                "com.daniel.presentation.view.pages.InvestmentTypesPage",
                "com.daniel.presentation.view.pages.DiversificationPage",
                "com.daniel.presentation.view.pages.ReportsPage");
        List<String> offending = new ArrayList<>();
        int inspected = 0;
        for (String owner : owners) {
            List<Class<?>> classes = new ArrayList<>(List.of(Class.forName(owner, false, getClass().getClassLoader())));
            for (int i = 0; i < classes.size(); i++) {
                Class<?> c = classes.get(i);
                classes.addAll(List.of(c.getDeclaredClasses()));
                if (c == DailyTrackingUseCase.PortfolioRead.class) continue;
                for (Field f : c.getDeclaredFields()) {
                    inspected++;
                    if (f.getGenericType().getTypeName().contains("PortfolioRead")) offending.add(c.getName() + "." + f.getName());
                }
            }
        }
        assertTrue(inspected > 20, "fields inspected: " + inspected);
        assertEquals(List.of(), offending);
    }
}
