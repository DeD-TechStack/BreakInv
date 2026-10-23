package com.daniel.infrastructure.persistence.backup;

import com.daniel.core.domain.entity.AllocationPlan;
import com.daniel.core.domain.entity.Enums.CategoryEnum;
import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.repository.AppSettingsRepository;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import static com.daniel.infrastructure.persistence.backup.BackupTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * BRK-31 u3c (DESENHO-BRK31 §5.1, "o piso de 64 MiB é inicial"): the rollback journal of the routine transactions on
 * a copy of a heavy synthetic database. With {@code journal_mode=PERSIST} the journal file keeps the size of the
 * largest transaction so far, so its size after each step is the largest journal up to that step. Opt-in: runs only
 * with {@code -Dbrk31.fixture=<synthetic database>}; never the personal database.
 */
@EnabledIfSystemProperty(named = "brk31.fixture", matches = ".+")
class RoutineJournalMeasuredTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        Database.setPreMigrationHook(null);
        Database.close();
    }

    @Test
    void routineTransactions_journalFarBelowTheWriteLimitFloor() throws Exception {
        Path fixture = Path.of(System.getProperty("brk31.fixture"));
        Path db = Files.createDirectories(tempDir.resolve("dados")).resolve("breakinv.db");
        Files.copy(fixture, db);
        Database.setPreMigrationHook((candidate, file) -> { }); // a synthetic copy: migrate it first, apart
        Database.configure(url(db));
        Database.open();
        Database.close();
        Path journal = db.resolveSibling("breakinv.db-journal");
        Files.deleteIfExists(journal);
        Database.configure(url(db) + "?journal_mode=PERSIST");
        try (Statement st = Database.open().createStatement(); ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
            assertEquals("persist", rs.getString(1), "a medida só vale com o journal persistente");
        }
        long d = Files.size(db);
        DailyTrackingUseCase uc = useCase();
        LocalDate day = LocalDate.of(2026, 10, 23);

        step(journal, d, "deposito", () -> uc.movement(MovementRequest.deposit("brk31-dep", day, 100_000L, null)));
        int[] id = new int[1];
        step(journal, d, "novo investimento com abertura", () -> id[0] = uc.createInvestment(stock("BRK31 MEDIDA", "BRKM3"),
                DailyTrackingUseCase.OpeningSpec.zero("brk31-open", day, PositionMode.QUANTITY)));
        step(journal, d, "compra", () -> uc.movement(MovementRequest.buy("brk31-buy", day, id[0], 10,
                new BigDecimal("12.34"), null)));
        step(journal, d, "venda", () -> uc.movement(MovementRequest.sell("brk31-sell", day, id[0], 4,
                new BigDecimal("13"), "medida")));
        step(journal, d, "snapshot do dia", () -> uc.takeSnapshotIfNeeded(day));
        step(journal, d, "configuracao", () -> new AppSettingsRepository().set("brk31_medida", "1"));
        Map<CategoryEnum, Integer> w = new EnumMap<>(CategoryEnum.class);
        CategoryEnum[] cats = CategoryEnum.values();
        for (int i = 0; i < cats.length; i++) w.put(cats[i], i == 0 ? 10_000 : 0);
        step(journal, d, "plano de alocacao", () -> SavedAnalysisRepository.forApplicationDatabase()
                .savePlanAndSelect(AllocationPlan.create("BRK31 medida", w)));

        long largest = Files.size(journal);
        System.out.printf(Locale.ROOT, "MEDIDA maior_journal=%d d=%d piso=%d razao_piso=%.1f%n", largest, d,
                StorageLimits.defaults().writeLimitBytes(), (double) StorageLimits.defaults().writeLimitBytes() / largest);
        assertTrue(largest < StorageLimits.defaults().writeLimitBytes(), "maior journal de rotina abaixo do piso");
    }

    interface Step {
        void run() throws Exception;
    }

    static void step(Path journal, long d, String name, Step body) throws Exception {
        body.run();
        long size = Files.exists(journal) ? Files.size(journal) : 0;
        System.out.printf(Locale.ROOT, "MEDIDA passo=\"%s\" maior_journal_ate_aqui=%d d=%d%n", name, size, d);
    }
}
