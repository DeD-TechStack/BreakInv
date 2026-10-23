package com.daniel.infrastructure.persistence.backup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Additional peak space of each data operation, by phase and by place (DESENHO-BRK31 §5.2). Each phase lists what
 * is alive on disk in addition to the starting state; what is already on disk when the estimate is made (a staged
 * copy, a reusable copy) is not counted again, and what an earlier phase removed is not carried over. The peak of a
 * volume is the largest phase sum of the places on that volume, so a shared disk is counted once.
 *
 * <p>The bounds are conservative and validated against the files of real operations on synthetic databases
 * (SpaceEstimateMeasuredTest). All arithmetic saturates at {@link Long#MAX_VALUE}; a negative input size means
 * unknown and gives an unknown estimate, never a zero.
 */
public final class SpaceEstimate {

    /** Where an operation writes: the database folder, the copies folder, SQLite's temp folder, another folder. */
    public enum Place { DATA, COPIES, TEMP, DESTINATION }

    /** Manifest temp, header pages and journal headers. */
    public static final long ALLOWANCE = 64L << 10;

    public record Phase(String name, Map<Place, Long> live) {
        public Phase {
            EnumMap<Place, Long> m = new EnumMap<>(Place.class);
            live.forEach((p, v) -> {
                if (v > 0) m.put(p, v);
            });
            live = Collections.unmodifiableMap(m);
        }

        long on(Place p) {
            return live.getOrDefault(p, 0L);
        }
    }

    public record Estimate(String operation, List<Phase> phases, String unknownReason) {
        public Estimate {
            phases = List.copyOf(phases);
        }

        public boolean known() {
            return unknownReason == null;
        }

        /** The largest amount alive at one place over the phases; -1 when unknown. */
        public long peak(Place place) {
            if (!known()) return -1;
            long max = 0;
            for (Phase p : phases) max = Math.max(max, p.on(place));
            return max;
        }

        /**
         * The peak per volume: within each phase the places of the same volume are summed, then the largest phase
         * is kept. Every place in use must have a volume; an unknown estimate cannot be split by volume.
         */
        public Map<Object, Long> peakPerVolume(Map<Place, ?> volumeOf) {
            if (!known()) throw new IllegalStateException("estimativa desconhecida: " + unknownReason);
            Map<Object, Long> peak = new LinkedHashMap<>();
            for (Phase p : phases) {
                Map<Object, Long> sum = new LinkedHashMap<>();
                p.live().forEach((place, bytes) -> sum.merge(
                        Objects.requireNonNull(volumeOf.get(place), "sem volume para " + place), bytes,
                        SpaceEstimate::add));
                sum.forEach((v, bytes) -> peak.merge(v, bytes, Math::max));
            }
            return Collections.unmodifiableMap(peak);
        }
    }

    private SpaceEstimate() {
    }

    /** A {@code VACUUM INTO} copy of x bytes: at most the pages in use, plus the manifest. */
    public static long copyBound(long x) {
        return add(x, ALLOWANCE);
    }

    /** Rollback journal of a transaction rewriting x bytes: every page plus 8 bytes (page ≥ 512 B) and headers. */
    public static long journalBound(long x) {
        return add(x, x / 32, ALLOWANCE);
    }

    /**
     * SQLite temp files of a verification or fingerprint of x bytes: the sorter behind {@code ORDER BY} spills
     * the rows, and merges may write them once more.
     */
    public static long sortBound(long x) {
        return add(x, x, ALLOWANCE);
    }

    /** Verified copy (manual, automatic, pre-migration) of the current d bytes into the copies folder or elsewhere. */
    public static Estimate verifiedCopy(long d, Place target) {
        String op = "cópia conferida";
        if (d < 0) return unknown(op, "tamanho dos dados desconhecido");
        List<Phase> ph = new ArrayList<>();
        ph.add(phase("impressão digital dos dados", Place.TEMP, sortBound(d)));
        ph.add(phase("cópia", target, copyBound(d)));
        ph.add(phase("conferência da cópia", target, copyBound(d), Place.TEMP, sortBound(d)));
        return new Estimate(op, ph, null);
    }

    /**
     * Restore of a c-byte copy over d bytes of current data.
     *
     * @param staged         the chosen copy is already staged in the copies folder (counted as on disk)
     * @param safetyReusable the last valid copy already holds the current data (no pre-restore copy is written)
     * @param olderVersion   the copy has an older schema, so the restored data is copied again and migrated
     */
    public static Estimate restore(long c, long d, boolean staged, boolean safetyReusable, boolean olderVersion) {
        String op = "restauração";
        if (c < 0 || d < 0) return unknown(op, "tamanho da cópia ou dos dados desconhecido");
        List<Phase> ph = new ArrayList<>();
        long s = staging(ph, c, staged);
        long safety = safetyReusable ? 0 : copyBound(d);
        if (!safetyReusable) {
            ph.add(phase("cópia pré-restauração", Place.COPIES, add(s, safety), Place.TEMP, sortBound(d)));
        }
        replacing(ph, c, d, add(s, safety));
        if (olderVersion) {
            long grow = Math.max(0, c - d);
            ph.add(phase("atualização da cópia restaurada", Place.COPIES, add(s, safety, copyBound(c)),
                    Place.DATA, add(grow, journalBound(c)), Place.TEMP, sortBound(c)));
        }
        return new Estimate(op, ph, null);
    }

    /**
     * Restore over current data too damaged for a verified copy: the verified copy is attempted (its partial file
     * is removed when it fails), then the current file is kept byte for byte.
     */
    public static Estimate restoreOverDamaged(long c, long d, boolean staged) {
        String op = "restauração sobre dados danificados";
        if (c < 0 || d < 0) return unknown(op, "tamanho da cópia ou dos dados desconhecido");
        List<Phase> ph = new ArrayList<>();
        long s = staging(ph, c, staged);
        ph.add(phase("tentativa de cópia conferida", Place.COPIES, add(s, copyBound(d)), Place.TEMP, sortBound(d)));
        long kept = add(s, Math.max(copyBound(d), add(d, ALLOWANCE)));
        ph.add(phase("guarda do arquivo danificado", Place.COPIES, kept));
        replacing(ph, c, d, kept);
        return new Estimate(op, ph, null);
    }

    /** Maintenance (BRK-32), calculation only: pre-maintenance copy, VACUUM and its verification. */
    public static Estimate maintenance(long d, boolean safetyReusable) {
        String op = "manutenção";
        if (d < 0) return unknown(op, "tamanho dos dados desconhecido");
        List<Phase> ph = new ArrayList<>();
        long safety = safetyReusable ? 0 : copyBound(d);
        if (!safetyReusable) ph.add(phase("cópia pré-manutenção", Place.COPIES, safety, Place.TEMP, sortBound(d)));
        ph.add(phase("compactação", Place.COPIES, safety, Place.DATA, journalBound(d), Place.TEMP, copyBound(d)));
        ph.add(phase("conferência", Place.COPIES, safety, Place.TEMP, sortBound(d)));
        return new Estimate(op, ph, null);
    }

    private static long staging(List<Phase> ph, long c, boolean staged) {
        if (staged) return 0;
        long s = add(c, ALLOWANCE);
        ph.add(phase("preparação da cópia escolhida", Place.COPIES, s, Place.TEMP, sortBound(c)));
        return s;
    }

    private static void replacing(List<Phase> ph, long c, long d, long copies) {
        long grow = Math.max(0, c - d);
        ph.add(phase("substituição", Place.COPIES, copies, Place.DATA, add(journalBound(d), grow)));
        ph.add(phase("conferência dos dados restaurados", Place.COPIES, copies, Place.DATA, grow,
                Place.TEMP, sortBound(c)));
        ph.add(phase("volta, se a conferência falhar", Place.COPIES, copies, Place.DATA, add(grow, journalBound(c))));
    }

    private static Estimate unknown(String op, String why) {
        return new Estimate(op, List.of(), why);
    }

    private static Phase phase(String name, Object... placeBytes) {
        Map<Place, Long> m = new EnumMap<>(Place.class);
        for (int i = 0; i < placeBytes.length; i += 2) {
            m.merge((Place) placeBytes[i], (Long) placeBytes[i + 1], SpaceEstimate::add);
        }
        return new Phase(name, m);
    }

    static long add(long... values) {
        long sum = 0;
        for (long v : values) {
            if (v < 0) throw new IllegalArgumentException("tamanho negativo: " + v);
            sum = sum > Long.MAX_VALUE - v ? Long.MAX_VALUE : sum + v;
        }
        return sum;
    }
}
