package com.daniel.core.domain.entity.Enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IndexTypeEnumTest {

    // ===== CDI =====

    @Test
    void cdi_displayName() {
        assertEquals("CDI", IndexTypeEnum.CDI.getDisplayName());
    }

    @Test
    void cdi_hasNoFixedRate() {
        assertFalse(IndexTypeEnum.CDI.hasFixedRate());
        assertNull(IndexTypeEnum.CDI.getFixedRate());
    }

    @Test
    void cdi_isEditable() {
        assertTrue(IndexTypeEnum.CDI.isEditable());
    }

    @Test
    void cdi_rateDescription_containsVariavel() {
        String desc = IndexTypeEnum.CDI.getRateDescription();
        assertTrue(desc.contains("variável"), "Expected 'variável' in: " + desc);
        assertTrue(desc.contains("CDI"), "Expected 'CDI' in: " + desc);
    }

    // ===== SELIC =====

    @Test
    void selic_displayName() {
        assertEquals("Selic", IndexTypeEnum.SELIC.getDisplayName());
    }

    @Test
    void selic_hasFixedRate() {
        assertTrue(IndexTypeEnum.SELIC.hasFixedRate());
        assertNotNull(IndexTypeEnum.SELIC.getFixedRate());
    }

    @Test
    void selic_fixedRate_isCorrectValue() {
        assertEquals(0.15, IndexTypeEnum.SELIC.getFixedRate(), 0.0001);
    }

    @Test
    void selic_isNotEditable() {
        assertFalse(IndexTypeEnum.SELIC.isEditable());
    }

    @Test
    void selic_rateDescription_containsRate() {
        String desc = IndexTypeEnum.SELIC.getRateDescription();
        assertTrue(desc.contains("Selic"), "Expected 'Selic' in: " + desc);
        // Fixed rate 0.15 → formatted as "15.00" (but locale may vary)
        assertFalse(desc.contains("variável"),
                "SELIC has a fixed rate, should not say 'variável': " + desc);
    }

    // ===== IPCA =====

    @Test
    void ipca_displayName() {
        assertEquals("IPCA", IndexTypeEnum.IPCA.getDisplayName());
    }

    @Test
    void ipca_hasNoFixedRate() {
        assertFalse(IndexTypeEnum.IPCA.hasFixedRate());
        assertNull(IndexTypeEnum.IPCA.getFixedRate());
    }

    @Test
    void ipca_isEditable() {
        assertTrue(IndexTypeEnum.IPCA.isEditable());
    }

    @Test
    void ipca_rateDescription_containsVariavel() {
        String desc = IndexTypeEnum.IPCA.getRateDescription();
        assertTrue(desc.contains("variável"), "Expected 'variável' in: " + desc);
        assertTrue(desc.contains("IPCA"), "Expected 'IPCA' in: " + desc);
    }

    // ===== Cross-enum properties =====

    @Test
    void exactlyThreeValues() {
        assertEquals(3, IndexTypeEnum.values().length);
    }

    @Test
    void editableCount_twoEditable() {
        long editableCount = java.util.Arrays.stream(IndexTypeEnum.values())
                .filter(IndexTypeEnum::isEditable)
                .count();
        assertEquals(2, editableCount); // CDI + IPCA
    }

    @Test
    void fixedRateCount_oneFixed() {
        long fixedCount = java.util.Arrays.stream(IndexTypeEnum.values())
                .filter(IndexTypeEnum::hasFixedRate)
                .count();
        assertEquals(1, fixedCount); // only SELIC
    }

    @Test
    void valueOf_returnsCorrectConstant() {
        assertSame(IndexTypeEnum.CDI, IndexTypeEnum.valueOf("CDI"));
        assertSame(IndexTypeEnum.SELIC, IndexTypeEnum.valueOf("SELIC"));
        assertSame(IndexTypeEnum.IPCA, IndexTypeEnum.valueOf("IPCA"));
    }

    @Test
    void valueOf_unknownName_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> IndexTypeEnum.valueOf("UNKNOWN"));
    }
}
