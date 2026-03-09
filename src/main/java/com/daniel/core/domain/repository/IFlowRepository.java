package com.daniel.core.domain.repository;

import com.daniel.core.domain.entity.Flow;

import java.time.LocalDate;
import java.util.List;

public interface IFlowRepository {
    List<Flow> listForDate (LocalDate date);
    void save(Flow flow);
    void delete(long id);
    long create(Flow flow);
}
