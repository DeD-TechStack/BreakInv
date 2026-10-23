package com.daniel.infrastructure.persistence.backup;

import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.config.StorageRefusedException;
import com.daniel.infrastructure.persistence.repository.AppSettingsRepository;
import com.daniel.infrastructure.persistence.repository.DatabaseLedgerStore;
import com.daniel.infrastructure.persistence.repository.FlowRepository;
import com.daniel.infrastructure.persistence.repository.InvestmentTypeRepository;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository;
import com.daniel.infrastructure.persistence.repository.SnapshotRepository;
import com.daniel.infrastructure.persistence.repository.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.daniel.infrastructure.persistence.backup.BackupTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * BRK-31 u3a: proof of coverage of MAPA-ESCRITAS-BRK31 (D-Q2). Every write boundary W1..W8 is called in each state
 * of the measure: below the write limit it is refused before any SQL and the database is unchanged; with room, and
 * without a measure, the gate lets it through (the neutral arguments may then fail in SQL, never in the gate), and
 * the unmeasured state leaves the warning. A source scan fails when a file that writes is not in the map.
 */
class WriteBoundariesTest {

    private static final long MIB = 1L << 20;

    enum State {
        REFUSED(new SpaceProbe.Measured(MIB, Path.of("."), "VOL")),
        ALLOWED(new SpaceProbe.Measured(1L << 30, Path.of("."), "VOL")),
        UNMEASURED(new SpaceProbe.Unavailable("teste"));

        final SpaceProbe.Space space;

        State(SpaceProbe.Space space) {
            this.space = space;
        }
    }

    @FunctionalInterface
    interface Write {
        void run() throws Exception;
    }

    record Boundary(String id, Write write) {
    }

