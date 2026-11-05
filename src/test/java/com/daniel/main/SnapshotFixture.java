package com.daniel.main;

import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.repository.AppSettingsRepository;
import com.daniel.infrastructure.persistence.repository.DatabaseLedgerAccess;
import com.daniel.infrastructure.persistence.repository.FlowRepository;
import com.daniel.infrastructure.persistence.repository.InvestmentTypeRepository;
import com.daniel.infrastructure.persistence.repository.SnapshotRepository;
import com.daniel.infrastructure.persistence.repository.TransactionRepository;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Tests only (U4C:RF-04 and RF-05 item 4): the production wiring of the use case on a throwaway file, with one
 * investment opened today and no snapshot taken yet, and the write gate that refuses with a fixed text.
 */
public final class SnapshotFixture {

    /** The fixed refusal of the gate (RF-05 item 4). */
    public static final String REFUSAL = "limite de gravação atingido.";

    private SnapshotFixture() {
    }

    /**
     * Configures {@code Database} on a file in {@code dir}, creates one investment with a purchase (so today's
     * snapshot has something to write) and returns the use case. Offline: automatic price update off.
     */
    public static DailyTrackingUseCase useCaseWithOneInvestment(Path dir) {
        Database.configure("jdbc:sqlite:" + dir.resolve("registro.db").toAbsolutePath().toString().replace("\\", "/"));
        Database.open();
        new AppSettingsRepository().set("brapi_auto_update", "false");
        DailyTrackingUseCase uc = new DailyTrackingUseCase(new FlowRepository(), new InvestmentTypeRepository(),
                new SnapshotRepository(), new TransactionRepository(), ticker -> 12.5, new DatabaseLedgerAccess());
        LocalDate today = LocalDate.now();
        uc.movement(MovementRequest.deposit("dep", today, 10_000_000L, null));
        long id = uc.createInvestment(new DailyTrackingUseCase.NewInvestment("ATIVO 01", "ACOES", "ALTA", today, null,
                        null, "ACAO", null, null, "T1", null, null, null),
                DailyTrackingUseCase.OpeningSpec.zero("open-1", today, PositionMode.QUANTITY));
        uc.movement(MovementRequest.buy("buy-1", today, id, 10, new BigDecimal("10.00"), null));
        return uc;
    }

    /** Turned on after the operation that triggers the snapshot, as RF-05 item 4 asks. */
    public static void refuseWrites() {
        Database.setWriteGate(() -> REFUSAL);
    }

    public static String expectedNotice() {
        return "Não foi possível registrar o valor da carteira de hoje (limite de gravação atingido). "
                + "O BreakInv tenta de novo na próxima alteração da carteira ou na próxima abertura de hoje.";
    }
}
