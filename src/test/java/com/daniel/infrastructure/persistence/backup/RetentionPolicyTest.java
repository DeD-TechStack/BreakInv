package com.daniel.infrastructure.persistence.backup;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Retention plan (pure) and the file-name patterns it relies on. */
class RetentionPolicyTest {

    static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    static BackupManifest.Entry entry(BackupKind kind, int day, long size) {
        Instant created = T0.plusSeconds(day * 86_400L);
        String name = BackupNames.copyName(kind, LocalDateTime.of(2026, 10, 1, 10, 0).plusDays(day),
                String.format("%06x", day));
        return new BackupManifest.Entry(name, kind.tag(), BackupManifest.VALID, created.toString(), 3, day, size,
                "sha", "fp", Map.of(), "ok");
    }

    @Test
    void keepsTheTenMostRecentAutomaticCopies_andAlwaysTheProtectedOnes() {
        List<BackupManifest.Entry> all = new ArrayList<>();
        BackupManifest.Entry preMigration = entry(BackupKind.PRE_MIGRATION, 0, 10);
        BackupManifest.Entry manualOld = entry(BackupKind.MANUAL, 1, 10);
        all.add(preMigration);
        all.add(manualOld);
        for (int d = 2; d <= 14; d++) all.add(entry(BackupKind.DAILY, d, 10)); // 13 daily copies

        RetentionPolicy.Plan plan = RetentionPolicy.defaults().plan(all);

        // daily d2..d14: the 10 newest (d5..d14) stay; d2, d3, d4 go; pre-migration and manual stay
        assertEquals(List.of(all.get(2), all.get(3), all.get(4)), plan.delete());
        assertFalse(plan.delete().contains(preMigration), "latest pre-migration copy is protected");
        assertFalse(plan.delete().contains(manualOld), "manual copies are never rotated");
        assertFalse(plan.overBudget());
    }

    @Test
    void latestValidCopyIsNeverDeleted_evenOverBudget() {
        BackupManifest.Entry onlyOne = entry(BackupKind.DAILY, 1, 5_000);
        RetentionPolicy.Plan plan = new RetentionPolicy(10, 1_000).plan(List.of(onlyOne));
        assertTrue(plan.delete().isEmpty());
        assertTrue(plan.overBudget(), "conflict is reported instead of deleting the only recovery");
        assertEquals(5_000, plan.protectedBytes());
    }

    @Test
    void overBudget_dropsOldestRemovableFirst_andReportsWhenOnlyProtectedRemain() {
        BackupManifest.Entry manual = entry(BackupKind.MANUAL, 0, 600);
        BackupManifest.Entry d1 = entry(BackupKind.DAILY, 1, 300);
        BackupManifest.Entry d2 = entry(BackupKind.DAILY, 2, 300);
        BackupManifest.Entry d3 = entry(BackupKind.DAILY, 3, 300); // latest valid: protected
        RetentionPolicy.Plan plan = new RetentionPolicy(10, 1_000).plan(List.of(manual, d1, d2, d3));
        assertEquals(List.of(d1, d2), plan.delete(), "oldest removable first, until protected ones are left");
        assertEquals(2, plan.budgetDeleted(), "the screen says how many went to fit");
        assertEquals(900, plan.keptBytes());
        assertFalse(plan.overBudget());

        // §5.3: when the protected copies alone exceed the budget, nothing is deleted to fit
        RetentionPolicy.Plan tight = new RetentionPolicy(10, 700).plan(List.of(manual, d1, d2, d3));
        assertEquals(List.of(), tight.delete());
        assertEquals(0, tight.budgetDeleted());
        assertTrue(tight.overBudget(), "manual + latest exceed 700 bytes: conflict shown, nothing deleted");
        assertEquals(900, tight.protectedBytes());
    }

    static BackupManifest.Entry withHealth(BackupManifest.Entry e, String health) {
        return new BackupManifest.Entry(e.file(), e.kind(), e.state(), e.createdAt(), e.schemaVersion(),
                e.changeCounter(), e.sizeBytes(), e.sha256(), e.fingerprint(), e.rowCounts(), health);
    }

    @Test
    void latestCopyWithoutWarnings_andLatestPreRestore_areProtectedToo() {
        BackupManifest.Entry preRestore = entry(BackupKind.PRE_RESTORE, 1, 10);
        BackupManifest.Entry healthy = entry(BackupKind.DAILY, 2, 10);
        List<BackupManifest.Entry> all = new ArrayList<>(List.of(preRestore, healthy));
        // later copies share a problem with the live data: the restore refuses them
        for (int d = 3; d <= 6; d++) all.add(withHealth(entry(BackupKind.DAILY, d, 10), "1 referência(s) sem registro"));

        RetentionPolicy.Plan plan = new RetentionPolicy(1, 1L << 30).plan(all);

        assertFalse(plan.delete().contains(healthy), "the latest copy the restore accepts stays");
        assertFalse(plan.delete().contains(preRestore), "the way back from the last restore stays");
        assertEquals(List.of(all.get(2), all.get(3), all.get(4)), plan.delete(), "d3..d5 go; d6 is the latest");
    }

    @Test
    void ignoresInProgressEntries() {
        BackupManifest.Entry pending = new BackupManifest.Entry(
                BackupNames.tempName(false, LocalDateTime.of(2026, 10, 1, 9, 0), "abcdef"), "diaria",
                BackupManifest.IN_PROGRESS, T0.toString(), 0, -1, 0, null, null, Map.of(), null);
        RetentionPolicy.Plan plan = new RetentionPolicy(1, 10).plan(List.of(pending, entry(BackupKind.DAILY, 1, 5)));
        assertTrue(plan.delete().isEmpty());
    }

    @Test
    void namesFollowTheFeaturePatternOnly() {
        String daily = BackupNames.copyName(BackupKind.DAILY, LocalDateTime.of(2026, 10, 22, 3, 15, 0), "a1b2c3");
        assertEquals("breakinv-diaria-20261022-031500-a1b2c3.db", daily);
        assertEquals(BackupKind.DAILY, BackupNames.kindOfCopy(daily).orElseThrow());
        assertTrue(BackupNames.kindOfCopy("breakinv-diaria-20261022-031500-a1b2c3.db.bak").isEmpty());
        assertTrue(BackupNames.kindOfCopy("minha-copia.db").isEmpty());
        assertTrue(BackupNames.kindOfCopy("..\\breakinv-diaria-20261022-031500-a1b2c3.db").isEmpty());
        assertTrue(BackupNames.isTemp(BackupNames.tempName(true, LocalDateTime.of(2026, 10, 22, 3, 15), "00ff00")));
        assertFalse(BackupNames.isTemp(daily));
        assertThrows(IllegalArgumentException.class,
                () -> BackupNames.copyName(BackupKind.MANUAL, LocalDateTime.now(), "../x"));
        assertFalse(BackupKind.MANUAL.automatic());
        assertTrue(BackupKind.PRE_RESTORE.automatic());
    }

    @Test
    void sizesAreShownInBinaryUnitsWithDecimalComma() {
        assertEquals("1 GB", Sizes.text(1L << 30));
        assertEquals("12,3 MB", Sizes.text((long) (12.3 * 1024 * 1024)));
        assertEquals("512 bytes", Sizes.text(512));
        assertEquals("indisponível", Sizes.text(-1));
    }
}
