package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.domain.entity.StatementEntry;
import com.daniel.core.domain.ledger.MovementKind;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.core.service.DailyTrackingUseCase.InvestmentStatus;
import com.daniel.core.util.MoneyFormat;
import com.daniel.presentation.view.FxStubs;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.DetailsPane;
import com.daniel.presentation.view.components.StatStrip;
import com.daniel.presentation.view.util.Breakpoint;
import com.daniel.presentation.view.util.UiPreferences;
import com.daniel.presentation.view.window.MemoryPreferences;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.daniel.presentation.view.pages.Brk12V1Support.NBSP;
import static com.daniel.presentation.view.pages.Brk12V1Support.assertPtBr;
import static com.daniel.presentation.view.pages.Brk12V1Support.call;
import static com.daniel.presentation.view.pages.Brk12V1Support.detailFields;
import static com.daniel.presentation.view.pages.Brk12V1Support.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BRK-12 v1 on Carteira, Relatórios and Configurações, with the JVM in en-US: the quantities and counts of the
 * "class E" points with their thousands dot ("Qtd. 1.500", "1.500 un.", "1.500 x R$ 12,50", 1.500 positions), the unit
 * prices of the Relatórios in money with two places, the reference rates in pt-BR (V1:RF-01.1 to .3, .8, RF-05.2).
 */
class Brk12V1OtherPagesTest {

    private final Deque<Runnable> worker = new ArrayDeque<>();
    private Locale previous;

    @BeforeEach
    void english() {
        previous = Locale.getDefault();
        Locale.setDefault(Locale.US);
    }

    @AfterEach
    void restore() {
        Locale.setDefault(previous);
    }

    void drain() {
        while (!worker.isEmpty()) worker.poll().run();
    }

    // ── Carteira ─────────────────────────────────────────────────────────────

    /** 1.500 registrations: a stock of 1.500 shares at R$ 20,00, a fixed income at 13,50 % a.a., and 1.498 plain ones. */
    static List<InvestmentType> registrations() {
        List<InvestmentType> regs = new ArrayList<>();
        regs.add(new InvestmentType(1, "Ativo fictício FICT0", "ACOES", "ALTA", LocalDate.of(2026, 10, 15), null,
                new BigDecimal("30000.00"), "ACAO", null, null, "FICT0", new BigDecimal("20.00"), 1500, null));
        regs.add(new InvestmentType(2, "Tesouro Prefixado 2031", "RENDA_FIXA", "ALTA", LocalDate.of(2026, 10, 15),
                new BigDecimal("13.50"), new BigDecimal("500000.00"), "PREFIXADO", null, null, null, null, null, null));
        for (int id = 3; id <= 1500; id++) {
            regs.add(new InvestmentType(id, "Ativo " + id, "RENDA_FIXA", "ALTA", LocalDate.of(2026, 10, 15), null,
                    new BigDecimal("100.00"), "PREFIXADO", null, null, null, null, null, null));
        }
        return regs;
    }

    InvestmentTypesPage carteira() {
        InvestmentTypesPage page = new InvestmentTypesPage(FxStubs.dailyWithTypes(registrations()),
                new UiPreferences(new MemoryPreferences()), worker::add, Runnable::run, warning -> {});
        drain();
        return page;
    }

    @Test
    void carteira_theCountOfPositionsAndTheQuantityColumnHaveTheThousandsDot() throws Exception {
        FxTestSupport.runAndWait(() -> {
            InvestmentTypesPage page = carteira();
            StatStrip strip = field(page, "strip");
            assertEquals("1.500", strip.valueLabel(InvestmentTypesPage.STAT_COUNT).getText(), "1.500 positions");
            TableView<InvestmentType> table = field(page, "table");
            TableColumn<InvestmentType, ?> quantity = table.getColumns().stream().filter(c -> "Qtd.".equals(c.getText()))
                    .findFirst().orElseThrow();
            assertEquals("1.500", quantity.getCellData(registrations().get(0)));
            assertEquals("—", quantity.getCellData(registrations().get(1)), "no quantity stays a dash");
        });
    }

    @Test
    void carteira_theDetailWritesQuantityPriceAndRateInPtBr() throws Exception {
        FxTestSupport.runAndWait(() -> {
            InvestmentTypesPage page = carteira();
            TableView<InvestmentType> table = field(page, "table");
            DetailsPane details = field(page, "details");
            List<InvestmentType> regs = registrations();

            table.getSelectionModel().select(table.getItems().stream().filter(i -> i.id() == 1).findFirst().orElseThrow());
            assertEquals("1.500 un. × preço médio R$" + NBSP + "20,00", ((Label) details.lookup(".details-hint")).getText());
            Map<String, String> stock = detailFields(details);
            assertEquals("1.500", stock.get("Quantidade"));
            assertEquals("R$" + NBSP + "20,00", stock.get("Preço médio"));

            table.getSelectionModel().select(table.getItems().stream().filter(i -> i.id() == 2).findFirst().orElseThrow());
            assertEquals("13,50%", detailFields(details).get("Rentab. anual"));
            assertEquals(1500, regs.size());
        });
    }

