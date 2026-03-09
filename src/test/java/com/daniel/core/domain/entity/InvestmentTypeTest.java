package com.daniel.core.domain.entity;

import com.daniel.core.domain.entity.Enums.InvestmentTypeEnum;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class InvestmentTypeTest {

    // ===== Constructor validation =====

    @Test
    void nullName_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new InvestmentType(1, null));
    }

    @Test
    void blankName_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new InvestmentType(1, "  "));
    }

    @Test
    void emptyName_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new InvestmentType(1, ""));
    }

    @Test
    void validName_createsSuccessfully() {
        InvestmentType inv = new InvestmentType(1, "Tesouro Selic");
        assertEquals(1, inv.id());
        assertEquals("Tesouro Selic", inv.name());
    }

    // ===== hasFullData =====

    @Test
    void hasFullData_allFieldsPresent_returnsTrue() {
        InvestmentType inv = new InvestmentType(
                1, "Tesouro", "RENDA_FIXA", "ALTA",
                LocalDate.of(2023, 1, 1),
                BigDecimal.valueOf(0.12),
                BigDecimal.valueOf(1000)
        );
        assertTrue(inv.hasFullData());
    }

    @Test
    void hasFullData_missingCategory_returnsFalse() {
        InvestmentType inv = new InvestmentType(
                1, "Tesouro", null, "ALTA",
                LocalDate.of(2023, 1, 1),
                BigDecimal.valueOf(0.12),
                BigDecimal.valueOf(1000)
        );
        assertFalse(inv.hasFullData());
    }

    @Test
    void hasFullData_missingLiquidity_returnsFalse() {
        InvestmentType inv = new InvestmentType(
                1, "Tesouro", "RENDA_FIXA", null,
                LocalDate.of(2023, 1, 1),
                BigDecimal.valueOf(0.12),
                BigDecimal.valueOf(1000)
        );
        assertFalse(inv.hasFullData());
    }

    @Test
    void hasFullData_missingDate_returnsFalse() {
        InvestmentType inv = new InvestmentType(
                1, "Tesouro", "RENDA_FIXA", "ALTA",
                null,
                BigDecimal.valueOf(0.12),
                BigDecimal.valueOf(1000)
        );
        assertFalse(inv.hasFullData());
    }

    @Test
    void hasFullData_missingProfitability_returnsFalse() {
        InvestmentType inv = new InvestmentType(
                1, "Tesouro", "RENDA_FIXA", "ALTA",
                LocalDate.of(2023, 1, 1),
                null,
                BigDecimal.valueOf(1000)
        );
        assertFalse(inv.hasFullData());
    }

    @Test
    void hasFullData_missingInvestedValue_returnsFalse() {
        InvestmentType inv = new InvestmentType(
                1, "Tesouro", "RENDA_FIXA", "ALTA",
                LocalDate.of(2023, 1, 1),
                BigDecimal.valueOf(0.12),
                null
        );
        assertFalse(inv.hasFullData());
    }

    // ===== getInvestmentTypeEnum =====

    @Test
    void getInvestmentTypeEnum_nullTypeField_returnsNull() {
        InvestmentType inv = new InvestmentType(1, "Test");
        assertNull(inv.getInvestmentTypeEnum());
    }

    @Test
    void getInvestmentTypeEnum_acao_returnsCorrectEnum() {
        InvestmentType inv = new InvestmentType(
                1, "PETR4", "ACOES", "MUITO_ALTA",
                LocalDate.now(), BigDecimal.valueOf(0.05), BigDecimal.valueOf(5000),
                "ACAO", null, null, "PETR4", BigDecimal.valueOf(30.0), 100, null
        );
        assertEquals(InvestmentTypeEnum.ACAO, inv.getInvestmentTypeEnum());
    }

    @Test
    void getInvestmentTypeEnum_prefixado_returnsCorrectEnum() {
        InvestmentType inv = new InvestmentType(
                1, "CDB Prefixado", "RENDA_FIXA", "MEDIA",
                LocalDate.now(), BigDecimal.valueOf(0.12), BigDecimal.valueOf(10000),
                "PREFIXADO", null, null, null, null, null, null
        );
        assertEquals(InvestmentTypeEnum.PREFIXADO, inv.getInvestmentTypeEnum());
    }

    @Test
    void getInvestmentTypeEnum_invalidValue_returnsNull() {
        InvestmentType inv = new InvestmentType(
                1, "Weird", "RENDA_FIXA", "ALTA",
                LocalDate.now(), BigDecimal.valueOf(0.10), BigDecimal.valueOf(1000),
                "INVALID_TYPE", null, null, null, null, null, null
        );
        assertNull(inv.getInvestmentTypeEnum());
    }

    // ===== hasInvestmentTypeData =====

    @Test
    void hasInvestmentTypeData_withType_returnsTrue() {
        InvestmentType inv = new InvestmentType(
                1, "CDB", "RENDA_FIXA", "ALTA",
                LocalDate.now(), BigDecimal.valueOf(0.12), BigDecimal.valueOf(1000),
                "PREFIXADO", null, null, null, null, null, null
        );
        assertTrue(inv.hasInvestmentTypeData());
    }

    @Test
    void hasInvestmentTypeData_withoutType_returnsFalse() {
        InvestmentType inv = new InvestmentType(1, "Test");
        assertFalse(inv.hasInvestmentTypeData());
    }

    // ===== Simple constructor compatibility =====

    @Test
    void simpleConstructor_setsNullsForOptionalFields() {
        InvestmentType inv = new InvestmentType(42, "Poupança");
        assertEquals(42, inv.id());
        assertEquals("Poupança", inv.name());
        assertNull(inv.category());
        assertNull(inv.liquidity());
        assertNull(inv.investmentDate());
        assertNull(inv.profitability());
        assertNull(inv.investedValue());
        assertNull(inv.ticker());
    }
}
