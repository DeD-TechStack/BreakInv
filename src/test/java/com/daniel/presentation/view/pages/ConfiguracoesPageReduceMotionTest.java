package com.daniel.presentation.view.pages;

import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.util.Motion;
import com.daniel.presentation.view.util.UiPreferences;
import com.daniel.presentation.view.window.MemoryPreferences;
import javafx.scene.control.CheckBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/** The "Reduzir animações" preference lives in Configurações, persists and takes effect at once. */
class ConfiguracoesPageReduceMotionTest {

    @AfterEach
    void tearDown() {
        Motion.configure(null);
    }

    @Test
    void checkboxReflectsAndPersistsThePreference_andMotionFollows() throws Exception {
        MemoryPreferences node = new MemoryPreferences();
        UiPreferences prefs = new UiPreferences(node);
        prefs.setReduceMotion(true);
        Motion.configure(prefs);
        FxTestSupport.runAndWait(() -> {
            ConfiguracoesPage page = new ConfiguracoesPage(prefs);
            Field f = ConfiguracoesPage.class.getDeclaredField("reduceMotionCheck");
            f.setAccessible(true);
            CheckBox cb = (CheckBox) f.get(page);
            assertTrue(cb.isSelected(), "starts from the persisted value");
            cb.fire();
            assertFalse(prefs.reduceMotion());
            assertFalse(Motion.reduced());
            assertFalse(new UiPreferences(node).reduceMotion(), "persisted immediately, no Save button needed");
            cb.fire();
            assertTrue(Motion.reduced());
        });
    }

    /**
     * The production wiring, not the test seam: App configures Motion from {@code forUser()} and
     * the page is built with its public constructor (which also resolves {@code forUser()}).
     * The toggle must reach Motion in the same session. Runs against the user root, which the
     * isolated test harness redirects to a synthetic preferences factory; the original value is
     * restored afterwards.
     */
    @Test
    void productionWiring_pageBuiltWithoutInjection_stillDrivesMotionConfiguredByTheApp() throws Exception {
        UiPreferences appPrefs = UiPreferences.forUser();
        boolean original = appPrefs.reduceMotion();
        try {
            appPrefs.setReduceMotion(false);
            Motion.configure(appPrefs);
            FxTestSupport.runAndWait(() -> {
                ConfiguracoesPage page = new ConfiguracoesPage();
                Field f = ConfiguracoesPage.class.getDeclaredField("reduceMotionCheck");
                f.setAccessible(true);
                CheckBox cb = (CheckBox) f.get(page);
                assertFalse(cb.isSelected());
                cb.fire();
                assertTrue(Motion.reduced(), "the toggle in Configurações must take effect in the running session");
                assertTrue(UiPreferences.forUser().reduceMotion(), "and be persisted");
                cb.fire();
                assertFalse(Motion.reduced());
            });
        } finally {
            appPrefs.setReduceMotion(original);
            Motion.configure(null);
        }
    }
}
