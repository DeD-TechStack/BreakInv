package com.daniel.infrastructure.persistence.backup;

import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.core.domain.repository.IStockPriceProvider;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.repository.DatabaseLedgerAccess;
import com.daniel.infrastructure.persistence.repository.FlowRepository;
import com.daniel.infrastructure.persistence.repository.InvestmentTypeRepository;
import com.daniel.infrastructure.persistence.repository.SnapshotRepository;
import com.daniel.infrastructure.persistence.repository.TransactionRepository;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Synthetic ledger fixtures on a temp-file database, written through the product's own use case. */
final class BackupTestSupport {

    static final LocalDate DAY0 = LocalDate.of(2026, 3, 2);

    private BackupTestSupport() {
    }

    static String url(Path dbFile) {
        return "jdbc:sqlite:" + dbFile.toString().replace("\\", "/");
    }

    static DailyTrackingUseCase useCase() {
        IStockPriceProvider noPrices = ticker -> null;
        return new DailyTrackingUseCase(new FlowRepository(), new InvestmentTypeRepository(), new SnapshotRepository(),
                new TransactionRepository(), noPrices, new DatabaseLedgerAccess());
    }

    /** Two investments, cash, buys, a sell and one day of snapshots. */
    static DailyTrackingUseCase seed(String prefix) {
        DailyTrackingUseCase uc = useCase();
        uc.movement(MovementRequest.deposit(prefix + "-dep", DAY0, 500_000L, null));
        int a = uc.createInvestment(stock(prefix + " ATIVO A", "FICTA1"),
                DailyTrackingUseCase.OpeningSpec.zero(prefix + "-open-a", DAY0, PositionMode.QUANTITY));
        int b = uc.createInvestment(stock(prefix + " ATIVO B", "FICTB1"),
                DailyTrackingUseCase.OpeningSpec.zero(prefix + "-open-b", DAY0, PositionMode.QUANTITY));
        uc.movement(MovementRequest.buy(prefix + "-buy-a", DAY0.plusDays(1), a, 10, new BigDecimal("20.50"), null));
        uc.movement(MovementRequest.buy(prefix + "-buy-b", DAY0.plusDays(2), b, 3, new BigDecimal("101.10"), null));
        uc.movement(MovementRequest.sell(prefix + "-sell-a", DAY0.plusDays(3), a, 4, new BigDecimal("25"), "parcial"));
        uc.takeSnapshotIfNeeded(DAY0.plusDays(3));
        return uc;
    }

    /** One more deposit: changes the database (and its header change counter). */
    static void change(DailyTrackingUseCase uc, String key) {
        uc.movement(MovementRequest.deposit(key, DAY0.plusDays(10), 1_000L, null));
    }

    static DailyTrackingUseCase.NewInvestment stock(String name, String ticker) {
        return new DailyTrackingUseCase.NewInvestment(name, "ACOES", "ALTA", DAY0, null, null, "ACAO",
                null, null, ticker, null, null, null);
    }

    /**
     * Damages {@code table} in a CLOSED database file: its root page gets an invalid page type, so
     * reading it fails as malformed while the schema (page 1) still opens.
     */
    static void corruptTable(Path dbFile, String table) throws Exception {
        long root;
        long pageSize;
        try (Connection c = java.sql.DriverManager.getConnection(url(dbFile)); java.sql.Statement st = c.createStatement()) {
            try (java.sql.ResultSet rs = st.executeQuery(
                    "SELECT rootpage FROM sqlite_master WHERE type = 'table' AND name = '" + table + "'")) {
                rs.next();
                root = rs.getLong(1);
            }
            try (java.sql.ResultSet rs = st.executeQuery("PRAGMA page_size")) {
                rs.next();
                pageSize = rs.getLong(1);
            }
        }
        if (root < 2) throw new IllegalStateException("unexpected root page " + root);
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(dbFile,
                java.nio.file.StandardOpenOption.WRITE)) {
            ch.write(java.nio.ByteBuffer.wrap(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}),
                    (root - 1) * pageSize);
        }
    }

    /**
     * Index damage on structurally valid pages, in a CLOSED database file: {@code index} is
     * redeclared on {@code otherColumn}, so its entries no longer match their rows ("row N missing
     * from index"). integrity_check reports it; quick_check does not compare index entries with rows.
     */
    static void corruptIndex(Path dbFile, String index, String table, String otherColumn) throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(url(dbFile)); java.sql.Statement st = c.createStatement()) {
            st.execute("PRAGMA writable_schema = ON");
            int n = st.executeUpdate("UPDATE sqlite_master SET sql = 'CREATE INDEX " + index + " ON " + table + "("
                    + otherColumn + ")' WHERE type = 'index' AND name = '" + index + "'");
            st.execute("PRAGMA writable_schema = OFF");
            if (n != 1) throw new IllegalStateException("index not found: " + index);
        }
    }

    /** Overwrites the header magic of a database file, even one the app has open: it is no longer a database. */
    static void corruptHeader(Path dbFile) throws Exception {
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(dbFile,
                java.nio.file.StandardOpenOption.WRITE)) {
            ch.write(java.nio.ByteBuffer.wrap("BreakInv-danos\0\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII)), 0);
        }
    }

    static DatabaseFingerprint liveFingerprint() throws SQLException {
        try (Connection c = Database.newConnection()) {
            return DatabaseFingerprint.of(c);
        }
    }

    static BackupService service(Path dbFile, Path folder, Clock clock) {
        return new BackupService(dbFile, folder, BackupService.SourceAccess.opening(Database::newConnection), clock,
                RetentionPolicy.defaults());
    }

    static List<String> names(Path folder) throws Exception {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> s = Files.list(folder)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    static List<String> copies(Path folder) throws Exception {
        return names(folder).stream().filter(n -> BackupNames.kindOfCopy(n).isPresent()).toList();
    }

    /** Clock the test moves forward. */
    static final class MutableClock extends Clock {
        private Instant now;
        private final ZoneId zone;

        MutableClock(Instant start) {
            this(start, ZoneOffset.UTC);
        }

        private MutableClock(Instant start, ZoneId zone) {
            this.now = start;
            this.zone = zone;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return new MutableClock(now, z);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Suffixes in a fixed order, then random-looking unique ones. */
    static java.util.function.Supplier<String> suffixes(String... first) {
        AtomicInteger i = new AtomicInteger();
        return () -> {
            int n = i.getAndIncrement();
            return n < first.length ? first[n] : String.format("%06x", 0x100000 + n);
        };
    }
}
