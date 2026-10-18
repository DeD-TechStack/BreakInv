package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.presentation.view.FxStubs;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.DetailsPane;
import com.daniel.presentation.view.util.Motion;
import com.daniel.presentation.view.util.UiExecutor;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Carteira: closing the details pane because the DATA changed (a filter excluded the selected
 * row, a refresh replaced the list) must not move the keyboard focus, and a refresh that keeps
 * the same registration selected must not hide and re-animate the pane. Only the USER closing the
 * pane (Esc / X) clears the selection and hands the focus back to the table.
 *
 * <p>Headless: a Scene (no Stage) is enough for {@link Scene#getFocusOwner()}; the page's worker
 * is the single-threaded {@link UiExecutor}, so a refresh is awaited deterministically by queuing
 * a marker behind it and then draining the FX queue.
 *
 * <p>Reproduces the GUI evidence {@code evidencias/gui/pilot/filter-focus-loss.json}: with the
 * search field focused, typing a term that excludes the only row moved the focus to the TableView.
 */
class InvestmentTypesPageFocusTest {

    static final InvestmentType ONLY = new InvestmentType(7, "Ativo fictício E2", "ACOES", "ALTA",
            LocalDate.of(2026, 10, 15), null, new BigDecimal("120.00"), "ACAO", null, null, "FICT1",
            new BigDecimal("20.00"), 6, null);

    final List<InvestmentType> registrations = new ArrayList<>(List.of(ONLY));
    InvestmentTypesPage page;
    Parent view;
    Scene scene;
    TableView<InvestmentType> table;
    DetailsPane details;
    TextField search;
    CheckBox showArchived;

    @BeforeEach
    void setUp() throws Exception {
        Motion.configure(null);
        FxTestSupport.runAndWait(() -> {
            page = new InvestmentTypesPage(FxStubs.dailyWithTypes(registrations));
            view = page.view();
            scene = new Scene(view, 1280, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            ((Region) view).resize(1280, 800);
            view.applyCss();
            view.layout();
        });
        awaitRefresh();
        table = field("table");
        details = field("details");
        search = field("searchField");
        showArchived = field("showArchived");
    }

    @AfterEach
    void tearDown() {
        Motion.configure(null);
    }

    @SuppressWarnings("unchecked")
    <T> T field(String name) throws Exception {
        Field f = InvestmentTypesPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(page);
    }

    /** The refresh's worker step runs on the UiExecutor; its UI step is a runLater queued before ours. */
    static void awaitRefresh() throws Exception {
        UiExecutor.get().submit(() -> {}).get(10, TimeUnit.SECONDS);
        FxTestSupport.runAndWait(() -> {});
        FxTestSupport.runAndWait(() -> {});
    }

    @Test
    void filterThatExcludesTheSelectedRow_hidesDetails_butKeepsTheFocusInTheSearchField() {
        FxTestSupport.runAndWait(() -> {
            assertEquals(1, table.getItems().size(), "fixture row listed");
            table.getSelectionModel().select(0);
            assertTrue(details.isVisible(), "selecting opens the details");
            search.requestFocus();
            assertSame(search, scene.getFocusOwner(), "precondition: the user is typing in the search field");

            search.setText("zzzz");

            assertTrue(table.getItems().isEmpty(), "the term excludes the only row");
            assertFalse(details.isVisible(), "details of a row that is no longer listed are hidden");
            assertSame(search, scene.getFocusOwner(), "focus must stay where the user is typing");
        });
    }

    static final InvestmentType SECOND = new InvestmentType(8, "Segundo ativo", "RENDA_FIXA", "ALTA",
            LocalDate.of(2026, 10, 15), new BigDecimal("12.5"), new BigDecimal("500.00"), "PREFIXADO", null, null, null,
            null, null, null);

    @Test
    void filterThatExcludesTheSelectedRow_butKeepsANeighbour_doesNotSwitchTheDetailsToTheNeighbour() throws Exception {
        registrations.add(SECOND);
        FxTestSupport.runAndWait(() -> showArchived.setSelected(true)); // refresh lists both
        awaitRefresh();
        FxTestSupport.runAndWait(() -> {
            assertEquals(2, table.getItems().size());
            table.getSelectionModel().select(0);
            assertEquals(ONLY.id(), table.getSelectionModel().getSelectedItem().id());
            search.requestFocus();

            search.setText("Segundo"); // excludes ONLY, keeps SECOND

            assertEquals(1, table.getItems().size());
            assertNull(table.getSelectionModel().getSelectedItem(), "the filter must not silently select the neighbour");
            assertFalse(details.isVisible(), "no details of an asset the user did not pick");
            assertSame(search, scene.getFocusOwner());
        });
    }

    @Test
    void userClosingThePane_returnsFocusToTheTable_andClearsTheSelection() {
        FxTestSupport.runAndWait(() -> {
            table.getSelectionModel().select(0);
            search.requestFocus();
            details.requestClose();
            assertFalse(details.isVisible());
            assertNull(table.getSelectionModel().getSelectedItem(), "Esc/X also deselects");
            assertSame(table, scene.getFocusOwner(), "Esc/X hands the focus back to the table");
        });
    }

    @Test
    void refreshKeepingTheSameRegistration_keepsDetailsOpenWithoutReanimating_andDoesNotMoveFocus() throws Exception {
        FxTestSupport.runAndWait(() -> {
            table.getSelectionModel().select(0);
            Motion.settle(details);
            assertTrue(details.isVisible());
            showArchived.requestFocus();
            assertSame(showArchived, scene.getFocusOwner());
            showArchived.setSelected(true); // triggers refresh()
        });
        awaitRefresh();
        FxTestSupport.runAndWait(() -> {
            InvestmentType sel = table.getSelectionModel().getSelectedItem();
            assertNotNull(sel, "selection survives the refresh");
            assertEquals(ONLY.id(), sel.id());
            assertTrue(details.isVisible(), "pane stays open");
            assertFalse(Motion.isAnimating(details), "no entrance animation replayed on refresh");
            assertEquals(1.0, details.getOpacity(), 1e-9);
            assertSame(showArchived, scene.getFocusOwner(), "a data refresh never steals the focus");
        });
    }

    @Test
    void refreshWhoseResultNoLongerListsTheSelection_hidesDetails_withoutStealingFocus() throws Exception {
        FxTestSupport.runAndWait(() -> {
            table.getSelectionModel().select(0);
            assertTrue(details.isVisible());
            showArchived.requestFocus();
        });
        registrations.clear(); // the registration is gone from the next load (archived/deleted elsewhere)
        FxTestSupport.runAndWait(() -> showArchived.setSelected(true));
        awaitRefresh();
        FxTestSupport.runAndWait(() -> {
            assertTrue(table.getItems().isEmpty());
            assertNull(table.getSelectionModel().getSelectedItem());
            assertFalse(details.isVisible(), "details of a registration that is no longer listed are hidden");
            assertSame(showArchived, scene.getFocusOwner(), "a data refresh never steals the focus");
        });
    }
}
