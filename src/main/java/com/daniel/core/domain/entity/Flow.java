package com.daniel.core.domain.entity;

import com.daniel.core.domain.entity.Enums.FlowKind;

import java.time.LocalDate;

public record Flow(
        long id,
        LocalDate date,
        FlowKind fromKind, Long fromInvestmentTypeId,
        FlowKind toKind, Long toInvestmentTypeId,
        long amountCents,
        String note
) {}
