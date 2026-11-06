package com.daniel.infrastructure.persistence.backup;

import com.daniel.core.domain.ledger.Movement;
import com.daniel.core.service.PortfolioService;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.repository.DatabaseLedgerAccess;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static com.daniel.infrastructure.persistence.backup.BackupTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * BRK-32 S2:RF-02 and RF-06 case 2: one test per outcome of the service "Liberar espaço", on a temp-folder database
 * with free pages made by saving and deleting plans.
 */
class SpaceReleaseTest extends SpaceReleaseTestBase {

    // ---- the threshold, as a pure function --------------------------------------------------------------------

    @Test
    void threshold_isAtLeastOneMiB_andAtLeastFivePercentOfTheData() {
        assertFalse(SpaceRelease.passesThreshold(MIB + MIB / 2, 40 * MIB), "1,5 MiB de 40 MiB: menos de 5%");
        assertTrue(SpaceRelease.passesThreshold(MIB + MIB / 2, 20 * MIB), "1,5 MiB de 20 MiB: passa");
        assertFalse(SpaceRelease.passesThreshold(MIB * 9 / 10, 10 * MIB), "0,9 MiB de 10 MiB: menos de 1 MiB");
    }

    @Test
    void threshold_edges() {
        assertTrue(SpaceRelease.passesThreshold(MIB, 20 * MIB), "exatamente 1 MiB e exatamente 5%");
        assertFalse(SpaceRelease.passesThreshold(MIB - 1, 20 * MIB), "um byte abaixo de 1 MiB");
        assertFalse(SpaceRelease.passesThreshold(MIB, 20 * MIB + 21), "5% de 20 MiB + 21 bytes passa de 1 MiB");
        assertTrue(SpaceRelease.passesThreshold(MIB, 10 * MIB), "mais de 5% e mais de 1 MiB");
        assertFalse(SpaceRelease.passesThreshold(-1, 0), "ganho negativo nunca passa");
        assertFalse(SpaceRelease.passesThreshold(0, 0));
    }

    // ---- SEM_GANHO --------------------------------------------------------------------------------------------

    @Test
    void semGanho_withoutFreePages_copiesNothing_compactsNothing() throws Exception {
        long d = Files.size(db);
        long counterBefore = counter();

        SpaceRelease.Result r = rig.release();

        assertEquals(SpaceRelease.Outcome.SEM_GANHO, r.outcome(), r.detail());
        assertEquals(d, r.bytesBefore());
        assertEquals(d, r.bytesAfter());
        assertEquals(List.of(), copies(folder), "nenhuma cópia foi criada");
        assertEquals(0, rig.compactions.get(), "nada foi compactado");
        assertEquals(counterBefore, counter(), "os dados não foram tocados");
        assertEquals(d, Files.size(db));
        assertReleased("SEM_GANHO por páginas");
    }

    @Test
    void semGanho_withALowExpectedGain_keepsTheCopyAsANormalCopy() throws Exception {
        // the injected reader says there are 10 MiB to reuse, on a real database that has none to speak of
        rig.pages = () -> new DatabasePages.Reading(4096, 5_000, 2_560);
        long d = Files.size(db);
        long counterBefore = counter();
        DatabaseFingerprint before = liveFingerprint();

        SpaceRelease.Result r = rig.release();

        assertEquals(SpaceRelease.Outcome.SEM_GANHO, r.outcome(), r.detail());
        List<String> pre = preMaintenanceCopies();
        assertEquals(1, pre.size(), "a cópia pré-manutenção foi feita (passo 5)");
        long copyBytes = Files.size(folder.resolve(pre.get(0)));
        assertTrue(d - copyBytes < MIB, "a cópia real saiu do tamanho de D: D=" + d + " cópia=" + copyBytes);
        assertTrue(BackupVerifier.verify(folder.resolve(pre.get(0))).faithfulTo(before), "a cópia está verificada");
        assertEquals(0, rig.compactions.get(), "o ganho previsto não passou do limiar: nada foi compactado");
        assertEquals(counterBefore, counter());
        assertEquals(d, Files.size(db));
        assertReleased("SEM_GANHO por ganho previsto");
    }

    // ---- BUSY and RECUSADA ------------------------------------------------------------------------------------

