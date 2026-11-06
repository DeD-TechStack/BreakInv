package com.daniel.presentation.view.pages.storage;

import com.daniel.presentation.view.pages.storage.LimitInput.Invalid;
import com.daniel.presentation.view.pages.storage.LimitInput.Parsed;
import org.junit.jupiter.api.Test;

import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.mb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** BRK-31 u5 F2, RF-07 and RF-08 case 7: the text of a limit field (whole MB) to bytes and back. */
class LimitInputTest {

    private static void assertParsed(long bytes, String text) {
        LimitInput.Result result = LimitInput.parse(text);
        assertEquals(new Parsed(bytes), result, "\"" + text + "\"");
    }

    private static void assertInvalid(String text) {
        assertInstanceOf(Invalid.class, LimitInput.parse(text), "\"" + text + "\"");
    }

    @Test
    void wholeMegabytesBecomeMebibytes() {
        assertParsed(mb(64), "64");
        assertParsed(mb(64), " 64 ");
        assertParsed(0, "0");
    }

    @Test
    void leadingZerosAndTheBiggestRoundNumberAreStillNumbers() {
        assertParsed(mb(64), "064");
        assertParsed(1L << 40, "1048576"); // 1 TiB in MiB
    }

    @Test
    void anythingButDigitsIsInvalid() {
        assertInvalid("");
        assertInvalid("  ");
        assertInvalid("-1");
        assertInvalid("+64");
        assertInvalid("6.4");
        assertInvalid("6,4");
        assertInvalid("64 MB");
        assertInvalid("1 024");
        assertInvalid("1.024");
        assertInvalid("abc");
        assertInvalid("٦٤"); // Arabic-Indic digits are not [0-9]
        assertInvalid(null);
    }

    @Test
    void anOverflowIsTheBiggestLongSoTheRangeRefusesItWithItsOwnText() {
        assertParsed(Long.MAX_VALUE, "99999999999999999999");    // parseLong overflows
        assertParsed(Long.MAX_VALUE, "9223372036854775807");     // parses, multiplyExact overflows
        assertParsed(Long.MAX_VALUE, "8796093022208");           // 2^43 MiB = 2^63 bytes: first multiplyExact overflow
    }

    @Test
    void theLargestNumberThatStillFitsIsExact() {
        long largest = Long.MAX_VALUE / (1L << 20);
        assertParsed(largest * (1L << 20), Long.toString(largest));
    }

    @Test
    void formatIsPlainDigitsWithoutAThousandsSeparator() {
        assertEquals("64", LimitInput.format(mb(64)));
        assertEquals("1048576", LimitInput.format(1L << 40));
        assertEquals("0", LimitInput.format(0));
    }

    @Test
    void formatRoundsDownWhatIsNotAWholeMebibyte() {
        assertEquals("64", LimitInput.format(mb(64) + 1));
        assertEquals("63", LimitInput.format(mb(64) - 1));
    }

    @Test
    void formatThenParseGivesTheSameMultiple() {
        assertParsed(mb(100), LimitInput.format(mb(100)));
    }
}
