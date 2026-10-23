package com.daniel.presentation.view.pages;

import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.core.service.CountingLedgerAccess;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.repository.AppSettingsRepository;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.util.Motion;
import com.daniel.presentation.view.util.UiExecutor;
import javafx.scene.control.Button;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BRK-36, unit 4: one refresh of each page, measured on the page itself (not on a use case method alone), reads
 * the ledger once and replays it at most once, whatever the number of registrations. The use case is the
 * production wiring on a temp file, counted by {@link CountingLedgerAccess}. Painel and Carteira run on the real
 * UiExecutor; Diversificação and Extrato on their test seams with a queued worker. Offline: BRAPI auto update off,
 * no token.
 */
class PageRefreshReadCountTest {

    static final int ASSETS = 12;

    @TempDir
    Path tempDir;
    String url;
    CountingLedgerAccess counted;
    DailyTrackingUseCase uc;
    final Deque<Runnable> worker = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        Motion.configure(null);
        url = "jdbc:sqlite:" + tempDir.resolve("paginas.db").toString().replace("\\", "/");
        Database.configure(url);
        Database.open();
        new AppSettingsRepository().set("brapi_auto_update", "false");
        counted = new CountingLedgerAccess();
        uc = counted.useCase(ticker -> 12.5);
        LocalDate today = LocalDate.now();
        uc.movement(MovementRequest.deposit("dep", today, 10_000_000L, null));
        for (int i = 0; i < ASSETS; i++) {
            long id = uc.createInvestment(new DailyTrackingUseCase.NewInvestment(String.format("ATIVO %02d", i), "ACOES",
                            "ALTA", today, null, null, "ACAO", null, null, "T" + i, null, null, null),
                    DailyTrackingUseCase.OpeningSpec.zero("open-" + i, today, PositionMode.QUANTITY));
            uc.movement(MovementRequest.buy("buy-" + i, today, id, 10, new BigDecimal("10.00"), null));
        }
        counted.reset();
    }

    @AfterEach
    void tearDown() throws Exception {
        awaitUiExecutor();
        Database.close();
        Motion.configure(null);
    }

    static void awaitUiExecutor() throws Exception {
        UiExecutor.get().submit(() -> {}).get(10, TimeUnit.SECONDS);
        FxTestSupport.runAndWait(() -> {});
        FxTestSupport.runAndWait(() -> {});
    }

    /** Lets every follow-up of a refresh (FX callbacks, a second background step if any) run before counting. */
    static void settle() throws Exception {
        awaitUiExecutor();
        Thread.sleep(300);
        awaitUiExecutor();
    }

    void drain() {
        while (!worker.isEmpty()) worker.poll().run();
    }

    void assertOneRefresh(String page, int replays) {
        assertEquals(1, counted.reads, page + ": " + counted.summary());
        assertEquals(1, counted.ledgerFullReads, page + ": " + counted.summary());
        assertEquals(0, counted.portfolioUnits, page + " reads nothing outside the read: " + counted.summary());
        assertEquals(replays, counted.replays(), page + ": " + counted.summary());
    }

    @Test
    void painel_oneReadOneReplay() throws Exception {
        DashboardPage[] page = new DashboardPage[1];
        FxTestSupport.runAndWait(() -> {
            page[0] = new DashboardPage(uc);
            page[0].view();
        });
        settle();
        counted.reset();
        FxTestSupport.runAndWait(() -> page[0].onShow());
        settle();
        assertOneRefresh("Painel", 1);
        assertEquals(1, counted.legacyTxReads, "Painel: legacy transactions of the month: " + counted.summary());
    }

    @Test
    void carteira_oneReadOneReplay_withAndWithoutArchived() throws Exception {
        InvestmentTypesPage[] page = new InvestmentTypesPage[1];
        FxTestSupport.runAndWait(() -> {
            page[0] = new InvestmentTypesPage(uc);
            page[0].view();
        });
        settle();
        counted.reset();
        FxTestSupport.runAndWait(() -> page[0].onShow());
        settle();
        assertOneRefresh("Carteira", 1);

        counted.reset();
        FxTestSupport.runAndWait(() -> {
            try {
                javafx.scene.control.CheckBox archived = field(InvestmentTypesPage.class, page[0], "showArchived");
                archived.fire();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        settle();
        assertOneRefresh("Carteira com arquivados", 1);
    }

    @Test
    void diversificacao_oneReadOneReplay() throws Exception {
        SavedAnalysisRepository plans = new SavedAnalysisRepository(SavedAnalysisRepository.sqliteFactory(url));
        DiversificationPage[] page = new DiversificationPage[1];
        FxTestSupport.runAndWait(() -> {
            page[0] = new DiversificationPage(uc, plans, worker::add, Runnable::run);
            page[0].view();
            drain();
        });
        counted.reset();
        FxTestSupport.runAndWait(() -> {
            page[0].onShow();
            drain();
        });
        assertOneRefresh("Diversificação", 1);
    }

    @Test
    void extrato_currentPastAndFutureMonth() throws Exception {
        ReportsPage[] page = new ReportsPage[1];
        FxTestSupport.runAndWait(() -> {
            page[0] = new ReportsPage(uc, worker::add, Runnable::run);
            page[0].view();
            drain();
        });
        counted.reset();
        FxTestSupport.runAndWait(() -> {
            page[0].onShow();
            drain();
        });
        assertOneRefresh("Extrato do mês atual", 1);

        counted.reset();
        FxTestSupport.runAndWait(() -> {
            fire(page[0], "btnPrevMonth");
            drain();
        });
        assertEquals(1, counted.reads, "Extrato do mês passado: " + counted.summary());
        assertEquals(1, counted.ledgerFullReads, "Extrato do mês passado: " + counted.summary());
        assertEquals(0, counted.portfolioUnits, "Extrato do mês passado: " + counted.summary());
        assertTrue(counted.replays() <= 1, "Extrato do mês passado: " + counted.summary());

        counted.reset();
        FxTestSupport.runAndWait(() -> {
            fire(page[0], "btnNextMonth");
            fire(page[0], "btnNextMonth");
            worker.pollFirst();   // the stale reload of the current month is superseded; run only the future one
            drain();
        });
        assertOneRefresh("Extrato de um mês futuro", 0);
    }

    static void fire(ReportsPage page, String button) {
        try {
            Button b = field(ReportsPage.class, page, button);
            b.fire();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T field(Class<?> owner, Object page, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(page);
    }
}
