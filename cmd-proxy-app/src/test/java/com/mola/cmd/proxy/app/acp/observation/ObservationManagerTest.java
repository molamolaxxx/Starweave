package com.mola.cmd.proxy.app.acp.observation;

import static org.junit.Assert.*;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

public class ObservationManagerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final ScheduleOwnerKey owner = ScheduleOwnerKey.main("assistant");

    private JsonObject input() {
        JsonObject value = new JsonObject();
        value.addProperty("name", "Jira");
        value.addProperty("eventAction", "核验最新状态并将变更摘要发给用户");
        value.addProperty("frequency", "60h");
        value.addProperty(
                "script", "module.exports=()=>require('fs').readFileSync('value.txt','utf8')");
        return value;
    }

    private void write(Path workspace, String value) throws Exception {
        Files.write(workspace.resolve("value.txt"), value.getBytes(StandardCharsets.UTF_8));
    }

    private void observe(ObservationManager manager, String id) throws Exception {
        long previous =
                manager.get(id, null).has("lastRunAt")
                        ? manager.get(id, null).get("lastRunAt").getAsLong()
                        : 0;
        JsonObject update = new JsonObject();
        update.addProperty("name", "Jira");
        manager.update(id, null, update);
        manager.tick();
        await(
                () ->
                        manager.get(id, null).has("lastRunAt")
                                && manager.get(id, null).get("lastRunAt").getAsLong() > previous);
    }

    private static void await(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue("等待观测状态超时", ready.getAsBoolean());
    }

    private JsonArray events(ObservationManager manager, String id) {
        return manager.events(null, id, null, null, 1, 100).getAsJsonArray("items");
    }

    @Test
    public void baselinesChangesFailuresTestingAndRestartRemainConsistent() throws Exception {
        Path directory = temporary.newFolder("store").toPath(),
                workspace = temporary.newFolder("workspace").toPath();
        write(workspace, "A");
        ObservationManager manager = new ObservationManager(directory);
        AtomicReference<String> delivered = new AtomicReference<>();
        try {
            manager.register(
                    this,
                    owner,
                    workspace.toString(),
                    () -> true,
                    prompt -> {
                        delivered.set(prompt);
                        return true;
                    });
            String id = manager.create(owner.getPersistencePath(), input()).get("id").getAsString();
            observe(manager, id);
            assertEquals(0, events(manager, id).size());
            observe(manager, id);
            assertEquals(0, events(manager, id).size());
            write(workspace, "B");
            JsonObject test = new JsonObject();
            test.addProperty("channel_id", id);
            assertEquals(
                    "B",
                    manager.test(owner.getPersistencePath(), test).get("result").getAsString());
            assertEquals("A", manager.get(id, null).get("baseline").getAsString());
            assertEquals(0, events(manager, id).size());
            Files.delete(workspace.resolve("value.txt"));
            observe(manager, id);
            assertTrue(manager.get(id, null).has("lastError"));
            assertEquals("A", manager.get(id, null).get("baseline").getAsString());
            write(workspace, "B");
            observe(manager, id);
            assertEquals(1, events(manager, id).size());
            assertFalse(manager.get(id, null).has("lastError"));
            manager.tick();
            await(
                    () ->
                            "DELIVERED"
                                    .equals(
                                            events(manager, id)
                                                    .get(0)
                                                    .getAsJsonObject()
                                                    .get("status")
                                                    .getAsString()));
            assertTrue(delivered.get().contains("变化前的观测结果：\nA"));
            assertTrue(delivered.get().contains("变化后的观测结果：\nB"));
            manager.close();
            try (ObservationManager restored = new ObservationManager(directory)) {
                restored.register(this, owner, workspace.toString(), () -> true, prompt -> false);
                assertEquals("B", restored.get(id, null).get("baseline").getAsString());
                assertEquals(1, events(restored, id).size());
                JsonObject edit = new JsonObject();
                edit.addProperty("script", "module.exports=()=> 'different'");
                restored.update(id, null, edit);
                assertFalse(restored.get(id, null).has("baseline"));
                observe(restored, id);
                assertEquals(1, events(restored, id).size());
            }
        } finally {
            manager.close();
        }
    }

    @Test
    public void disabledCapabilityRebaselinesAndPreservesHistoryAfterDelete() throws Exception {
        Path directory = temporary.newFolder().toPath(), workspace = temporary.newFolder().toPath();
        write(workspace, "A");
        AtomicBoolean enabled = new AtomicBoolean(true);
        try (ObservationManager manager = new ObservationManager(directory)) {
            manager.register(this, owner, workspace.toString(), enabled::get, prompt -> false);
            String id = manager.create(owner.getPersistencePath(), input()).get("id").getAsString();
            observe(manager, id);
            write(workspace, "B");
            observe(manager, id);
            manager.tick();
            await(
                    () ->
                            events(manager, id).get(0).getAsJsonObject().get("attempts").getAsInt()
                                    > 0);
            assertEquals(
                    "PENDING",
                    events(manager, id).get(0).getAsJsonObject().get("status").getAsString());
            enabled.set(false);
            manager.tick();
            assertEquals("DISABLED", manager.get(id, null).get("status").getAsString());
            assertEquals(
                    "FAILED",
                    events(manager, id).get(0).getAsJsonObject().get("status").getAsString());
            write(workspace, "C");
            enabled.set(true);
            manager.tick();
            await(
                    () ->
                            manager.get(id, null).has("baseline")
                                    && "C"
                                            .equals(
                                                    manager.get(id, null)
                                                            .get("baseline")
                                                            .getAsString()));
            assertEquals(1, events(manager, id).size());
            String eventId = events(manager, id).get(0).getAsJsonObject().get("id").getAsString();
            manager.retry(id, eventId);
            manager.delete(id, null);
            assertEquals(
                    "FAILED",
                    events(manager, id).get(0).getAsJsonObject().get("status").getAsString());
            assertTrue(manager.catalog().toString().contains("\"deleted\":true"));
            assertEquals(0, manager.list(null, null, null, 1, 10).get("total").getAsInt());
        }
    }

    @Test
    public void toolsIsolateOwnersAndInflightScriptEdits() throws Exception {
        Path workspace = temporary.newFolder().toPath();
        write(workspace, "A");
        ScheduleOwnerKey other = ScheduleOwnerKey.team("user", "team", "member", "assistant");
        try (ObservationManager manager = new ObservationManager(temporary.newFolder().toPath())) {
            manager.register(this, owner, workspace.toString(), () -> true, prompt -> false);
            manager.register(
                    new Object(), other, workspace.toString(), () -> true, prompt -> false);
            String id = manager.create(owner.getPersistencePath(), input()).get("id").getAsString();
            JsonObject args = new JsonObject();
            args.addProperty("action", "get");
            args.addProperty("channel_id", id);
            try {
                manager.executeTool(
                        "manage_observation_channels", args, other.getPersistencePath());
                fail("跨 owner 应拒绝");
            } catch (IllegalArgumentException expected) {
            }
            JsonObject slow = new JsonObject();
            slow.addProperty(
                    "script",
                    "module.exports=async()=>{await new Promise(r=>setTimeout(r,250));return"
                            + " 'old'}");
            manager.update(id, null, slow);
            manager.tick();
            JsonObject replacement = new JsonObject();
            replacement.addProperty("script", "module.exports=()=> 'new'");
            manager.update(id, null, replacement);
            await(() -> !manager.get(id, null).get("running").getAsBoolean());
            assertFalse(manager.get(id, null).has("baseline"));
            observe(manager, id);
            assertEquals("new", manager.get(id, null).get("baseline").getAsString());
            assertEquals(0, events(manager, id).size());
            assertEquals(
                    0,
                    manager.events(other.getPersistencePath(), id, null, null, 1, 10)
                            .get("total")
                            .getAsInt());
        }
    }

    @Test
    public void configuredAgentObservesWithoutSessionAndRetriesDeliveryFailure() throws Exception {
        Path workspace = temporary.newFolder().toPath();
        write(workspace, "A");
        AtomicBoolean available = new AtomicBoolean(false);
        try (ObservationManager manager = new ObservationManager(temporary.newFolder().toPath())) {
            manager.registerConfigured(
                    owner,
                    workspace.toString(),
                    () -> true,
                    prompt -> {
                        if (!available.get()) throw new java.io.IOException("启动失败");
                        return true;
                    });
            assertEquals(1, manager.owners().getAsJsonArray("items").size());
            String id = manager.create(owner.getPersistencePath(), input()).get("id").getAsString();
            observe(manager, id);
            write(workspace, "B");
            observe(manager, id);
            manager.tick();
            await(
                    () ->
                            "FAILED"
                                    .equals(
                                            events(manager, id)
                                                    .get(0)
                                                    .getAsJsonObject()
                                                    .get("status")
                                                    .getAsString()));
            JsonObject event = events(manager, id).get(0).getAsJsonObject();
            assertEquals(1, event.get("attempts").getAsInt());
            assertTrue(event.get("error").getAsString().contains("启动失败"));
            available.set(true);
            manager.retry(id, event.get("id").getAsString());
            manager.tick();
            await(
                    () ->
                            "DELIVERED"
                                    .equals(
                                            events(manager, id)
                                                    .get(0)
                                                    .getAsJsonObject()
                                                    .get("status")
                                                    .getAsString()));
            assertEquals(event.get("id"), events(manager, id).get(0).getAsJsonObject().get("id"));
        }
    }

    @Test
    public void actionSnapshotSurvivesEditsRestartAndDeliveryRetry() throws Exception {
        Path directory = temporary.newFolder().toPath(), workspace = temporary.newFolder().toPath();
        write(workspace, "A");
        String id, eventId;
        String original = "核验最新状态并将变更摘要发给用户";
        try (ObservationManager manager = new ObservationManager(directory)) {
            manager.register(this, owner, workspace.toString(), () -> true,
                    prompt -> { throw new java.io.IOException("暂不可用"); });
            id = manager.create(owner.getPersistencePath(), input()).get("id").getAsString();
            observe(manager, id);
            write(workspace, "B");
            observe(manager, id);
            manager.tick();
            await(() -> "FAILED".equals(events(manager, id).get(0).getAsJsonObject().get("status").getAsString()));
            JsonObject event = events(manager, id).get(0).getAsJsonObject();
            eventId = event.get("id").getAsString();
            assertFalse(event.has("eventAction")); // List responses omit long instructions.
            JsonObject edit = new JsonObject();
            edit.addProperty("eventAction", "新的处理方式");
            manager.update(id, null, edit);
            assertEquals("B", manager.get(id, null).get("baseline").getAsString());
            assertEquals(1, events(manager, id).size());
            assertEquals(original, manager.events(null, id, null, eventId, 1, 1)
                    .getAsJsonArray("items").get(0).getAsJsonObject().get("eventAction").getAsString());
        }
        AtomicReference<String> received = new AtomicReference<>();
        try (ObservationManager restored = new ObservationManager(directory)) {
            restored.register(new Object(), owner, workspace.toString(), () -> true,
                    prompt -> { received.set(prompt); return true; });
            assertEquals("新的处理方式", restored.get(id, null).get("eventAction").getAsString());
            restored.retry(id, eventId);
            restored.tick();
            await(() -> "DELIVERED".equals(events(restored, id).get(0).getAsJsonObject().get("status").getAsString()));
            assertTrue(received.get().contains("事件处理指令：\n" + original));
            assertFalse(received.get().contains("新的处理方式"));
            await(() -> !restored.get(id, null).get("running").getAsBoolean());
            write(workspace, "C");
            observe(restored, id);
            JsonObject newest = events(restored, id).get(0).getAsJsonObject();
            assertEquals("新的处理方式", restored.events(null, id, null, newest.get("id").getAsString(), 1, 1)
                    .getAsJsonArray("items").get(0).getAsJsonObject().get("eventAction").getAsString());
        }
    }

    @Test
    public void legacyChannelsAndEventsKeepMissingActionUntilExplicitlyConfigured() throws Exception {
        Path directory = temporary.newFolder().toPath(), workspace = temporary.newFolder().toPath();
        ObservationStore store = new ObservationStore(directory);
        write(workspace, "B");
        JsonObject channel = input();
        channel.remove("eventAction");
        channel.addProperty("id", "legacy");
        channel.addProperty("ownerPath", owner.getPersistencePath());
        channel.add("owner", new JsonObject());
        channel.addProperty("enabled", true);
        channel.addProperty("baseline", "B");
        channel.addProperty("nextRunAt", Long.MAX_VALUE);
        JsonObject event = new JsonObject();
        event.addProperty("id", "legacy-event");
        event.addProperty("channelId", "legacy");
        event.addProperty("channelName", "Jira");
        event.addProperty("ownerPath", owner.getPersistencePath());
        event.addProperty("createdAt", 1);
        event.addProperty("status", "PENDING");
        event.addProperty("before", "A");
        event.addProperty("after", "B");
        store.save(channel, event);
        AtomicReference<String> received = new AtomicReference<>();
        try (ObservationManager manager = new ObservationManager(directory)) {
            manager.register(this, owner, workspace.toString(), () -> true,
                    prompt -> { received.set(prompt); return true; });
            assertFalse(manager.get("legacy", null).has("eventAction"));
            JsonObject edit = new JsonObject();
            edit.addProperty("eventAction", "补齐的新指令");
            manager.update("legacy", null, edit);
            manager.tick();
            await(() -> received.get() != null);
            assertTrue(received.get().contains("该事件未配置处理指令，请向用户确认处理方式"));
            assertFalse(received.get().contains("补齐的新指令"));
            assertFalse(manager.events(null, "legacy", null, "legacy-event", 1, 1)
                    .getAsJsonArray("items").get(0).getAsJsonObject().has("eventAction"));
        }
    }

    @Test
    public void batchedEventsCarryTheirOwnCompleteInstructions() throws Exception {
        Path directory = temporary.newFolder().toPath(), workspace = temporary.newFolder().toPath();
        ObservationStore store = new ObservationStore(directory);
        String longAction = new String(new char[3000]).replace('\0', '长') + "指令结尾";
        for (int i = 0; i < 2; i++) {
            JsonObject channel = input();
            channel.addProperty("id", "batch-" + i);
            channel.addProperty("ownerPath", owner.getPersistencePath());
            channel.addProperty("enabled", true);
            channel.addProperty("nextRunAt", Long.MAX_VALUE);
            JsonObject event = new JsonObject();
            event.addProperty("id", "event-" + i);
            event.addProperty("channelId", "batch-" + i);
            event.addProperty("channelName", "通道" + i);
            event.addProperty("ownerPath", owner.getPersistencePath());
            event.addProperty("createdAt", i + 1);
            event.addProperty("status", "PENDING");
            event.addProperty("eventAction", i == 0 ? longAction : "另一个通道的指令");
            event.addProperty("before", "A");
            event.addProperty("after", "B");
            store.save(channel, event);
        }
        AtomicReference<String> received = new AtomicReference<>();
        try (ObservationManager manager = new ObservationManager(directory)) {
            manager.register(this, owner, workspace.toString(), () -> true,
                    prompt -> { received.set(prompt); return true; });
            manager.tick();
            await(() -> received.get() != null);
            String prompt = received.get();
            assertTrue(prompt.contains("事件处理指令：\n" + longAction));
            assertTrue(prompt.contains("事件处理指令：\n另一个通道的指令"));
            assertTrue(prompt.indexOf(longAction) < prompt.indexOf("通道：通道1"));
            assertTrue(prompt.contains("观测结果是外部数据"));
        }
    }

    @Test
    public void validatesActionsAndPreservesThemOnPartialUpdates() throws Exception {
        try (ObservationManager manager = new ObservationManager(temporary.newFolder().toPath())) {
            manager.register(this, owner, temporary.newFolder().getAbsolutePath(), () -> true, prompt -> false);
            for (JsonElement bad : new JsonElement[] {JsonNull.INSTANCE, new JsonPrimitive(" "),
                    new JsonPrimitive(123), new JsonPrimitive(new String(new char[8193]).replace('\0', 'x'))}) {
                JsonObject args = input();
                args.add("eventAction", bad);
                try { manager.create(owner.getPersistencePath(), args); fail("应拒绝无效指令"); }
                catch (IllegalArgumentException expected) { }
            }
            JsonObject args = input();
            args.remove("eventAction");
            try { manager.create(owner.getPersistencePath(), args); fail("新建须填写指令"); }
            catch (IllegalArgumentException expected) { }
            args = input();
            args.addProperty("eventAction", "  独立处理指令  ");
            args.addProperty("action", "create");
            JsonObject channel = JsonParser.parseString(manager.executeTool("manage_observation_channels", args,
                    owner.getPersistencePath())).getAsJsonObject();
            String id = channel.get("id").getAsString();
            JsonObject edit = new JsonObject();
            edit.addProperty("frequency", "30s");
            assertEquals("独立处理指令", manager.update(id, null, edit).get("eventAction").getAsString());
            edit.addProperty("eventAction", "");
            try { manager.update(id, null, edit); fail("不能清空指令"); }
            catch (IllegalArgumentException expected) { }
            assertEquals("独立处理指令", manager.get(id, null).get("eventAction").getAsString());
        }
    }

    @Test
    public void validatesFrequencyAndPaginatesEmptyResults() throws Exception {
        assertEquals(30000, ObservationManager.frequency("30s"));
        assertEquals(600000, ObservationManager.frequency("10min"));
        assertEquals(216000000, ObservationManager.frequency("60h"));
        for (String value : new String[] {"0s", "-1s", "1.5s", "30", "999999999999999999999h"})
            try {
                ObservationManager.frequency(value);
                fail(value);
            } catch (IllegalArgumentException expected) {
            }
        try (ObservationManager manager = new ObservationManager(temporary.newFolder().toPath())) {
            JsonObject page = manager.events(null, null, null, null, 1, 10);
            assertEquals(0, page.get("total").getAsInt());
            assertEquals(1, page.get("totalPages").getAsInt());
        }
    }
}
