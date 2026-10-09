package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.AutoNewSessionConfig;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class AcpClientSleepLifecycleTest {
    @Test
    public void idleClientReleasesRuntimeAndWakesInPlace() throws Exception {
        FakeClient client = new FakeClient();
        RecordingListener listener = new RecordingListener();
        client.setGlobalListener(listener);
        client.readyAt(1_000L);
        client.setSessionId("session-old");

        assertTrue(client.sleepIfIdle(61_000L, 60_000L));
        assertEquals(AbstractAcpClient.State.SLEEP, client.getState());
        assertEquals(1, client.releases);

        assertTrue(client.wakeIfSleeping());
        assertEquals(AbstractAcpClient.State.READY, client.getState());
        assertEquals(1, client.wakes);
        assertEquals("AGENT_WAKE:SLEEP:READY:false", listener.lifecycleEvent);
        assertFalse(client.wakeIfSleeping());
    }

    @Test
    public void sleepingClientRotatesExpiredSessionWithoutBackgroundCheck() throws Exception {
        FakeClient client = new FakeClient();
        RecordingListener listener = new RecordingListener();
        client.setGlobalListener(listener);
        client.readyAt(1_000L);
        assertTrue(client.sleepIfIdle(61_000L, 60_000L));
        client.autoNewSession(true, System.currentTimeMillis() - 120_000L);
        assertEquals(AbstractAcpClient.State.SLEEP, client.getState());
        assertTrue(client.wakeIfSleeping());
        assertEquals("AGENT_WAKE:SLEEP:READY:true", listener.lifecycleEvent);
    }

    @Test
    public void wakeRotationNotifiesSessionRotationListenerWithOldAndNewSession()
            throws Exception {
        FakeClient client = new FakeClient();
        client.readyAt(1_000L);
        client.setSessionId("session-old");
        java.util.List<String> rotations = new java.util.ArrayList<>();
        client.setSessionRotationListener((previous, current) ->
                rotations.add(previous + "->" + current));
        assertTrue(client.sleepIfIdle(61_000L, 60_000L));
        client.autoNewSession(true, System.currentTimeMillis() - 120_000L);
        assertTrue(client.wakeIfSleeping());
        assertEquals(java.util.Collections.singletonList("session-old->session-1"), rotations);
    }

    @Test
    public void restartSleepIsRestoredImmediatelyAndWakeUpdatesPersistence() throws Exception {
        FakeClient client = new FakeClient();
        java.util.List<String> states = new java.util.ArrayList<>();
        client.setSleepStateListener(new AcpClient.SleepStateListener() {
            @Override public void onSleeping() { states.add("sleep"); }
            @Override public void onWaking() { states.add("wake"); }
        });
        client.readyAt(System.currentTimeMillis());

        assertTrue(client.restoreSleepAfterRestart());
        assertEquals(AbstractAcpClient.State.SLEEP, client.getState());
        assertEquals(1, client.releases);
        assertEquals(java.util.Collections.singletonList("sleep"), states);

        assertTrue(client.wakeIfSleeping());
        assertEquals(java.util.Arrays.asList("sleep", "wake"), states);
    }

    @Test
    public void wakeFallbackNotifiesActualRotationWithAutoNewSessionDisabled() throws Exception {
        FakeClient client = new FakeClient();
        RecordingListener listener = new RecordingListener();
        client.setGlobalListener(listener);
        client.setSessionId("session-old");
        client.readyAt(System.currentTimeMillis());
        client.autoNewSession(false, 0L);
        client.fallbackToNew = true;
        java.util.List<String> rotations = new java.util.ArrayList<>();
        client.setSessionRotationListener((previous, current) ->
                rotations.add(previous + "->" + current));
        assertTrue(client.restoreSleepAfterRestart());

        assertTrue(client.wakeIfSleeping());
        assertFalse(client.createdNew);
        assertEquals(Collections.singletonList("session-old->session-1"), rotations);
        assertEquals("AGENT_WAKE:SLEEP:READY:true", listener.lifecycleEvent);
    }

    @Test
    public void restoredSleepRotatesExpiredSessionWithoutSavedMarker() throws Exception {
        FakeClient client = new FakeClient();
        client.autoNewSession(true, System.currentTimeMillis() - 120_000L);
        client.readyAt(System.currentTimeMillis());
        client.setSessionId("session-old");
        assertTrue(client.restoreSleepAfterRestart());
        assertTrue(client.wakeIfSleeping());
        assertTrue(client.createdNew);
        assertEquals("session-1", client.getSessionId());
        assertEquals(0L, client.getLastMessageAt());
    }

    @Test
    public void wakeKeepsSessionWhenDisabledUnconfiguredEmptyRecentOrFutureDated() throws Exception {
        long now = System.currentTimeMillis();
        assertWakeKeepsSession(false, now - 120_000L);
        assertWakeKeepsSession(true, 0L);
        assertWakeKeepsSession(true, now);
        assertWakeKeepsSession(true, now + 120_000L);
        FakeClient unconfigured = new FakeClient();
        unconfigured.readyAt(now);
        unconfigured.setSessionId("session-old");
        assertTrue(unconfigured.restoreSleepAfterRestart());
        assertTrue(unconfigured.wakeIfSleeping());
        assertFalse(unconfigured.createdNew);
        assertEquals("session-old", unconfigured.getSessionId());
    }

    private void assertWakeKeepsSession(boolean enabled, long lastMessage) throws Exception {
        FakeClient client = new FakeClient();
        RecordingListener listener = new RecordingListener();
        client.setGlobalListener(listener);
        java.util.List<String> rotations = new java.util.ArrayList<>();
        client.setSessionRotationListener((previous, current) ->
                rotations.add(previous + "->" + current));
        client.autoNewSession(enabled, lastMessage);
        client.readyAt(System.currentTimeMillis());
        client.setSessionId("session-old");
        assertTrue(client.restoreSleepAfterRestart());
        assertTrue(client.wakeIfSleeping());
        assertFalse(client.createdNew);
        assertEquals("session-old", client.getSessionId());
        assertEquals(lastMessage, client.getLastMessageAt());
        assertTrue(rotations.isEmpty());
        assertEquals("AGENT_WAKE:SLEEP:READY:false", listener.lifecycleEvent);
    }

    @Test
    public void failedWakePreservesIdleTimeAndRetriesRotation() throws Exception {
        FakeClient client = new FakeClient();
        long lastMessage = System.currentTimeMillis() - 120_000L;
        client.autoNewSession(true, lastMessage);
        client.readyAt(System.currentTimeMillis());
        client.setSessionId("session-old");
        assertTrue(client.restoreSleepAfterRestart());
        client.failWake = true;
        try {
            client.wakeIfSleeping();
            fail("expected wake failure");
        } catch (IOException expected) {
            assertEquals("planned wake failure", expected.getMessage());
        }
        assertEquals(AbstractAcpClient.State.SLEEP, client.getState());
        assertEquals(lastMessage, client.getLastMessageAt());
        client.failWake = false;
        assertTrue(client.wakeIfSleeping());
        assertTrue(client.createdNew);
    }

    private static final class RecordingListener implements AcpResponseListener {
        private String lifecycleEvent;

        @Override public void onMessage(String text) { }
        @Override public void onToolCall(String toolCallId, String title, String status,
                                         com.google.gson.JsonObject update) { }
        @Override public void onComplete(String fullResponse) { }
        @Override public void onError(Exception error) { }

        @Override
        public void onLifecycleEvent(String eventType, String fromState, String toState,
                                     long durationMillis, boolean newSession) {
            lifecycleEvent = eventType + ":" + fromState + ":" + toState + ":" + newSession;
        }
    }

    private static final class FakeClient extends AcpClient {
        private int releases;
        private int wakes;
        private boolean createdNew;
        private boolean failWake;
        private boolean fallbackToNew;

        private FakeClient() {
            super(new FakeProvider(), "/tmp", "sleep-test", new AcpRobotParam());
        }

        private void readyAt(long timestamp) {
            state.set(State.READY);
            markActivityAt(timestamp);
        }

        private void autoNewSession(boolean enabled, long lastMessage) throws Exception {
            AutoNewSessionConfig config = new AutoNewSessionConfig();
            config.setEnabled(enabled);
            config.setIdleMinutes(1);
            config.setCheckIntervalMinutes(360);
            getRobotParam().setAutoNewSession(config);
            java.lang.reflect.Field field = AcpClient.class.getDeclaredField("lastMessageAt");
            field.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicLong) field.get(this)).set(lastMessage);
        }

        @Override protected void releaseRuntimeForSleep() { releases++; }

        @Override protected void wakeRuntimeSession() throws IOException {
            try {
                java.lang.reflect.Field field = AcpClient.class.getDeclaredField("forceNewSession");
                field.setAccessible(true);
                createdNew = field.getBoolean(this);
            } catch (ReflectiveOperationException error) {
                throw new IOException(error);
            }
            if (failWake) throw new IOException("planned wake failure");
            if (!state.compareAndSet(State.SLEEP, State.READY)) {
                throw new IOException("unexpected state");
            }
            wakes++;
            if (createdNew || fallbackToNew || getSessionId() == null) {
                setSessionId("session-" + wakes);
            }
        }
    }

    private static final class FakeProvider implements AgentProvider {
        @Override public String getName() { return "fake"; }
        @Override public String getCommand() { return "fake"; }
        @Override public String[] getArgs() { return new String[0]; }
        @Override public List<Path> getMcpConfigPaths(String workspacePath) {
            return Collections.emptyList();
        }
    }
}
