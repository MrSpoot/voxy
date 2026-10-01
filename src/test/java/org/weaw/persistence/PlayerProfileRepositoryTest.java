package org.weaw.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PlayerProfileRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsAStableUuidAndAllowsAnExplicitDisplayNameUpdate() {
        PlayerProfileRepository repository = new PlayerProfileRepository(temporaryDirectory);

        PlayerProfile created = repository.openOrCreate("default", "Alice");
        PlayerProfile reopened = repository.openOrCreate("default", null);
        PlayerProfile renamed = repository.openOrCreate("default", "Alice_2");

        assertEquals(created.id(), reopened.id());
        assertEquals(created.id(), renamed.id());
        assertEquals("Alice_2", renamed.displayName());
        assertEquals(created.createdAtEpochMillis(), renamed.createdAtEpochMillis());
    }

    @Test
    void createsListsAndRenamesIndependentProfiles() {
        PlayerProfileRepository repository = new PlayerProfileRepository(temporaryDirectory);

        PlayerProfile alice = repository.create("Alice");
        PlayerProfile secondAlice = repository.create("Alice");
        PlayerProfile renamed = repository.rename(alice.key(), "Alice_2");

        assertEquals("alice", alice.key());
        assertEquals("alice-2", secondAlice.key());
        assertNotEquals(alice.id(), secondAlice.id());
        assertEquals(alice.id(), renamed.id());
        assertEquals(alice.key(), renamed.key());
        assertEquals("Alice_2", renamed.displayName());
        assertEquals(2, repository.listProfiles().size());
        assertEquals(renamed, repository.find(alice.key()).orElseThrow());
    }
}