    @Test
    void carteira_theSaleHintWritesTheMaximumQuantityWithThousands() throws Exception {
        FxTestSupport.runAndWait(() -> {
            InvestmentTypesPage page = carteira();
            InvestmentStatus byQuantity = new InvestmentStatus(1, "Ativo fictício FICT0", true, PositionMode.QUANTITY, 1500,
                    3_000_000, false, false, null, false, 0, null, null);
            assertEquals("Reduz a posição (máx. 1.500 un.) e aumenta o caixa. Ganho realizado = venda − custo médio.",
                    page.sellHint(byQuantity));
            InvestmentStatus byValue = new InvestmentStatus(2, "Tesouro", true, PositionMode.VALUE, 0, 3_000_000,
                    false, false, null, false, 0, null, null);
            assertEquals("Resgate ao custo (máx. " + MoneyFormat.brl(3_000_000) + "): volta ao caixa sem ganho inferido.",
                    page.sellHint(byValue));
        });
    }

    @Test
    void noTextOfTheCarteiraIsEnUsOrWithoutThousands() throws Exception {
        InvestmentTypesPage[] holder = new InvestmentTypesPage[1];
        FxTestSupport.runAndWait(() -> {
            InvestmentTypesPage page = carteira();
            holder[0] = page;
            Brk12V1Support.styled(page.view(), 1280, 800);
            page.onBreakpoint(Breakpoint.WIDE);
            Brk12V1Support.settle(page.view(), 1280, 800);
            TableView<InvestmentType> table = field(page, "table");
            table.getSelectionModel().select(table.getItems().stream().filter(i -> i.id() == 1).findFirst().orElseThrow());
            Brk12V1Support.settle(page.view(), 1280, 800);
        });
        FxTestSupport.runAndWait(() -> {
            Brk12V1Support.settle(holder[0].view(), 1280, 800);
            assertPtBr(holder[0].view(), "Carteira");
        });
    }

    // ── Relatórios ───────────────────────────────────────────────────────────

    private static StatementEntry entry(long id, MovementKind kind, Long quantity, BigDecimal unitPrice, String classification) {
        return new StatementEntry(LocalDate.of(2026, 10, 5), kind, null, id, null, 7L, "Ativo fictício", PositionMode.QUANTITY,
                quantity, unitPrice, 1_875_000, classification, null, null, null, null, false, false);
    }

    private static StatementEntry legacyBuy() {
        return new StatementEntry(LocalDate.of(2026, 10, 5), null, "BUY", null, 9L, 7L, "CDB antigo", null, 1500L,
                new BigDecimal("12.5"), 1_875_000, null, null, null, null, null, false, true);
    }

    ReportsPage reports() {
        return new ReportsPage(ReportsPageStateTest.legacyDaily(Map.of(), null), Runnable::run, Runnable::run);
    }

    private static String descriptionOf(ReportsPage page, StatementEntry entry) throws Exception {
        ReportsPage.ExtractRow row = (ReportsPage.ExtractRow) call(page, "toRow", new Class<?>[]{StatementEntry.class}, entry);
        return row.description();
    }

    @Test
    void reports_quantitiesAndUnitPricesInTheStatementAreWrittenInPtBr() throws Exception {
        FxTestSupport.runAndWait(() -> {
            ReportsPage page = reports();
            String unit = "R$" + NBSP + "12,50";
            assertTrue(descriptionOf(page, entry(1, MovementKind.BUY, 1500L, new BigDecimal("12.5"), null))
                    .startsWith("Compra de Ativo fictício — 1.500 x " + unit), "buy");
            assertTrue(descriptionOf(page, entry(2, MovementKind.SELL, 1500L, new BigDecimal("12.5"), null))
                    .startsWith("Venda de Ativo fictício — 1.500 x " + unit), "sell");
            assertTrue(descriptionOf(page, entry(3, MovementKind.OPENING, 1500L, null, null))
                    .startsWith("Saldo inicial de Ativo fictício — 1.500 un."), "opening");
            assertTrue(descriptionOf(page, entry(4, MovementKind.RECONCILIATION, 1500L, null, null))
                    .startsWith("Revisão de saldo de Ativo fictício — 1.500 un."), "review");
            assertTrue(descriptionOf(page, entry(5, MovementKind.ADJUSTMENT, 1500L, null, "CORRECTION"))
                    .contains(" — +1.500 un."), "positive adjustment");
            assertTrue(descriptionOf(page, entry(6, MovementKind.ADJUSTMENT, -1500L, null, "CORRECTION"))
                    .contains(" — -1.500 un."), "negative adjustment");
            assertTrue(descriptionOf(page, legacyBuy()).contains(" — 1.500 x " + unit), "old record");
        });
    }

