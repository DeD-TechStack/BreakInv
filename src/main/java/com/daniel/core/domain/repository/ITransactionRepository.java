package com.daniel.core.domain.repository;

import com.daniel.core.domain.entity.Transaction;

import java.time.LocalDate;
import java.util.List;

public interface ITransactionRepository {
    long insert(Transaction transaction);
    List<Transaction> listBetween(LocalDate start, LocalDate end);
}
