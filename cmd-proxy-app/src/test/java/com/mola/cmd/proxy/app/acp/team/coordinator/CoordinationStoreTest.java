package com.mola.cmd.proxy.app.acp.team.coordinator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class CoordinationStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void durableAdmissionIsBoundedAndDuplicateDoesNotConsumeAnotherSlot() throws Exception {
        CoordinationStore store = new CoordinationStore(temporary.newFolder().toPath());
        store.enqueue("inbox", "one", MixedTeamCoordinator.object("timestamp", 1), 1);
        store.enqueue("inbox", "one", MixedTeamCoordinator.object("timestamp", 2), 1);
        try { store.enqueue("inbox", "two", MixedTeamCoordinator.object("timestamp", 3), 1); fail(); }
        catch (CoordinationException full) { assertEquals("QUEUE_FULL", full.getCode()); }
        assertEquals(1L, store.find("inbox", "one").getLongValue("timestamp"));
        store.delete("inbox", "one");
        store.enqueue("inbox", "two", MixedTeamCoordinator.object("timestamp", 3), 1);
        assertEquals(1, store.list("inbox").size());
    }
    @Test public void rejectsPathTraversalInAreaAndRecordId() throws Exception {
        CoordinationStore store = new CoordinationStore(temporary.newFolder().toPath());
        for (String value : new String[]{"..", "../secret", "/tmp/outside", "a/b"}) {
            try { store.find("teams", value); fail(value); } catch (IllegalArgumentException expected) { }
            try { store.find(value, "team"); fail(value); } catch (IllegalArgumentException expected) { }
        }
    }
}