    @Test
    void reports_theUnitPriceIsMoneyWithTwoPlaces_roundedHalfUp() throws Exception {
        FxTestSupport.runAndWait(() -> {
            ReportsPage page = reports();
            assertTrue(descriptionOf(page, entry(1, MovementKind.BUY, 10L, new BigDecimal("12.345"), null))
                    .startsWith("Compra de Ativo fictício — 10 x R$" + NBSP + "12,35"), "12,345 shows 12,35");
            assertTrue(descriptionOf(page, entry(2, MovementKind.BUY, 10L, new BigDecimal("12.3"), null))
                    .startsWith("Compra de Ativo fictício — 10 x R$" + NBSP + "12,30"), "12,3 shows 12,30");
            assertTrue(descriptionOf(page, entry(3, MovementKind.BUY, 10L, new BigDecimal("1234.5"), null))
                    .startsWith("Compra de Ativo fictício — 10 x R$" + NBSP + "1.234,50"), "thousands");
        });
    }

    @Test
    void reports_theDetailWritesQuantityAndUnitPriceInPtBr() throws Exception {
        FxTestSupport.runAndWait(() -> {
            ReportsPage page = reports();
            page.showEntries(List.of(entry(1, MovementKind.BUY, 1500L, new BigDecimal("12.5"), null)), 0, 95_000L);
            TableView<ReportsPage.ExtractRow> table = field(page, "table");
            ReportsPage.ExtractRow row = table.getItems().get(0);
            call(page, "showDetails", new Class<?>[]{ReportsPage.ExtractRow.class}, row);
            DetailsPane details = field(page, "details");
            Map<String, String> shown = detailFields(details);
            assertEquals("1.500", shown.get("Quantidade"));
            assertEquals("R$" + NBSP + "12,50", shown.get("Preço unitário"));
        });
    }

    @Test
    void noTextOfTheReportsIsEnUsOrWithoutThousands() throws Exception {
        ReportsPage[] holder = new ReportsPage[1];
        FxTestSupport.runAndWait(() -> {
            ReportsPage page = reports();
            holder[0] = page;
            Brk12V1Support.styled(page.view(), 1280, 800);
            page.onBreakpoint(Breakpoint.WIDE);
            page.showEntries(List.of(
                    entry(1, MovementKind.BUY, 1500L, new BigDecimal("12.5"), null),
                    entry(2, MovementKind.SELL, 1500L, new BigDecimal("1234.5"), null),
                    entry(3, MovementKind.OPENING, 1500L, null, null),
                    entry(4, MovementKind.ADJUSTMENT, -1500L, null, "CORRECTION")), 1_234_567_890L, 2_169_548_750L);
            Brk12V1Support.settle(page.view(), 1280, 800);
            TableView<ReportsPage.ExtractRow> table = field(page, "table");
            call(page, "showDetails", new Class<?>[]{ReportsPage.ExtractRow.class}, table.getItems().get(0));
            Brk12V1Support.settle(page.view(), 1280, 800);
        });
        FxTestSupport.runAndWait(() -> {
            Brk12V1Support.settle(holder[0].view(), 1280, 800);
            assertPtBr(holder[0].view(), "Relatórios");
        });
    }

    // ── Configurações ────────────────────────────────────────────────────────

    @Test
    void settings_theReferenceRatesAreWrittenInPtBr_withThousands() throws Exception {
        FxTestSupport.runAndWait(() -> {
            ConfiguracoesPage page = new ConfiguracoesPage(new UiPreferences(new MemoryPreferences()));
            StatStrip rates = field(page, "ratesStrip");
            Class<?>[] types = {String.class, String.class};
            call(page, "setRate", types, ConfiguracoesPage.STAT_CDI, "0.135");
            call(page, "setRate", types, ConfiguracoesPage.STAT_SELIC, "0.1365");
            call(page, "setRate", types, ConfiguracoesPage.STAT_IPCA, "12.345");
            assertEquals("13,50%", rates.valueLabel(ConfiguracoesPage.STAT_CDI).getText());
            assertEquals("13,65%", rates.valueLabel(ConfiguracoesPage.STAT_SELIC).getText());
            assertEquals("1.234,50%", rates.valueLabel(ConfiguracoesPage.STAT_IPCA).getText());
        });
    }

    @Test
    void noTextOfTheSettingsIsEnUsOrWithoutThousands() throws Exception {
        FxTestSupport.runAndWait(() -> {
            ConfiguracoesPage page = new ConfiguracoesPage(new UiPreferences(new MemoryPreferences()));
            Brk12V1Support.styled(page.view(), 1280, 800);
            page.onBreakpoint(Breakpoint.WIDE);
            Class<?>[] types = {String.class, String.class};
            call(page, "setRate", types, ConfiguracoesPage.STAT_CDI, "0.135");
            call(page, "setRate", types, ConfiguracoesPage.STAT_SELIC, "0.1365");
            call(page, "setRate", types, ConfiguracoesPage.STAT_IPCA, "12.345");
            Brk12V1Support.settle(page.view(), 1280, 800);
            assertPtBr(page.view(), "Configurações");
        });
    }
}
