package com.daniel.infrastructure.config;

import com.daniel.core.domain.repository.IFlowRepository;
import com.daniel.core.domain.repository.IInvestmentTypeRepository;
import com.daniel.core.domain.repository.ISnapshotRepository;
import com.daniel.core.domain.repository.IStockPriceProvider;
import com.daniel.core.domain.repository.ITransactionRepository;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.infrastructure.api.BrapiStockPriceProvider;
import com.daniel.infrastructure.persistence.backup.BackupService;
import com.daniel.infrastructure.persistence.backup.DataProtection;
import com.daniel.infrastructure.persistence.backup.FileInstanceLock;
import com.daniel.infrastructure.persistence.backup.InstanceGuard;
import com.daniel.infrastructure.persistence.backup.RestoreService;
import com.daniel.infrastructure.persistence.backup.RetentionPolicy;
import com.daniel.infrastructure.persistence.backup.StorageGuard;
import com.daniel.infrastructure.persistence.backup.StoragePreferences;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.repository.DatabaseLedgerAccess;
import com.daniel.infrastructure.persistence.repository.FlowRepository;
import com.daniel.infrastructure.persistence.repository.InvestmentTypeRepository;
import com.daniel.infrastructure.persistence.repository.SnapshotRepository;
import com.daniel.infrastructure.persistence.repository.TransactionRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AppConfig implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(AppConfig.class.getName());

    private final Connection connection;
    private final DailyTrackingUseCase dailyTrackingUseCase;
    private final FileInstanceLock instanceLock;
    private final BackupService backupService;
    private final DataProtection dataProtection;
    private final StorageGuard storageGuard;

    public AppConfig() {
        Path dbFile = Database.currentFile()
                .orElseThrow(() -> new IllegalStateException("o banco de dados precisa de um arquivo"));
        // BRK-30: every open window holds the data folder; a restore waits until it is the only one
        this.instanceLock = holdInstanceLock(dbFile);
        this.backupService = new BackupService(dbFile, BackupService.defaultFolder(dbFile),
                BackupService.SourceAccess.opening(Database::newConnection), Clock.systemDefaultZone(),
                RetentionPolicy.defaults());
        InstanceGuard guard = instanceLock != null ? instanceLock : InstanceGuard.UNVERIFIABLE;
        // temps of an interrupted copy are removed only when no other window could be writing them
        if (guard.tryAcquireExclusive()) {
            try {
                backupService.cleanupInterrupted();
            } finally {
                guard.releaseExclusive();
            }
        }
        // an existing database is copied and verified before any migration changes it
        Database.setPreMigrationHook((candidate, file) -> backupService.beforeMigration(candidate));
        // BRK-31: below the write limit on the data volume, user writes are refused before any SQL
        this.storageGuard = StorageGuard.forDatabase(dbFile, StoragePreferences.forUser());
        Database.setWriteGate(storageGuard.writeGate());
        backupService.spaceCheck(storageGuard::operation);
        this.connection = Database.open();

        IFlowRepository flowRepo = new FlowRepository();
        IInvestmentTypeRepository invRepo = new InvestmentTypeRepository();
        ISnapshotRepository snapRepo = new SnapshotRepository();
        ITransactionRepository txRepo = new TransactionRepository();
        IStockPriceProvider priceProvider = new BrapiStockPriceProvider();

        // Production wiring: every movement goes through the ledger (portfolio_movement).
        this.dailyTrackingUseCase = new DailyTrackingUseCase(flowRepo, invRepo, snapRepo, txRepo, priceProvider,
                new DatabaseLedgerAccess());
        this.dataProtection = DataProtection.of(backupService, new RestoreService(backupService, guard));
    }

    private static FileInstanceLock holdInstanceLock(Path dbFile) {
        try {
            return FileInstanceLock.holdShared(dbFile.resolveSibling(FileInstanceLock.FILE_NAME));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "instance lock unavailable; restoring will be refused", e);
            return null;
        }
    }

    /** First opening of the day: one verified copy when the data changed (background executor). */
    public void runDailyBackup() {
        BackupService.Result r = backupService.dailyIfNeeded();
        if (r.outcome() == BackupService.Outcome.FAILED) LOG.warning("daily copy failed: " + r.message());
    }

    public DailyTrackingUseCase getDailyTrackingUseCase() {
        return dailyTrackingUseCase;
    }

    public DataProtection getDataProtection() {
        return dataProtection;
    }

    public BackupService getBackupService() {
        return backupService;
    }

    public Connection getConnection() {
        return connection;
    }

    @Override
    public void close() {
        if (instanceLock == null) return;
        try {
            instanceLock.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "instance lock close", e);
        }
    }
}
