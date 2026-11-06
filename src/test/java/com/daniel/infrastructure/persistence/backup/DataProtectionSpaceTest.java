package com.daniel.infrastructure.persistence.backup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BRK-32 S2:RF-04: the two new methods of {@link DataProtection}. The overloads of two and four arguments keep their
 * shape and throw; the overload of five arguments answers with the real pages and the real service.
 */
class DataProtectionSpaceTest extends SpaceReleaseTestBase {

    private StorageReport reportOn(SpaceProbe probe, StoragePreferences preferences) {
        return new StorageReport(db, folder, sqliteTemp, probe, preferences, backups);
    }

    private DataProtection withFive(SpaceRelease release) {
        StoragePreferences preferences = new StoragePreferences(new MemoryPreferences());
        return DataProtection.of(backups, new RestoreService(backups, InstanceGuard.UNVERIFIABLE),
                reportOn(FakeSpaceProbe.all(1L << 40), preferences), preferences, release);
    }

    @Test
    void theOfOfTwoArguments_throwsUnsupported_onTheTwoNewMethods() {
        DataProtection old = DataProtection.of(backups, new RestoreService(backups, InstanceGuard.UNVERIFIABLE));

        assertNotNull(old.status(), "o resto continua como era");
        assertThrows(UnsupportedOperationException.class, old::pages);
        assertThrows(UnsupportedOperationException.class, old::releaseSpace);
    }

    @Test
    void theOfOfFourArguments_throwsUnsupported_onTheTwoNewMethods() {
        StoragePreferences preferences = new StoragePreferences(new MemoryPreferences());
        DataProtection four = DataProtection.of(backups, new RestoreService(backups, InstanceGuard.UNVERIFIABLE),
                reportOn(FakeSpaceProbe.all(1L << 40), preferences), preferences);

        assertDoesNotThrow(four::storage, "a leitura do espaço da u5 continua ligada");
        assertThrows(UnsupportedOperationException.class, four::pages);
        assertThrows(UnsupportedOperationException.class, four::releaseSpace);
    }

    @Test
    void theOfOfFiveArguments_answersPages_withTheCurrentData() throws Exception {
        fragment(8, 6, 32 * 1024);
        DataProtection protection = withFive(new SpaceRelease(backups, InstanceGuard.SOLE));

        DatabasePages.Reading reading = protection.pages();

        assertEquals(new DatabasePages(db).read(), reading);
        assertTrue(reading.freelistCount() > 0);
    }

    @Test
    void theOfOfFiveArguments_answersReleaseSpace_withTheResultOfTheGivenService() {
        SpaceRelease service = new SpaceRelease(backups, InstanceGuard.SOLE,
                () -> new DatabasePages.Reading(4096, 100, 7), conn -> { }, BackupVerifier::verify,
                (conn, copy) -> { }, body -> {
                    throw new IllegalStateException("não deve chegar aqui");
                });
        DataProtection protection = withFive(service);

        SpaceRelease.Result viaProtection = protection.releaseSpace();

        assertEquals(SpaceRelease.Outcome.SEM_GANHO, viaProtection.outcome(), viaProtection.detail());
        assertEquals(7, viaProtection.freePagesBefore(), "o leitor injetado no serviço é o que respondeu");
        assertEquals(service.release(), viaProtection, "o mesmo serviço, o mesmo resultado");
    }

    @Test
    void theOfOfFiveArguments_keepsTheOtherMethodsWired() {
        DataProtection protection = withFive(new SpaceRelease(backups, InstanceGuard.SOLE));

        assertNotNull(protection.status());
        assertDoesNotThrow(protection::storage);
    }

    @Test
    void releaseSpace_throughTheProduct_onADatabaseWithoutGain_isSemGanho() {
        DataProtection protection = withFive(new SpaceRelease(backups, InstanceGuard.SOLE));

        SpaceRelease.Result r = protection.releaseSpace();

        assertEquals(SpaceRelease.Outcome.SEM_GANHO, r.outcome(), r.detail());
    }
}
