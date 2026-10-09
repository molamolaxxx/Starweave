package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class StarweaveSessionEventStoreTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void boundsToolHistoryAndRealtimeWithoutChangingDurableOriginal() throws Exception {
        Path root = temporary.newFolder("tool-previews").toPath();
        StarweaveSessionEventStore store = new StarweaveSessionEventStore(64, root);
        String large = String.join("", java.util.Collections.nCopies(100000, "output"));
        JSONObject payload = new JSONObject(); payload.put("toolCallId", "large-tool");
        JSONObject update = new JSONObject(); update.put("rawOutput", large); payload.put("update", update);
        StarweaveSessionEvent event = store.append("group", "session", 1L, "TOOL_CALL_UPDATED", payload);
        assertTrue(event.toJson().toJSONString().length() < 20000);
        assertEquals(large, event.getPayload().getJSONObject("update").getString("rawOutput"));
        StarweaveSessionEventStore restarted = new StarweaveSessionEventStore(64, root);
        assertEquals(large, restarted.sessionSnapshot("group", "session").getEvents().get(0)
                .getPayload().getJSONObject("update").getString("rawOutput"));
        JSONObject page = restarted.historyPage("group", "session", null, 50);
        assertEquals(1, page.getJSONArray("events").size());
        assertTrue(page.toJSONString().length() < 20000);
    }

    @Test
    public void concurrentAppendsPublishOneMonotonicSequenceAndSurviveRestart()
            throws Exception {
        Path root = temporary.newFolder("events").toPath();
        StarweaveSessionEventStore store = new StarweaveSessionEventStore(512, root);
        int workers = 8;
        int perWorker = 40;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int worker = 0; worker < workers; worker++) {
            final int workerId = worker;
            futures.add(executor.submit(() -> {
                start.await();
                for (int item = 0; item < perWorker; item++) {
                    JSONObject payload = new JSONObject(true);
                    payload.put("worker", workerId);
                    payload.put("item", item);
                    store.append("group", "session", 3L, "DELTA", payload);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) future.get(15, TimeUnit.SECONDS);
        executor.shutdownNow();

        List<StarweaveSessionEvent> events = store.snapshot("group", "session", 0L);
        assertEquals(workers * perWorker, events.size());
        Set<Long> sequences = new HashSet<>();
        for (int index = 0; index < events.size(); index++) {
            assertEquals(index + 1L, events.get(index).getEventSeq());
            assertTrue(sequences.add(events.get(index).getEventSeq()));
        }

        StarweaveSessionEventStore restarted = new StarweaveSessionEventStore(512, root);
        StarweaveSessionEvent next = restarted.append(
                "group", "session", 3L, "TERMINAL", new JSONObject(true));
        assertEquals(workers * perWorker + 1L, next.getEventSeq());
        assertEquals(workers * perWorker + 1,
                restarted.sessionSnapshot("group", "session").getEvents().size());
    }

    @Test
    public void waiterWakesOnAppendAndGenerationFilterRejectsOldEvents()
            throws Exception {
        StarweaveSessionEventStore store = new StarweaveSessionEventStore(8);
        store.append("group", "session", 1L, "OLD", new JSONObject(true));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch enteringAwait = new CountDownLatch(1);
        Future<StarweaveSessionEventStore.ReadResult> waiting = executor.submit(() -> {
            enteringAwait.countDown();
            return store.await("group", "session", 0L, 2L, 5_000L);
        });

        assertTrue(enteringAwait.await(1, TimeUnit.SECONDS));
        store.append("group", "session", 2L, "CURRENT", new JSONObject(true));

        StarweaveSessionEventStore.ReadResult result = waiting.get(2, TimeUnit.SECONDS);
        executor.shutdownNow();
        assertEquals(1, result.getEvents().size());
        assertEquals("CURRENT", result.getEvents().get(0).getType());
        assertEquals(2L, result.getEvents().get(0).getGeneration());
    }

    @Test
    public void evictedMatchingEventsRequireResyncButOtherSessionsDoNot()
            throws Exception {
        Path root = temporary.newFolder("resync").toPath();
        StarweaveSessionEventStore store = new StarweaveSessionEventStore(2, root);
        store.append("group", "session-c", 1L, "C1", new JSONObject(true));
        store.append("group", "session-a", 1L, "A1", new JSONObject(true));
        store.append("group", "session-b", 1L, "B1", new JSONObject(true));
        store.append("group", "session-b", 1L, "B2", new JSONObject(true));

        assertTrue(store.read("group", "session-a", 1L, 1L).isResyncRequired());
        assertFalse(store.read("group", "session-c", 1L, 1L).isResyncRequired());
    }
}
