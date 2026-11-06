package com.daniel.presentation.view.pages.storage;

import com.daniel.presentation.view.pages.storage.StoragePresenter.SpaceState;
import org.junit.jupiter.api.Test;

import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.critical;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.dbUnknown;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.dbWarning;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.facts;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.normal;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.reserve;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.unmeasured;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** BRK-31 u5 F2, RF-02 and RF-08 case 1: the state of the section and its priority. */
class StoragePresenterStateTest {

    @Test
    void theFiveExampleRowsOfTheDesign() {
        assertEquals(SpaceState.NORMAL, StoragePresenter.state(normal()));
        assertEquals(SpaceState.RESERVE, StoragePresenter.state(reserve()));
        assertEquals(SpaceState.CRITICAL, StoragePresenter.state(critical()));
        assertEquals(SpaceState.DB_WARNING, StoragePresenter.state(dbWarning()));
        assertEquals(SpaceState.UNMEASURED, StoragePresenter.state(unmeasured()));
    }

    @Test
    void theEnumHasTheSixStatesInPriorityOrder() {
        assertEquals("[UNMEASURED, CRITICAL, DB_UNKNOWN, RESERVE, DB_WARNING, NORMAL]",
                java.util.Arrays.toString(SpaceState.values()));
    }

    @Test
    void priorityWithEverySignalOnAtTheSameTime() {
        // every signal on: the undetermined write limit wins
        StorageFacts all = facts(b -> {
            b.writeLimitUndetermined = true;
            b.writeLimitActive = true;
            b.warnUndetermined = true;
            b.reserveUndetermined = true;
            b.reserveActive = true;
            b.warnActive = true;
        });
        assertEquals(SpaceState.UNMEASURED, StoragePresenter.state(all));

        // the write limit is determined: the active write limit wins over everything below it
        StorageFacts determined = facts(b -> {
            b.writeLimitActive = true;
            b.warnUndetermined = true;
            b.reserveUndetermined = true;
            b.reserveActive = true;
            b.warnActive = true;
        });
        assertEquals(SpaceState.CRITICAL, StoragePresenter.state(determined));

        // no write limit: an unknown size of the data wins over the reserve and the warn
        StorageFacts unknown = facts(b -> {
            b.warnUndetermined = true;
            b.reserveUndetermined = true;
            b.reserveActive = true;
            b.warnActive = true;
        });
        assertEquals(SpaceState.DB_UNKNOWN, StoragePresenter.state(unknown));

        // the size is known: the reserve wins over the warn
        StorageFacts known = facts(b -> {
            b.reserveActive = true;
            b.warnActive = true;
        });
        assertEquals(SpaceState.RESERVE, StoragePresenter.state(known));
    }

    @Test
    void anActiveWriteLimitBeatsAnActiveReserve() {
        StorageFacts both = facts(b -> {
            b.reserveActive = true;
            b.writeLimitActive = true;
        });
        assertEquals(SpaceState.CRITICAL, StoragePresenter.state(both));
    }

    @Test
    void reserveAndDbWarningTogetherAreTheReserve() {
        StorageFacts both = facts(b -> {
            b.reserveActive = true;
            b.warnActive = true;
        });
        assertEquals(SpaceState.RESERVE, StoragePresenter.state(both));
        assertEquals(SpaceState.DB_WARNING, StoragePresenter.state(facts(b -> b.warnActive = true)));
    }

    @Test
    void anUnknownSizeOfTheDataIsDbUnknownWhicheverOfTheTwoIsUndetermined() {
        assertEquals(SpaceState.DB_UNKNOWN, StoragePresenter.state(dbUnknown()));
        assertEquals(SpaceState.DB_UNKNOWN, StoragePresenter.state(facts(b -> b.reserveUndetermined = true)));
        assertEquals(SpaceState.DB_UNKNOWN, StoragePresenter.state(facts(b -> b.warnUndetermined = true)));
    }

    @Test
    void anUndeterminedWriteLimitIsUnmeasuredEvenWhenOnlyItIsOn() {
        assertEquals(SpaceState.UNMEASURED, StoragePresenter.state(facts(b -> b.writeLimitUndetermined = true)));
    }
}