    @TempDir
    Path tempDir;
    final AtomicReference<SpaceProbe.Space> space = new AtomicReference<>();
    final AtomicLong clock = new AtomicLong();
    StorageGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        Path db = Files.createDirectories(tempDir.resolve("dados")).resolve("breakinv.db");
        Database.configure(url(db));
        Database.open();
        seed("a");
        guard = new StorageGuard(db, p -> space.get(), StorageLimits::defaults,
                () -> clock.addAndGet(StorageGuard.CACHE_NANOS)); // never reuses a measure here
        Database.setWriteGate(guard.writeGate());
    }

    @AfterEach
    void tearDown() {
        Database.setWriteGate(null);
        Database.close();
    }

    static Object neutral(Class<?> t) {
        if (t == long.class) return 0L;
        if (t == int.class) return 0;
        if (t == boolean.class) return false;
        if (t == double.class) return 0d;
        if (t == String.class) return "brk31";
        if (t == LocalDate.class) return LocalDate.of(2026, 1, 2);
        return null;
    }

    static Write reflective(Object target, String name, int arity) {
        Method m = Arrays.stream(target.getClass().getMethods())
                .filter(x -> x.getName().equals(name) && x.getParameterCount() == arity)
                .findFirst().orElseThrow(() -> new AssertionError("sem " + name + "/" + arity));
        Object[] args = Arrays.stream(m.getParameterTypes()).map(WriteBoundariesTest::neutral).toArray();
        return () -> {
            try {
                m.invoke(target, args);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof Exception ex) throw ex;
                throw e;
            }
        };
    }

    List<Boundary> boundaries() {
        List<Boundary> b = new ArrayList<>();
        b.add(new Boundary("W1 inTransaction", () -> Database.inTransaction(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO app_settings(key, value) VALUES ('w1', 'x')");
            }
            return null;
        })));
        b.add(new Boundary("W2 ledger com conexão fixa", () -> new DatabaseLedgerStore(Database.open()).transact(repo -> {
            repo.setNote(1L, "w2");
            return null;
        })));
        Object settings = new AppSettingsRepository();
        Object flows = new FlowRepository();
        Object types = new InvestmentTypeRepository();
        Object txs = new TransactionRepository();
        Object snaps = new SnapshotRepository();
        Object saved = SavedAnalysisRepository.forApplicationDatabase();
        Object[][] table = {
                {"W3", settings, "set", 2}, {"W3", settings, "delete", 1},
                {"W4", flows, "create", 1}, {"W4", flows, "delete", 1},
                {"W5", types, "save", 1}, {"W5", types, "createFull", 12}, {"W5", types, "updateFull", 13},
                {"W5", types, "rename", 2}, {"W5", types, "delete", 1},
                {"W6", txs, "insert", 1},
                {"W7", snaps, "upsertCash", 2}, {"W7", snaps, "upsertInvestment", 4},
                {"W8", saved, "savePlan", 1}, {"W8", saved, "savePlanAndSelect", 1}, {"W8", saved, "deletePlan", 1},
                {"W8", saved, "selectPlan", 1}, {"W8", saved, "saveScenario", 1}, {"W8", saved, "deleteScenario", 1},
        };
        for (Object[] row : table) {
            String id = row[0] + " " + row[1].getClass().getSimpleName() + "." + row[2];
            b.add(new Boundary(id, reflective(row[1], (String) row[2], (Integer) row[3])));
        }
        return b;
    }

    @TestFactory
    Stream<DynamicTest> everyBoundary_inEveryStateOfTheMeasure() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Boundary boundary : boundaries()) {
            for (State state : State.values()) {
                tests.add(DynamicTest.dynamicTest(boundary.id() + " · " + state, () -> check(boundary, state)));
            }
        }
        assertEquals(20 * State.values().length, tests.size(), "W1, W2 e as 18 fronteiras dos repositórios");
        return tests.stream();
    }

    void check(Boundary boundary, State state) throws Exception {
        space.set(state.space);
        DatabaseFingerprint before = liveFingerprint();
        Exception thrown = null;
        try {
            boundary.write().run();
        } catch (Exception e) {
            thrown = e;
        }
        if (state == State.REFUSED) {
            assertInstanceOf(StorageRefusedException.class, thrown, boundary.id());
            assertTrue(thrown.getMessage().contains("Nada foi gravado"), thrown.getMessage());
            assertEquals(before, liveFingerprint(), "nada gravado: " + boundary.id());
        } else {
            assertFalse(thrown instanceof StorageRefusedException, boundary.id() + ": o portão não recusa");
            assertEquals(state == State.UNMEASURED, guard.lastWarning().isPresent(), boundary.id());
        }
    }

    @Test
    void aRealWrite_goesThrough_withRoom_andWithoutAMeasure() {
        AppSettingsRepository settings = new AppSettingsRepository();
        space.set(State.ALLOWED.space);
        settings.set("brk31", "com espaço");
        assertEquals("com espaço", settings.get("brk31").orElseThrow());

        space.set(State.UNMEASURED.space);
        settings.set("brk31", "sem medida");
        assertEquals("sem medida", settings.get("brk31").orElseThrow());
        assertTrue(guard.lastWarning().orElseThrow().contains("espaço livre não medido"));
    }

    /** Files whose source runs SQL that writes; must match MAPA-ESCRITAS-BRK31. */
    static final Set<String> MAPPED = Set.of("AppSettingsRepository.java", "FlowRepository.java",
            "InvestmentTypeRepository.java", "TransactionRepository.java", "SnapshotRepository.java",
            "SavedAnalysisRepository.java", "JdbcPortfolioRepository.java", "SchemaMigrator.java", "BackupService.java");

    static final Pattern WRITES = Pattern.compile("executeUpdate\\(|executeBatch\\("
            + "|\\.execute\\(\"\\s*(INSERT|UPDATE|DELETE|REPLACE|DROP|ALTER|CREATE|VACUUM)"
            + "|\"(INSERT|UPDATE|DELETE FROM|REPLACE|VACUUM)");

    static Map<String, String> sources() throws IOException {
        Map<String, String> m = new TreeMap<>();
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                m.put(p.getFileName().toString(), Files.readString(p));
            }
        }
        return m;
    }

    @Test
    void everyFileThatWrites_isInTheMap() throws IOException {
        Set<String> found = new TreeSet<>();
        sources().forEach((name, src) -> {
            if (WRITES.matcher(src).find()) found.add(name);
        });
        assertEquals(new TreeSet<>(MAPPED), found, "atualize o MAPA-ESCRITAS-BRK31 e as guardas");
    }

    @Test
    void everyMappedBoundary_checksBeforeWriting() throws IOException {
        Map<String, String> src = sources();
        Map<String, Integer> expected = Map.of("AppSettingsRepository.java", 2, "FlowRepository.java", 2,
                "InvestmentTypeRepository.java", 5, "TransactionRepository.java", 1, "SnapshotRepository.java", 2,
                "SavedAnalysisRepository.java", 5, "DatabaseLedgerStore.java", 1, "Database.java", 2);
        expected.forEach((file, n) -> assertEquals(n, count(src.get(file), "checkWrite()"), file));
    }

    static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
        return n;
    }
}
