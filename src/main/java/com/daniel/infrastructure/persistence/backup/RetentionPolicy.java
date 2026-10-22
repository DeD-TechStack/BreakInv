package com.daniel.infrastructure.persistence.backup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which managed copies may go. Defaults (DECISAO-ARMAZENAMENTO §5.3): the 10 most recent automatic
 * copies, within 1 GB for the managed folder. Never deleted: the most recent valid copy, the most
 * recent one without warnings (the restore refuses copies with warnings), the most recent
 * pre-migration copy, the most recent pre-restore copy (the way back from the last restore) and every
 * manual copy. Over the budget the oldest removable automatic copies go first and the plan says how
 * many; when the protected copies alone exceed the budget nothing is deleted to fit and the plan
 * reports the conflict.
 */
public record RetentionPolicy(int maxAutomatic, long budgetBytes) {

    public static final int DEFAULT_MAX_AUTOMATIC = 10;
    public static final long DEFAULT_BUDGET_BYTES = 1L << 30;

    public RetentionPolicy {
        if (maxAutomatic < 1) throw new IllegalArgumentException("maxAutomatic must be >= 1");
        if (budgetBytes < 1) throw new IllegalArgumentException("budgetBytes must be >= 1");
    }

    public static RetentionPolicy defaults() {
        return new RetentionPolicy(DEFAULT_MAX_AUTOMATIC, DEFAULT_BUDGET_BYTES);
    }

    /**
     * @param delete         entries to remove, oldest first
     * @param keptBytes      size of what stays
     * @param overBudget     what stays still exceeds the budget (the protected copies alone exceed it)
     * @param protectedBytes size of the copies that are never removed automatically
     * @param budgetDeleted  how many of {@code delete} go only to fit the budget
     */
    record Plan(List<BackupManifest.Entry> delete, long keptBytes, boolean overBudget, long protectedBytes,
                int budgetDeleted) {
    }

    Plan plan(List<BackupManifest.Entry> entries) {
        List<BackupManifest.Entry> valid = new ArrayList<>(entries.stream().filter(BackupManifest.Entry::valid).toList());
        valid.sort(Comparator.comparing(BackupManifest.Entry::created).reversed());

        Set<BackupManifest.Entry> protectedSet = new HashSet<>();
        if (!valid.isEmpty()) protectedSet.add(valid.get(0));
        valid.stream().filter(e -> "ok".equals(e.health())).findFirst().ifPresent(protectedSet::add);
        latestOf(valid, BackupKind.PRE_MIGRATION, protectedSet);
        latestOf(valid, BackupKind.PRE_RESTORE, protectedSet);
        valid.stream().filter(BackupManifest.Entry::manual).forEach(protectedSet::add);

        List<BackupManifest.Entry> delete = new ArrayList<>();
        List<BackupManifest.Entry> keptRemovable = new ArrayList<>();
        int automaticSeen = 0;
        for (BackupManifest.Entry e : valid) {
            if (e.manual()) continue;
            automaticSeen++;
            if (protectedSet.contains(e)) continue;
            if (automaticSeen > maxAutomatic) delete.add(e);
            else keptRemovable.add(e);
        }

        long kept = 0;
        for (BackupManifest.Entry e : valid) if (!delete.contains(e)) kept += e.sizeBytes();
        long protectedBytes = protectedSet.stream().mapToLong(BackupManifest.Entry::sizeBytes).sum();
        int budgetDeleted = 0;
        // over budget: drop the oldest removable copies first, unless the protected ones alone exceed it
        if (protectedBytes <= budgetBytes) {
            for (int i = keptRemovable.size() - 1; i >= 0 && kept > budgetBytes; i--) {
                BackupManifest.Entry e = keptRemovable.get(i);
                delete.add(e);
                kept -= e.sizeBytes();
                budgetDeleted++;
            }
        }
        delete.sort(Comparator.comparing(BackupManifest.Entry::created));
        return new Plan(List.copyOf(delete), kept, kept > budgetBytes, protectedBytes, budgetDeleted);
    }

    private static void latestOf(List<BackupManifest.Entry> newestFirst, BackupKind kind, Set<BackupManifest.Entry> into) {
        newestFirst.stream().filter(e -> kind.tag().equals(e.kind())).findFirst().ifPresent(into::add);
    }
}
