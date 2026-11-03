package com.daniel.infrastructure.persistence.backup;

import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * Category of a failure of the SQLite driver (BRK-31 u4a). The code decides first: the extended name
 * of a {@link SQLiteException}, then the primary code ({@code getErrorCode() & 0xFF}); the message
 * decides only when the code says nothing specific, because its wording changes between versions.
 */
public final class SqliteErrors {

    public enum Category { SPACE, IO, CANT_OPEN, WRITE_DENIED, BUSY, DAMAGED, OTHER }

    /** A word of the message; {@code driverOnly} words count only on a {@link SQLException}. */
    private record Word(String text, Category category, boolean driverOnly) {
    }

    /** In the order of the table: the first word found decides. */
    private static final List<Word> WORDS = List.of(
            new Word("sqlite_full", Category.SPACE, false),
            new Word("disk is full", Category.SPACE, false),
            new Word("not enough space", Category.SPACE, false),
            new Word("espaço insuficiente", Category.SPACE, false),
            new Word("sqlite_ioerr", Category.IO, true),
            new Word("disk i/o error", Category.IO, true),
            new Word("sqlite_cantopen", Category.CANT_OPEN, false),
            new Word("unable to open", Category.CANT_OPEN, false),
            new Word("sqlite_readonly", Category.WRITE_DENIED, true),
            new Word("readonly database", Category.WRITE_DENIED, true),
            new Word("sqlite_perm", Category.WRITE_DENIED, true),
            new Word("access permission denied", Category.WRITE_DENIED, true),
            new Word("sqlite_busy", Category.BUSY, false),
            new Word("database is locked", Category.BUSY, false),
            new Word("database table is locked", Category.BUSY, true),
            new Word("malformed", Category.DAMAGED, true),
            new Word("corrupt", Category.DAMAGED, true),
            new Word("not a database", Category.DAMAGED, true));

    private SqliteErrors() {
    }

    /** The category of this exception alone (its causes are not read). */
    static Category of(Throwable t) {
        Category byCode = byCode(t);
        if (byCode != Category.OTHER) return byCode;
        return byMessage(t);
    }

    /** The first category other than OTHER in the exception and its causes, from the outside in. */
    public static Category ofChain(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            Category c = of(t);
            if (c != Category.OTHER) return c;
        }
        return Category.OTHER;
    }

    private static Category byCode(Throwable t) {
        if (t instanceof SQLiteException se) {
            SQLiteErrorCode code = se.getResultCode();
            if (code != null && code != SQLiteErrorCode.UNKNOWN_ERROR) {
                Category byName = byName(code.name());
                if (byName != Category.OTHER) return byName;
            }
        }
        if (t instanceof SQLException se && se.getErrorCode() > 0) {
            return byPrimary(se.getErrorCode() & 0xFF);
        }
        return Category.OTHER;
    }

    private static Category byName(String name) {
        if (name.equals("SQLITE_FULL")) return Category.SPACE;
        if (family(name, "SQLITE_IOERR")) return Category.IO;
        if (family(name, "SQLITE_CANTOPEN")) return Category.CANT_OPEN;
        if (name.equals("SQLITE_PERM") || family(name, "SQLITE_READONLY")) return Category.WRITE_DENIED;
        if (family(name, "SQLITE_BUSY") || family(name, "SQLITE_LOCKED")) return Category.BUSY;
        if (family(name, "SQLITE_CORRUPT") || name.equals("SQLITE_NOTADB")) return Category.DAMAGED;
        return Category.OTHER;
    }

    private static boolean family(String name, String primary) {
        return name.equals(primary) || name.startsWith(primary + "_");
    }

    private static Category byPrimary(int primary) {
        return switch (primary) {
            case 13 -> Category.SPACE;
            case 10 -> Category.IO;
            case 14 -> Category.CANT_OPEN;
            case 3, 8 -> Category.WRITE_DENIED;
            case 5, 6 -> Category.BUSY;
            case 11, 26 -> Category.DAMAGED;
            default -> Category.OTHER;
        };
    }

    private static Category byMessage(Throwable t) {
        String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
        boolean driver = t instanceof SQLException;
        for (Word w : WORDS) {
            if ((driver || !w.driverOnly()) && m.contains(w.text())) return w.category();
        }
        return Category.OTHER;
    }
}
