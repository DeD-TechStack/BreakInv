package com.daniel.main;

import org.junit.jupiter.api.Test;
import javafx.application.Application;
import java.lang.reflect.Modifier;
import static org.junit.jupiter.api.Assertions.*;

class LauncherTest {
    @Test void jarEntryPointDoesNotTriggerJavaFxModuleLauncherDetection() throws Exception {
        Class<?> entry = Class.forName("com.daniel.main.Launcher");
        assertFalse(Application.class.isAssignableFrom(entry));
        var main = entry.getMethod("main", String[].class);
        assertTrue(Modifier.isPublic(main.getModifiers()));
        assertTrue(Modifier.isStatic(main.getModifiers()));
        assertEquals(void.class, main.getReturnType());
    }
}
