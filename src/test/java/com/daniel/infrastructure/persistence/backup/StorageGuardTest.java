package com.daniel.infrastructure.persistence.backup;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** BRK-31 u3a: the decision for user writes and the daily snapshot (DESENHO-BRK31 §5.3, first two rows). */
class StorageGuardTest {

    private static final long MIB = 1L << 20;
    private static final Path DB = Path.of("dados", "breakinv.db");

    private final AtomicReference<SpaceProbe.Space> space = new AtomicReference<>();
    private final AtomicInteger probes = new AtomicInteger();
    private final AtomicLong now = new AtomicLong(1_000);
    private final AtomicReference<StorageLimits> limits = new AtomicReference<>(StorageLimits.defaults());
    private final StorageGuard guard = new StorageGuard(DB, path -> {
        probes.incrementAndGet();
        assertEquals(DB.toAbsolutePath().getParent(), path, "mede o volume da pasta dos dados");
        return space.get();
    }, limits::get, now::get);

    private static SpaceProbe.Space free(long bytes) {
        return new SpaceProbe.Measured(bytes, Path.of("."), "VOL");
    }

    @Test
    void belowTheWriteLimit_isRefused_sayingHowMuchIsMissing() {
        space.set(free(64 * MIB - 10 * MIB));

        StorageGuard.Refused r = assertInstanceOf(StorageGuard.Refused.class, guard.userWrite());

        assertEquals(10 * MIB, r.shortfallBytes());
        assertTrue(r.message().contains("Nada foi gravado"), r.message());
        assertTrue(r.message().contains(Sizes.text(10 * MIB)), r.message());
        assertEquals(r.message(), guard.writeGate().refusal());
    }

    @Test
    void exactlyAtTheLimit_isAllowed() {
        space.set(free(64 * MIB));

        assertEquals(new StorageGuard.Allowed(64 * MIB), guard.userWrite());
        assertNull(guard.writeGate().refusal());
        assertTrue(guard.lastWarning().isEmpty());
    }

    @Test
    void unmeasuredSpace_letsTheWriteGoOn_withAWarning() {
        for (SpaceProbe.Space s : new SpaceProbe.Space[]{new SpaceProbe.Unavailable("sem volume"),
                new SpaceProbe.Failed("IOException")}) {
            space.set(s);
            now.addAndGet(StorageGuard.CACHE_NANOS);

            StorageGuard.Unmeasured u = assertInstanceOf(StorageGuard.Unmeasured.class, guard.userWrite());

            assertTrue(u.warning().contains("espaço livre não medido"), u.warning());
            assertNull(guard.writeGate().refusal(), "segue");
            assertEquals(u.warning(), guard.lastWarning().orElseThrow());
        }
        space.set(free(GIB()));
        now.addAndGet(StorageGuard.CACHE_NANOS);
        guard.userWrite();
        assertTrue(guard.lastWarning().isEmpty(), "uma medida boa limpa o aviso");
    }

    @Test
    void theMeasureIsReusedForOneSecond_atMost() {
        space.set(free(GIB()));
        guard.userWrite();
        guard.userWrite();
        now.addAndGet(StorageGuard.CACHE_NANOS - 1);
        guard.userWrite();
        assertEquals(1, probes.get());

        now.addAndGet(1);
        space.set(free(MIB));
        assertInstanceOf(StorageGuard.Refused.class, guard.userWrite(), "depois de 1 s mede de novo");
        assertEquals(2, probes.get());
    }

    @Test
    void changedLimits_takeEffectAtOnce() {
        space.set(free(100 * MIB));
        assertInstanceOf(StorageGuard.Allowed.class, guard.userWrite());

        limits.set(new StorageLimits(100 * MIB, 64 * MIB, 128 * MIB));

        StorageGuard.Refused r = assertInstanceOf(StorageGuard.Refused.class, guard.userWrite());
        assertEquals(28 * MIB, r.shortfallBytes());
    }

    private static long GIB() {
        return 1L << 30;
    }
}
