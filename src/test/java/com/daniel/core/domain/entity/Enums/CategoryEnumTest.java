package com.daniel.core.domain.entity.Enums;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class CategoryEnumTest {

    // ===== Display names =====

    @Test
    void rendaFixa_displayName() {
        assertEquals("Renda Fixa", CategoryEnum.RENDA_FIXA.getDisplayName());
    }

    @Test
    void acoes_displayName() {
        assertEquals("Ações", CategoryEnum.ACOES.getDisplayName());
    }

    @Test
    void fundosImobiliarios_displayName() {
        assertEquals("Fundos Imobiliários", CategoryEnum.FUNDOS_IMOBILIARIOS.getDisplayName());
    }

    @Test
    void fundos_displayName() {
        assertEquals("Fundos de Investimento", CategoryEnum.FUNDOS.getDisplayName());
    }

    @Test
    void previdencia_displayName() {
        assertEquals("Previdência", CategoryEnum.PREVIDENCIA.getDisplayName());
    }

    @Test
    void criptomoedas_displayName() {
        assertEquals("Criptomoedas", CategoryEnum.CRIPTOMOEDAS.getDisplayName());
    }

    @Test
    void outros_displayName() {
        assertEquals("Outros", CategoryEnum.OUTROS.getDisplayName());
    }

    // ===== Colors — all must be valid hex strings =====

    @Test
    void allColors_startWithHash() {
        for (CategoryEnum cat : CategoryEnum.values()) {
            String color = cat.getColor();
            assertNotNull(color, cat.name() + " has null color");
            assertTrue(color.startsWith("#"),
                    cat.name() + " color does not start with '#': " + color);
        }
    }

    @Test
    void allColors_validHexLength() {
        for (CategoryEnum cat : CategoryEnum.values()) {
            String color = cat.getColor();
            // Expect 7-char hex (#RRGGBB)
            assertEquals(7, color.length(),
                    cat.name() + " color has unexpected length: " + color);
        }
    }

    @Test
    void allColors_validHexCharacters() {
        for (CategoryEnum cat : CategoryEnum.values()) {
            String hex = cat.getColor().substring(1);
            assertTrue(hex.matches("[0-9a-fA-F]+"),
                    cat.name() + " color has invalid hex chars: " + cat.getColor());
        }
    }

    // ===== Specific colors =====

    @Test
    void rendaFixa_color_isBlue() {
        assertEquals("#3b82f6", CategoryEnum.RENDA_FIXA.getColor());
    }

    @Test
    void acoes_color_isGreen() {
        assertEquals("#22c55e", CategoryEnum.ACOES.getColor());
    }

    @Test
    void criptomoedas_color_isPink() {
        assertEquals("#ec4899", CategoryEnum.CRIPTOMOEDAS.getColor());
    }

    // ===== Enum completeness =====

    @Test
    void exactlySevenValues() {
        assertEquals(7, CategoryEnum.values().length);
    }

    @Test
    void allExpectedValuesPresent() {
        var names = Arrays.stream(CategoryEnum.values())
                .map(Enum::name)
                .toList();
        assertTrue(names.contains("RENDA_FIXA"));
        assertTrue(names.contains("ACOES"));
        assertTrue(names.contains("FUNDOS_IMOBILIARIOS"));
        assertTrue(names.contains("FUNDOS"));
        assertTrue(names.contains("PREVIDENCIA"));
        assertTrue(names.contains("CRIPTOMOEDAS"));
        assertTrue(names.contains("OUTROS"));
    }

    @Test
    void valueOf_knownName_returnsConstant() {
        assertSame(CategoryEnum.RENDA_FIXA, CategoryEnum.valueOf("RENDA_FIXA"));
        assertSame(CategoryEnum.ACOES, CategoryEnum.valueOf("ACOES"));
    }

    @Test
    void valueOf_unknownName_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> CategoryEnum.valueOf("INVALID"));
    }

    @Test
    void allDisplayNames_nonBlank() {
        for (CategoryEnum cat : CategoryEnum.values()) {
            assertNotNull(cat.getDisplayName(), cat.name() + " displayName is null");
            assertFalse(cat.getDisplayName().isBlank(),
                    cat.name() + " displayName is blank");
        }
    }
}
