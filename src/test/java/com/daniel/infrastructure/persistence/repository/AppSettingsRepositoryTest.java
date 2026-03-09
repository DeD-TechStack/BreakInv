package com.daniel.infrastructure.persistence.repository;

import com.daniel.infrastructure.persistence.config.Database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AppSettingsRepository.
 *
 * Uses an isolated temp-file SQLite DB per test.
 * AppSettingsRepository was previously untested.
 */
class AppSettingsRepositoryTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        String dbPath = tempDir.resolve("test.db").toString().replace("\\", "/");
        Database.configure("jdbc:sqlite:" + dbPath);
        Database.open();
    }

    @AfterEach
    void tearDown() {
        Database.close();
    }

    // ===== get =====

    @Test
    void get_unknownKey_returnsEmpty() {
        AppSettingsRepository repo = new AppSettingsRepository();
        Optional<String> result = repo.get("nonexistent.key");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void get_afterSet_returnsValue() {
        AppSettingsRepository repo = new AppSettingsRepository();
        repo.set("theme", "dark");
        Optional<String> result = repo.get("theme");
        assertTrue(result.isPresent());
        assertEquals("dark", result.get());
    }

    // ===== set / upsert =====

    @Test
    void set_existingKey_overwrites() {
        AppSettingsRepository repo = new AppSettingsRepository();
        repo.set("api.token", "token-v1");
        repo.set("api.token", "token-v2");
        assertEquals("token-v2", repo.get("api.token").orElseThrow());
    }

    @Test
    void set_emptyStringValue_persists() {
        AppSettingsRepository repo = new AppSettingsRepository();
        repo.set("empty.pref", "");
        Optional<String> result = repo.get("empty.pref");
        assertTrue(result.isPresent());
        assertEquals("", result.get());
    }

    @Test
    void set_multipleKeys_storedIndependently() {
        AppSettingsRepository repo = new AppSettingsRepository();
        repo.set("key.a", "value-a");
        repo.set("key.b", "value-b");
        repo.set("key.c", "value-c");

        assertEquals("value-a", repo.get("key.a").orElseThrow());
        assertEquals("value-b", repo.get("key.b").orElseThrow());
        assertEquals("value-c", repo.get("key.c").orElseThrow());
    }

    // ===== delete =====

    @Test
    void delete_removesKey() {
        AppSettingsRepository repo = new AppSettingsRepository();
        repo.set("to.delete", "some-value");
        repo.delete("to.delete");
        assertTrue(repo.get("to.delete").isEmpty());
    }

    @Test
    void delete_nonExistentKey_doesNotThrow() {
        AppSettingsRepository repo = new AppSettingsRepository();
        assertDoesNotThrow(() -> repo.delete("does.not.exist"));
    }

    @Test
    void delete_leavesOtherKeysIntact() {
        AppSettingsRepository repo = new AppSettingsRepository();
        repo.set("keep.this", "important");
        repo.set("remove.this", "temporary");

        repo.delete("remove.this");

        assertTrue(repo.get("remove.this").isEmpty());
        assertEquals("important", repo.get("keep.this").orElseThrow());
    }
}
