package com.daniel.infrastructure.persistence.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** BRK-31 u1: the real probe answers through FileStore and never invents a value (DESENHO-BRK31 D-Q4). */
class SpaceProbeTest {

    @Test
    void existingDirectory_isMeasuredOnItsVolume(@TempDir Path dir) {
        SpaceProbe.Measured m = assertInstanceOf(SpaceProbe.Measured.class, SpaceProbe.fileStores().usableSpace(dir));

        assertTrue(m.usableBytes() >= 0);
        assertEquals(dir.toAbsolutePath().normalize(), m.measuredAt());
        assertNotNull(m.volume());
    }

    @Test
    void missingFolder_isMeasuredAtItsNearestExistingParent_withoutCreatingIt(@TempDir Path dir) {
        Path missing = dir.resolve("a").resolve("b");

        SpaceProbe.Measured m = assertInstanceOf(SpaceProbe.Measured.class,
                SpaceProbe.fileStores().usableSpace(missing));
        SpaceProbe.Measured parent = assertInstanceOf(SpaceProbe.Measured.class,
                SpaceProbe.fileStores().usableSpace(dir));

        assertEquals(dir.toAbsolutePath().normalize(), m.measuredAt());
        assertEquals(parent.volume(), m.volume(), "mesmo volume da pasta existente");
        assertFalse(Files.exists(missing));
    }

    @Test
    void noPath_isUnavailable() {
        assertInstanceOf(SpaceProbe.Unavailable.class, SpaceProbe.fileStores().usableSpace(null));
    }
}
