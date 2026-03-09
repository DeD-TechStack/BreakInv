package com.daniel.core.domain.entity.Enums;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class LiquidityEnumTest {

    // ===== Display names =====

    @Test
    void muitoAlta_displayName_containsDPlus0() {
        assertTrue(LiquidityEnum.MUITO_ALTA.getDisplayName().contains("D+0"),
                "MUITO_ALTA should mention D+0: " + LiquidityEnum.MUITO_ALTA.getDisplayName());
    }

    @Test
    void alta_displayName_containsDPlus1() {
        String name = LiquidityEnum.ALTA.getDisplayName();
        assertTrue(name.contains("D+1") || name.contains("D+2"),
                "ALTA should mention D+1/D+2: " + name);
    }

    @Test
    void media_displayName_containsDPlus3() {
        String name = LiquidityEnum.MEDIA.getDisplayName();
        assertTrue(name.contains("D+3") || name.contains("D+30"),
                "MEDIA should mention D+3 range: " + name);
    }

    @Test
    void baixa_displayName_containsDPlus30() {
        String name = LiquidityEnum.BAIXA.getDisplayName();
        assertTrue(name.contains("D+30") || name.contains("D+90"),
                "BAIXA should mention D+30/D+90: " + name);
    }

    @Test
    void muitoBaixa_displayName_containsD90() {
        String name = LiquidityEnum.MUITO_BAIXA.getDisplayName();
        assertTrue(name.contains("D+90") || name.contains(">D+90"),
                "MUITO_BAIXA should mention >D+90: " + name);
    }

    // ===== Colors =====

    @Test
    void allColors_startWithHash() {
        for (LiquidityEnum liq : LiquidityEnum.values()) {
            assertTrue(liq.getColor().startsWith("#"),
                    liq.name() + " color does not start with '#': " + liq.getColor());
        }
    }

    @Test
    void allColors_validHexLength() {
        for (LiquidityEnum liq : LiquidityEnum.values()) {
            assertEquals(7, liq.getColor().length(),
                    liq.name() + " color has unexpected length: " + liq.getColor());
        }
    }

    @Test
    void muitoAlta_color_isGreen() {
        assertEquals("#22c55e", LiquidityEnum.MUITO_ALTA.getColor());
    }

    @Test
    void muitoBaixa_color_isRed() {
        assertEquals("#ef4444", LiquidityEnum.MUITO_BAIXA.getColor());
    }

    // ===== Enum completeness =====

    @Test
    void exactlyFiveValues() {
        assertEquals(5, LiquidityEnum.values().length);
    }

    @Test
    void allExpectedValuesPresent() {
        var names = Arrays.stream(LiquidityEnum.values())
                .map(Enum::name)
                .toList();
        assertTrue(names.contains("MUITO_ALTA"));
        assertTrue(names.contains("ALTA"));
        assertTrue(names.contains("MEDIA"));
        assertTrue(names.contains("BAIXA"));
        assertTrue(names.contains("MUITO_BAIXA"));
    }

    @Test
    void valueOf_knownName_returnsConstant() {
        assertSame(LiquidityEnum.MUITO_ALTA, LiquidityEnum.valueOf("MUITO_ALTA"));
        assertSame(LiquidityEnum.MUITO_BAIXA, LiquidityEnum.valueOf("MUITO_BAIXA"));
    }

    @Test
    void valueOf_unknownName_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> LiquidityEnum.valueOf("NENHUMA"));
    }

    @Test
    void allDisplayNames_nonBlank() {
        for (LiquidityEnum liq : LiquidityEnum.values()) {
            assertNotNull(liq.getDisplayName());
            assertFalse(liq.getDisplayName().isBlank(),
                    liq.name() + " displayName is blank");
        }
    }

    @Test
    void allColors_nonBlank() {
        for (LiquidityEnum liq : LiquidityEnum.values()) {
            assertNotNull(liq.getColor());
            assertFalse(liq.getColor().isBlank(), liq.name() + " color is blank");
        }
    }
}