    @Test
    void busy_whenAnotherOperationHoldsTheLock() throws Exception {
        fragmentedFixture();
        ReentrantLock busy = backups.busyLock();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            busy.lock();
            try {
                locked.countDown();
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                busy.unlock();
            }
        }, "operacao-em-andamento");
        holder.start();
        try {
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            long d = Files.size(db);

            SpaceRelease.Result r = rig.release();

            assertEquals(SpaceRelease.Outcome.BUSY, r.outcome(), r.detail());
            assertEquals(0, rig.guard.acquired, "nem chegou a pedir a guarda de instância");
            assertEquals(0, rig.compactions.get());
            assertEquals(List.of(), copies(folder));
            assertEquals(d, Files.size(db));
            assertTrue(busy.isLocked(), "a trava de quem estava operando continua com ele");
        } finally {
            release.countDown();
            holder.join(10_000);
        }
        assertFalse(busy.isLocked());
    }

    @Test
    void recusada_whenAnotherInstanceIsOpen() throws Exception {
        fragmentedFixture();
        rig.guard.allow = false;
        long d = Files.size(db);

        SpaceRelease.Result r = rig.release();

        assertEquals(SpaceRelease.Outcome.RECUSADA, r.outcome(), r.detail());
        assertTrue(r.detail().contains(rig.guard.refusal()), "o motivo é o da guarda: " + r.detail());
        assertEquals(List.of(), copies(folder), "nenhuma cópia: a recusa vem antes de medir");
        assertEquals(0, rig.compactions.get());
        assertEquals(d, Files.size(db));
        assertReleased("RECUSADA por outra instância");
    }

    @Test
    void recusada_withAReadOpen_byTheExclusiveProbe_withoutCompacting() throws Exception {
        fragmentedFixture();
        DatabaseFingerprint before = liveFingerprint();
        long d = Files.size(db);
        long counterBefore = counter();
        DatabaseLedgerAccess access = new DatabaseLedgerAccess();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> reader = CompletableFuture.supplyAsync(() -> access.read((repo, regs) -> {
            regs.listAll();
            int n = repo.listAll().size();
            inside.countDown();
            try {
                assertTrue(release.await(30, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return n;
        }));
        assertTrue(inside.await(10, TimeUnit.SECONDS));

        long t0 = System.nanoTime();
        SpaceRelease.Result during = rig.release();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        release.countDown();
        reader.get(10, TimeUnit.SECONDS);

        assertEquals(SpaceRelease.Outcome.RECUSADA, during.outcome(), during.detail());
        assertTrue(during.detail().contains("outra operação está usando os dados"), during.detail());
        assertTrue(elapsedMs < 20_000, "terminou sem esperar para sempre (" + elapsedMs + " ms)");
        assertEquals(0, rig.compactions.get(), "a sonda recusou antes de compactar");
        assertEquals(d, Files.size(db));
        assertEquals(counterBefore, counter());
        assertEquals(before, liveFingerprint(), "nada mudou");
        assertReleased("RECUSADA pela sonda");

        // once the read is over, the same request goes through
        SpaceRelease.Result after = rig.release();
        assertEquals(SpaceRelease.Outcome.FEITA, after.outcome(), after.detail());
        assertEquals(before, liveFingerprint());
        assertReleased("FEITA depois da leitura");
    }

    @Test
    void recusada_becauseTheDataChangedAfterTheCopy_withoutCompacting() throws Exception {
        fragmentedFixture();
        // a write lands between the copy (step 5) and the exclusivity (step 7)
        rig.exclusive = body -> {
            change(uc, "b-1");
            return Database.runExclusive(body);
        };
        List<Movement> ledgerBefore = PortfolioService.onDatabase().history();

        SpaceRelease.Result r = rig.release();

        assertEquals(SpaceRelease.Outcome.RECUSADA, r.outcome(), r.detail());
        assertTrue(r.detail().contains("os dados mudaram"), r.detail());
        assertEquals(0, rig.compactions.get(), "os dados mudaram depois da cópia: nada foi compactado");
        assertEquals(0, rig.wayBacks.get(), "e nada voltou da cópia, que não tem a mudança");
        List<Movement> ledgerAfter = PortfolioService.onDatabase().history();
        assertEquals(ledgerBefore.size() + 1, ledgerAfter.size(), "a gravação feita depois da cópia continua nos dados");
        assertReleased("RECUSADA por mudança depois da cópia");
    }

    // ---- exceptions before the compaction ---------------------------------------------------------------------

    @Test
    void falhouMantida_whenReadingThePagesThrows() throws Exception {
        rig.pages = () -> {
            throw new java.sql.SQLException("leitura das páginas recusada no teste");
        };
        long d = Files.size(db);

        SpaceRelease.Result r = rig.release();

        assertEquals(SpaceRelease.Outcome.FALHOU_MANTIDA, r.outcome(), r.detail());
        assertTrue(r.detail().contains("leitura das páginas recusada no teste"), r.detail());
        assertEquals(0, rig.compactions.get());
        assertEquals(List.of(), copies(folder));
        assertEquals(d, Files.size(db));
        assertReleased("exceção ao ler as páginas");
    }

    @Test
    void falhouMantida_whenTheCopyFails_withTheReasonOfTheCopy() throws Exception {
        fragmentedFixture();
        backups.hooks(new BackupService.Hooks() {
            @Override
            public void beforePublish(java.nio.file.Path verifiedTemp) throws IOException {
                throw new IOException("falha da cópia no teste");
            }
        });
        long d = Files.size(db);
        long counterBefore = counter();

        SpaceRelease.Result r = rig.release();

        assertEquals(SpaceRelease.Outcome.FALHOU_MANTIDA, r.outcome(), r.detail());
        assertTrue(r.detail().contains("falha da cópia no teste"), "o motivo da cópia: " + r.detail());
        assertEquals(0, rig.compactions.get(), "sem cópia verificada não se compacta");
        assertEquals(counterBefore, counter());
        assertEquals(d, Files.size(db));
        assertReleased("falha ao copiar");
    }

    // ---- FEITA ------------------------------------------------------------------------------------------------

    @Test
    void feita_onTheRealDriver_shrinksTheFile_emptiesTheFreelist_keepsTheContent() throws Exception {
        fragmentedFixture();
        DatabaseFingerprint fingerprintBefore = liveFingerprint();
        List<Movement> ledgerBefore = PortfolioService.onDatabase().history();
        long dBefore = Files.size(db);
        long[] pagesBefore = pragmas(db);
        long counterBefore = counter();
        CountingGuard guard = new CountingGuard();

        SpaceRelease.Result r = new SpaceRelease(backups, guard).release();

        assertEquals(SpaceRelease.Outcome.FEITA, r.outcome(), r.detail());
        long dAfter = Files.size(db);
        long[] pagesAfter = pragmas(db);
        long counterAfter = counter();
        assertEquals(dBefore, r.bytesBefore(), "D antes");
        assertEquals(dAfter, r.bytesAfter(), "tamanho depois");
        assertTrue(dAfter < dBefore, "o arquivo ficou menor: " + dBefore + " -> " + dAfter);
        assertTrue(pagesBefore[2] > 0, "fixture: havia páginas livres");
        assertEquals(pagesBefore[2], r.freePagesBefore());
        assertEquals(0, pagesAfter[2], "as páginas livres foram a zero");
        assertEquals(0, r.freePagesAfter());
        assertEquals(fingerprintBefore, liveFingerprint(), "a impressão digital é a mesma");
        assertTrue(counterAfter > counterBefore, "o contador de mudanças subiu: " + counterBefore + " -> " + counterAfter);
        assertEquals(ledgerBefore, PortfolioService.onDatabase().history(), "o livro é o mesmo");
        assertEquals(guard.acquired, guard.released);
        assertFalse(backups.busyLock().isLocked());

        List<String> pre = preMaintenanceCopies();
        assertEquals(1, pre.size(), "a cópia pré-manutenção ficou na pasta (nada foi apagado)");
        assertTrue(BackupVerifier.verify(folder.resolve(pre.get(0))).faithfulTo(fingerprintBefore),
                "e é uma cópia verificada dos dados de antes");
        assertEquals(List.of(), names(folder).stream().filter(BackupNames::isTemp).toList(), "nenhum temporário ficou");

        System.out.println("BRK32S2 FEITA antes=" + dBefore + " depois=" + dAfter + " livres_antes=" + pagesBefore[2]
                + " livres_depois=" + pagesAfter[2] + " contador_antes=" + counterBefore + " contador_depois="
                + counterAfter);
    }

    @Test
    void feita_whenOnlyReopenSharedThrows_theDetailCarriesTheError() throws Exception {
        fragmentedFixture();
        DatabaseFingerprint before = liveFingerprint();
        long dBefore = Files.size(db);
        rig.exclusive = body -> Database.runExclusive(access -> body.run(new Database.ExclusiveAccess() {
            @Override
            public Connection newConnection() throws java.sql.SQLException {
                return access.newConnection();
            }

            @Override
            public Connection reopenShared() {
                throw new IllegalStateException("reabertura recusada no teste");
            }
        }));

        SpaceRelease.Result r = rig.release();

        assertEquals(SpaceRelease.Outcome.FEITA, r.outcome(), "o arquivo compactado está conferido no disco: " + r.detail());
        assertTrue(r.detail().contains("reabertura recusada no teste"), "o erro vai no detail: " + r.detail());
        assertEquals(0, rig.wayBacks.get(), "não houve volta");
        assertTrue(r.bytesAfter() < dBefore);
        assertReleased("FEITA com reopenShared lançando");

        Database.open();
        assertEquals(before, liveFingerprint(), "o arquivo compactado tem o mesmo conteúdo");
        assertEquals(0, pragmas(db)[2], "e nenhuma página livre");
    }
}
