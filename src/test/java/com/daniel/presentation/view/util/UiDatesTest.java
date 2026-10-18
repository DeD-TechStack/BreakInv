package com.daniel.presentation.view.util;

import com.daniel.presentation.view.FxStubs;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.MovementDialog;
import com.daniel.presentation.viewmodel.MovementForm;
import javafx.scene.control.DatePicker;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dates on screen follow the pt-BR UI (dd/MM/yyyy) whatever the JVM's default locale is — the GUI
 * pass saw "10/16/2026" in the dialogs. Only the DatePicker's converter changes: the value stays a
 * {@link LocalDate}, persistence and the API parsers are untouched.
 */
class UiDatesTest {

    @Test
    void impossibleCalendarDatesAreRejectedRatherThanAdjusted() {
        var converter = UiDates.brConverter();
        for (String text : java.util.List.of("31/02/2026", "29/02/2025", "31/04/2026",
                "00/01/2026", "01/13/2026", "2026-02-31")) {
            assertNull(converter.fromString(text), text);
        }
        assertEquals(LocalDate.of(2024, 2, 29), converter.fromString("29/02/2024"));
        assertEquals(LocalDate.of(2025, 2, 28), converter.fromString("28/02/2025"));
        assertEquals(LocalDate.of(2026, 1, 31), converter.fromString("2026-01-31"));
        // No new business-year restriction: preserve LocalDate's proleptic-year contract.
        for (LocalDate date : java.util.List.of(LocalDate.of(0, 1, 1), LocalDate.of(-1, 1, 1),
                LocalDate.of(10000, 1, 1), LocalDate.MIN, LocalDate.MAX)) {
            assertEquals(date, converter.fromString(converter.toString(date)));
        }
    }

    @Test
    void brConverter_formatsAndParsesDayMonthYear_andKeepsTheValueALocalDate() {
        FxTestSupport.runAndWait(() -> {
            DatePicker p = new DatePicker(LocalDate.of(2026, 10, 16));
            UiDates.applyBrFormat(p);
            assertEquals("16/10/2026", p.getConverter().toString(LocalDate.of(2026, 10, 16)));
            assertEquals(LocalDate.of(2026, 10, 16), p.getConverter().fromString("16/10/2026"));
            assertEquals(LocalDate.of(2026, 10, 16), p.getConverter().fromString("2026-10-16"), "ISO typed by hand still accepted");
            assertNull(p.getConverter().fromString("10/16/2026"), "month 16 is not a date: rejected, not silently swapped");
            assertEquals("16/10/2026", p.getEditor().getText(), "the editor shows the pt-BR text");
            assertEquals(LocalDate.of(2026, 10, 16), p.getValue());
        });
    }

    @Test
    void productionDialogs_useTheBrFormat() {
        FxTestSupport.runAndWait(() -> {
            MovementDialog d = new MovementDialog("Aporte externo", null, MovementForm.deposit(), FxStubs.emptyDaily(), m -> {});
            d.getDialogPane().applyCss(); // the frame's ScrollPane only exposes its content once the skin exists
            DatePicker picker = (DatePicker) d.getDialogPane().lookup(".date-picker");
            assertNotNull(picker);
            LocalDate v = picker.getValue();
            assertEquals(String.format("%02d/%02d/%d", v.getDayOfMonth(), v.getMonthValue(), v.getYear()),
                    picker.getConverter().toString(v));
        });
    }
}
