package com.mola.starweave.sdk;

import com.google.gson.JsonObject;
import org.junit.Test;
import java.io.*;
import java.nio.file.*;
import static org.junit.Assert.*;

public class WireTest {
    @Test public void unicodeAndMultipleFramesRoundTrip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); JsonObject first = Wire.frame("acp", "one"); first.addProperty("text", "中文\nline");
        Wire.write(bytes, first); Wire.write(bytes, Wire.frame("ping", null));
        ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray()); assertEquals(first, Wire.read(input)); assertEquals("ping", Wire.str(Wire.read(input), "type"));
    }
    @Test public void rejectsOversizeBeforeAllocatingBody() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); new DataOutputStream(bytes).writeInt(Wire.MAX_FRAME + 1);
        try { Wire.read(new ByteArrayInputStream(bytes.toByteArray())); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("length")); }
    }
    @Test public void rejectsInvalidNamesBeforeStartingThreads() throws Exception {
        try { StarcoreClient.start("http://localhost:1", ".."); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void preventsTwoConnectorsSharingIdentityDirectory() throws Exception {
        Path data = Files.createTempDirectory("starcore-lock");
        try (StarcoreClient one = StarcoreClient.builder().starweaveUrl("http://127.0.0.1:1").name("one").dataDirectory(data).start()) {
            try { StarcoreClient.builder().starweaveUrl("http://127.0.0.1:1").name("one").dataDirectory(data).start(); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("already in use")); }
        }
    }
}
