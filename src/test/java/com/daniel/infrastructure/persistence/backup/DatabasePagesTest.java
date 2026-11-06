package com.daniel.infrastructure.persistence.backup;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/** BRK-32 S2:RF-01: the three numbers of the pages, read on a read-only connection, against the PRAGMAs read directly. */
class DatabasePagesTest extends SpaceReleaseTestBase {

    @Test
    void theThreeNumbers_equalTheDirectPragmas() throws Exception {
        long[] direct = pragmas(db);

        DatabasePages.Reading reading = new DatabasePages(db).read();

        assertEquals(direct[0], reading.pageSize(), "page_size");
        assertEquals(direct[1], reading.pageCount(), "page_count");
        assertEquals(direct[2], reading.freelistCount(), "freelist_count");
        assertEquals(reading.freelistCount() * reading.pageSize(), reading.reusableBytes(), "reusable = livres × página");
    }

    @Test
    void afterDeletingThroughTheProduct_theReusableSpaceIsAboveZero_andStillEqualsThePragmas() throws Exception {
        long freeBefore = new DatabasePages(db).read().freelistCount();

        fragment(12, 9, 64 * 1024);
        long[] direct = pragmas(db);
        DatabasePages.Reading reading = new DatabasePages(db).read();

        assertTrue(reading.freelistCount() > freeBefore, "deleted plans left free pages");
        assertTrue(reading.reusableBytes() > 0);
        assertEquals(direct[0], reading.pageSize());
        assertEquals(direct[1], reading.pageCount());
        assertEquals(direct[2], reading.freelistCount());
        assertTrue(reading.freelistCount() < reading.pageCount(), "the freelist is a part of the pages");
        assertEquals(Files.size(db), reading.pageCount() * reading.pageSize(), "the file is page_count pages");
    }

    @Test
    void reading_changesNothing() throws Exception {
        fragment(6, 4, 32 * 1024);
        long counterBefore = counter();
        long sizeBefore = Files.size(db);

        new DatabasePages(db).read();

        assertEquals(counterBefore, counter(), "a reading writes nothing");
        assertEquals(sizeBefore, Files.size(db));
    }

    @Test
    void aReadingError_comesUpAsAnException() {
        DatabasePages missing = new DatabasePages(tempDir.resolve("nao-existe").resolve("breakinv.db"));

        assertThrows(SQLException.class, missing::read);
    }
}
