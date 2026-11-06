package com.daniel.infrastructure.persistence.backup;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.daniel.infrastructure.persistence.backup.BackupTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * BRK-32 S2:RF-03 and RF-06 case 5: the single ladder of failures in and after the compaction, with injected
 * collaborators. Each rung is asserted by what the result says and by what the data and the copy look like after.
 *
 * <p>An assertion that could die by an exception of the mutated product is wrapped in
 * {@code assertDoesNotThrow(..., "motivo")}, so a mutation shows up as a failed assertion.
 */
class SpaceReleaseLadderTest extends SpaceReleaseTestBase {

    private Path theCopy() throws Exception {
        List<String> pre = preMaintenanceCopies();
        assertEquals(1, pre.size(), "uma cópia pré-manutenção na pasta");
        return folder.resolve(pre.get(0));
    }

    @Test
    void theCompactorThrows_andTheDataAreIntact_isFalhouMantida_withoutGoingBack() throws Exception {
        fragmentedFixture();
        DatabaseFingerprint before = liveFingerprint();
        long d = Files.size(db);
        long counterBefore = counter();
        rig.compactor = conn -> {
            throw new SQLException("disk is full");
        };

        SpaceRelease.Result r = assertDoesNotThrow(rig::release, "a exceção do compactador vira um resultado, não sobe");

        assertEquals(SpaceRelease.Outcome.FALHOU_MANTIDA, r.outcome(), r.detail());
        assertTrue(r.detail().contains("SPACE"), "a classificação do SqliteErrors vai no detail: " + r.detail());
        assertEquals(1, rig.compactions.get(), "o compactador foi chamado");
        assertEquals(0, rig.wayBacks.get(), "os dados estavam intactos: não houve volta");
        assertEquals(before, liveFingerprint(), "a mesma impressão digital");
        assertEquals(d, Files.size(db));
        assertEquals(counterBefore, counter());
        assertTrue(Files.isRegularFile(theCopy()), "a cópia pré-manutenção continua lá");
        assertReleased("o compactador lança");
    }

    @Test
    void theCompactorDamagesTheContentAndThrows_theDataComeBackFromTheCopy_onTheRealRestore() throws Exception {
        fragmentedFixture();
        DatabaseFingerprint before = liveFingerprint();
        rig.compactor = conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("DELETE FROM allocation_plan");
            }
            throw new SQLException("disk I/O error");
        };

        SpaceRelease.Result r = assertDoesNotThrow(rig::release, "a exceção do compactador vira um resultado, não sobe");

        assertEquals(SpaceRelease.Outcome.FALHOU_MANTIDA, r.outcome(), r.detail());
        assertTrue(r.detail().contains("voltaram da cópia"), r.detail());
        assertEquals(1, rig.wayBacks.get(), "os dados não estavam intactos: voltaram da cópia");
        assertEquals(List.of(theCopy()), rig.wayBackFrom, "a volta usou a cópia pré-manutenção");
        assertEquals(before, liveFingerprint(), "depois da volta o conteúdo é o de antes");
        assertTrue(Files.isRegularFile(theCopy()), "e a cópia continua lá");
        assertReleased("o compactador estraga e lança");
    }

    @Test
    void theVerifierDivergesOnlyTheFirstTime_andTheWayBackIsChecked_isFalhouMantida_voltaramDaCopia() throws Exception {
        fragmentedFixture();
        DatabaseFingerprint before = liveFingerprint();
        AtomicInteger calls = new AtomicInteger();
        rig.verifier = conn -> calls.getAndIncrement() == 0 ? divergentReport() : BackupVerifier.verify(conn);

        SpaceRelease.Result r = assertDoesNotThrow(rig::release, "uma divergência vira um resultado, não sobe");

        assertEquals(SpaceRelease.Outcome.FALHOU_MANTIDA, r.outcome(), r.detail());
        assertTrue(r.detail().contains("voltaram da cópia"), r.detail());
        assertEquals(1, rig.compactions.get());
        assertEquals(1, rig.wayBacks.get(), "a conferência divergiu: voltou da cópia");
        assertEquals(List.of(theCopy()), rig.wayBackFrom);
        assertEquals(2, calls.get(), "uma conferência depois da compactação e outra depois da volta");
        assertEquals(before, liveFingerprint(), "depois da volta o conteúdo é o de antes");
        assertTrue(Files.isRegularFile(theCopy()));
        assertReleased("a conferência diverge uma vez");
    }

    @Test
    void theVerifierDiverges_andTheWayBackThrows_isFalhouCritica_withThePathOfTheCopy() throws Exception {
        fragmentedFixture();
        rig.verifier = conn -> divergentReport();
        rig.wayBack = (conn, copy) -> {
            throw new SQLException("volta recusada no teste");
        };

        SpaceRelease.Result r = assertDoesNotThrow(rig::release, "a volta que lança vira um resultado, não sobe");

        Path copy = theCopy();
        assertEquals(SpaceRelease.Outcome.FALHOU_CRITICA, r.outcome(), r.detail());
        assertTrue(r.detail().contains(copy.toString()), "o caminho da cópia pré-manutenção: " + r.detail());
        assertEquals(1, rig.wayBacks.get());
        assertEquals(1, rig.verifications.get(), "não conferiu depois de uma volta que não terminou");
        assertTrue(Files.isRegularFile(copy), "a cópia pré-manutenção nunca é apagada");
        assertReleased("a volta lança");
    }

    @Test
    void theWayBackFinishes_butItsOwnCheckDiverges_isFalhouCritica() throws Exception {
        fragmentedFixture();
        AtomicInteger calls = new AtomicInteger();
        // the first check after the compaction diverges, and so does the one after the way back
        rig.verifier = conn -> calls.incrementAndGet() <= 2 ? divergentReport() : BackupVerifier.verify(conn);
        rig.wayBack = (conn, copy) -> { };

        SpaceRelease.Result r = assertDoesNotThrow(rig::release, "uma volta que não confere vira um resultado, não sobe");

        Path copy = theCopy();
        assertEquals(SpaceRelease.Outcome.FALHOU_CRITICA, r.outcome(),
                "a volta terminou mas a conferência dela diverge: " + r.detail());
        assertTrue(r.detail().contains(copy.toString()), "o caminho da cópia pré-manutenção: " + r.detail());
        assertEquals(1, rig.wayBacks.get());
        assertEquals(2, calls.get(), "a conferência depois da volta foi feita");
        assertTrue(Files.isRegularFile(copy));
        assertReleased("a conferência da volta diverge");
    }
}
