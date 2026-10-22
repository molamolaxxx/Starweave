package com.mola.starweave.sdk.sync;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.stream.Stream;

/** Caller serializes exchanges for one stable resource set, including reconnecting peers. */
public final class SessionAuthority {
    private final Path root;
    public SessionAuthority(Path root) { this.root = root; }
    public JsonObject exchange(JsonObject request) throws IOException {
        String id = SessionSnapshots.id(ResourceFiles.str(request, "sessionId"));
        Path file = SessionSnapshots.file(root, id); byte[] current = SessionSnapshots.read(file);
        String head = ResourceFiles.digest(current); JsonObject response = new JsonObject(); response.addProperty("sessionId", id);
        if (request.has("data")) {
            byte[] incoming = SessionSnapshots.decode(request); SessionSnapshots.validate(id, incoming);
            String wanted = ResourceFiles.hash(incoming);
            if (!head.equals(ResourceFiles.str(request, "base")) && !head.equals(wanted)) {
                Path copy = ResourceFiles.resolve(root.resolve("conflicts"), id + "/" + wanted + ".json");
                if (!Files.exists(copy)) { checkQuota(incoming.length); SessionSnapshots.write(copy, incoming); }
                response.addProperty("error", "会话快照版本冲突，已保留中心和远端副本"); response.addProperty("conflict", true);
                response.addProperty("hash", head); return response;
            }
            if (!head.equals(wanted)) {
                checkQuota(incoming.length - (current == null ? 0 : current.length)); SessionSnapshots.write(file, incoming);
                current = incoming; head = wanted;
            }
        }
        response.addProperty("hash", head);
        // Upload acknowledgements never download into a live runtime's files.
        if (request.has("pull") && request.get("pull").getAsBoolean() && current != null
                && !head.equals(ResourceFiles.str(request, "local"))) SessionSnapshots.content(response, current);
        return response;
    }
    private void checkQuota(long extra) throws IOException {
        long total = 0; int count = 0;
        if (Files.exists(root)) try (Stream<Path> paths = Files.walk(root)) {
            java.util.Iterator<Path> files = paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).iterator();
            while (files.hasNext()) { total += Files.size(files.next()); if (++count > 10000) throw new IOException("会话备份文件数量超过上限"); }
        }
        if (total + extra > 512L * 1024 * 1024) throw new IOException("会话备份总量超过 512 MiB");
    }
}
