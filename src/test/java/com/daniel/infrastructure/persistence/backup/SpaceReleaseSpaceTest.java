package com.daniel.infrastructure.persistence.backup;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.daniel.infrastructure.persistence.backup.BackupTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * BRK-32 S2:RF-02 step 4 and RF-06 case 3: the guard of the data space with a {@link FakeSpaceProbe}. Below the
 * estimate and without a measure the answer is RECUSADA, and nothing is copied or compacted; the estimate asked is
 * always the one of a new copy.
 */
class SpaceReleaseSpaceTest extends SpaceReleaseTestBase {

    StorageGuard guardOn(SpaceProbe probe) {
        return new StorageGuard(db, sqliteTemp, probe, StorageLimits::defaults, System::nanoTime);
    }

    @Test
    void belowTheEstimate_isRecusada_nothingCopiedNothingCompacted() throws Exception {
        fragmentedFixture();
        backups.spaceCheck(guardOn(FakeSpaceProbe.all(MIB))::operation);
        long d = Files.size(db);
        long counterBefore = counter();
        DatabaseFingerprint before = liveFingerprint();

        SpaceRelease.Result r = rig.release();

        assertEquals(SpaceRelease.Outcome.RECUSADA, r.outcome(), r.detail());
        assertTrue(r.detail().contains("Falta espaço"), "a mensagem da guarda: " + r.detail());
        assertTrue(r.detail().contains("manutenção"), "a operação da estimativa: " + r.detail());
        assertEquals(List.of(), copies(folder), "nada foi copiado");
        assertEquals(0, rig.compactions.get(), "nada foi compactado");
        assertEquals(d, Files.size(db));
        assertEquals(counterBefore, counter());
        assertEquals(before, liveFingerprint());
        assertReleased("RECUSADA por espaço");
    }

    @Test
    void withoutAMeasure_isRecusada_nothingCopiedNothingCompacted() throws Exception {
        fragmentedFixture();
        backups.spaceCheck(guardOn(FakeSpaceProbe.none())::operation);
        long d = Files.size(db);
        long counterBefore = counter();

        SpaceRelease.Result r = rig.release();

        assertEquals(SpaceRelease.Outcome.RECUSADA, r.outcome(), r.detail());
        assertTrue(r.detail().contains("não medido"), "sem medida a operação não começa: " + r.detail());
        assertEquals(List.of(), copies(folder), "nada foi copiado");
        assertEquals(0, rig.compactions.get(), "nada foi compactado");
        assertEquals(d, Files.size(db));
        assertEquals(counterBefore, counter());
        assertReleased("RECUSADA sem medida");
    }

    @Test
    void withRoomOnTheMeasuredVolume_theOperationGoesOn() throws Exception {
        fragmentedFixture();
        backups.spaceCheck(guardOn(FakeSpaceProbe.all(100L * 1024 * MIB))::operation);

        SpaceRelease.Result r = rig.release();

        assertEquals(SpaceRelease.Outcome.FEITA, r.outcome(), r.detail());
        assertEquals(1, rig.compactions.get());
        assertEquals(List.of(1), rig.copiesAtCompaction, "a cópia pré-manutenção já existia quando a compactação começou");
        assertReleased("FEITA com espaço");
    }

    @Test
    void theEstimateAsked_isTheOneOfANewCopy_beforeAnyCopy_withTheDataAndCopiesFolders() throws Exception {
        fragmentedFixture();
        AtomicReference<SpaceEstimate.Estimate> asked = new AtomicReference<>();
        AtomicReference<Map<SpaceEstimate.Place, Path>> where = new AtomicReference<>();
        AtomicInteger copiesAtTheCheck = new AtomicInteger(-1);
        AtomicInteger checks = new AtomicInteger();
        backups.spaceCheck((estimate, places) -> {
            checks.incrementAndGet();
            asked.set(estimate);
            where.set(places);
            try {
                copiesAtTheCheck.set(copies(folder).size());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return new StorageGuard.Allowed(-1);
        });
        long d = Files.size(db);

        SpaceRelease.Result r = rig.release();

        assertEquals(SpaceRelease.Outcome.FEITA, r.outcome(), r.detail());
        assertEquals(1, checks.get(), "a guarda é consultada uma vez, para a operação inteira");
        assertEquals(SpaceEstimate.maintenance(d, false), asked.get(),
                "sempre a conta da cópia nova, a mais exigente (passo 4)");
        assertNotEquals(SpaceEstimate.maintenance(d, true), asked.get());
        assertEquals(0, copiesAtTheCheck.get(), "a guarda responde antes da cópia");
        assertEquals(db.toAbsolutePath().normalize().getParent(), where.get().get(SpaceEstimate.Place.DATA));
        assertEquals(backups.folder(), where.get().get(SpaceEstimate.Place.COPIES));
        assertReleased("FEITA");
    }

    @Test
    void whenThePagesDoNotPassTheThreshold_theGuardIsNotAskedAtAll() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        backups.spaceCheck((estimate, places) -> {
            checks.incrementAndGet();
            return new StorageGuard.Allowed(-1);
        });

        SpaceRelease.Result r = rig.release();

        assertEquals(SpaceRelease.Outcome.SEM_GANHO, r.outcome(), r.detail());
        assertEquals(Files.size(db), r.bytesBefore(), "o resultado traz o D medido");
        assertEquals(0, checks.get(), "o passo 3 vem antes do 4");
        assertReleased("SEM_GANHO");
    }
}
