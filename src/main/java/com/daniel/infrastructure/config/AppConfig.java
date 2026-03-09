package com.daniel.infrastructure.config;

import com.daniel.core.domain.repository.IFlowRepository;
import com.daniel.core.domain.repository.IInvestmentTypeRepository;
import com.daniel.core.domain.repository.ISnapshotRepository;
import com.daniel.core.domain.repository.IStockPriceProvider;
import com.daniel.core.domain.repository.ITransactionRepository;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.infrastructure.api.BrapiStockPriceProvider;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.repository.FlowRepository;
import com.daniel.infrastructure.persistence.repository.InvestmentTypeRepository;
import com.daniel.infrastructure.persistence.repository.SnapshotRepository;
import com.daniel.infrastructure.persistence.repository.TransactionRepository;

import java.sql.Connection;

public class AppConfig {

    private final Connection connection;
    private final DailyTrackingUseCase dailyTrackingUseCase;

    public AppConfig() {
        this.connection = Database.open();

        IFlowRepository flowRepo = new FlowRepository();
        IInvestmentTypeRepository invRepo = new InvestmentTypeRepository();
        ISnapshotRepository snapRepo = new SnapshotRepository();
        ITransactionRepository txRepo = new TransactionRepository();
        IStockPriceProvider priceProvider = new BrapiStockPriceProvider();

        this.dailyTrackingUseCase = new DailyTrackingUseCase(flowRepo, invRepo, snapRepo, txRepo, priceProvider);
    }

    public DailyTrackingUseCase getDailyTrackingUseCase() {
        return dailyTrackingUseCase;
    }

    public Connection getConnection() {
        return connection;
    }
}