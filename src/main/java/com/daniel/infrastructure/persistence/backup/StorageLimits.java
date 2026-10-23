package com.daniel.infrastructure.persistence.backup;

/**
 * The three configurable limits of DESENHO-BRK31 §5.1, in bytes. The defaults are BRK-29's values read in binary
 * units, the same reading {@link RetentionPolicy} already uses for "1 GB" (1 MiB = 2^20, 1 GiB = 2^30 bytes).
 *
 * @param warnDbBytes       the database file at or above this size raises an informative warning
 * @param reserveFloorBytes the "+ 64 MB" of the general reserve, free space below 2 × D + floor
 * @param writeLimitBytes   free space below this refuses new user writes
 */
public record StorageLimits(long warnDbBytes, long reserveFloorBytes, long writeLimitBytes) {

    public static final long MIB = 1L << 20;
    public static final long GIB = 1L << 30;

    public static final long MIN_WARN = MIB;
    public static final long MAX_WARN = 1024 * GIB;
    /** Well above the largest routine transaction (estimated below 1 MB by BRK-29, measured in BRK-31 u3). */
    public static final long MIN_FLOOR = 16 * MIB;
    public static final long MAX_FLOOR = 16 * GIB;

    public static StorageLimits defaults() {
        return new StorageLimits(100 * MIB, 64 * MIB, 64 * MIB);
    }

    /** Why these limits cannot be used, in the user's language; null when every value is inside its range. */
    public String problem() {
        if (warnDbBytes < MIN_WARN || warnDbBytes > MAX_WARN) {
            return "O aviso do tamanho do banco precisa ficar entre " + Sizes.text(MIN_WARN) + " e "
                    + Sizes.text(MAX_WARN) + ".";
        }
        if (reserveFloorBytes < MIN_FLOOR || reserveFloorBytes > MAX_FLOOR) {
            return "A margem da reserva precisa ficar entre " + Sizes.text(MIN_FLOOR) + " e " + Sizes.text(MAX_FLOOR)
                    + ".";
        }
        if (writeLimitBytes < MIN_FLOOR || writeLimitBytes > MAX_FLOOR) {
            return "O limite de gravação precisa ficar entre " + Sizes.text(MIN_FLOOR) + " e " + Sizes.text(MAX_FLOOR)
                    + ".";
        }
        return null;
    }
}
