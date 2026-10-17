package com.mola.starweave.sdk.sync;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.stream.Stream;

/** Only explicitly managed roots are transferable. No links, traversal, hidden or executable metadata. */
public final class ResourceFiles {
    public static final int MAX_FILE = 512 * 1024, MAX_TOTAL = 8 * 1024 * 1024, MAX_FILES = 1000;
    private ResourceFiles() { }
    public static String hash(byte[] bytes) {
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder out = new StringBuilder(); for (byte b : digest) out.append(String.format("%02x", b)); return out.toString(); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static Path resolve(Path root, String relative) throws IOException {
        if (relative == null || relative.isEmpty() || relative.length() > 512 || relative.contains("\\") || relative.startsWith("/") || relative.contains(":") || relative.indexOf('\0') >= 0) throw new IOException("Invalid resource path");
        for (String part : relative.split("/", -1)) if (part.isEmpty() || part.startsWith(".") || part.chars().anyMatch(c -> c < 32)) throw new IOException("Invalid resource path");
        Path normalized = root.toAbsolutePath().normalize(); Path target = normalized.resolve(relative).normalize();
        if (!target.startsWith(normalized)) throw new IOException("Resource escaped managed root");
        for (Path p = target; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) throw new IOException("Resource links are not supported");
        return target;
    }
    public static Map<String, byte[]> scan(Map<String, Path> roots) throws IOException {
        Map<String, byte[]> out = new TreeMap<>(); long total = 0;
        for (Map.Entry<String, Path> entry : roots.entrySet()) {
            Path root = entry.getValue(); resolve(root, "resource-root-check"); if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.isSymbolicLink(root)) throw new IOException("Managed root is a symbolic link");
            try (Stream<Path> files = Files.walk(root, 16)) {
                Iterator<Path> iterator = files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().iterator();
                while (iterator.hasNext()) {
                    Path file = iterator.next(); String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
                    if (relative.equals("index.json") && entry.getKey().equals("memory")) continue;
                    if (Arrays.stream(relative.split("/")).anyMatch(part -> part.startsWith("."))) continue;
                    resolve(root, relative);
                    if (Files.size(file) > MAX_FILE || out.size() >= MAX_FILES) throw new IOException("Resource file count or size limit exceeded");
                    byte[] data = Files.readAllBytes(file); total += data.length;
                    if (data.length > MAX_FILE || total > MAX_TOTAL) throw new IOException("Resource aggregate limit exceeded");
                    out.put(entry.getKey() + "/" + relative, data);
                }
            }
        }
        return out;
    }
    public static Path target(Map<String, Path> roots, String key) throws IOException {
        int slash = key.indexOf('/'); if (slash < 1 || !roots.containsKey(key.substring(0, slash))) throw new IOException("Unknown resource scope");
        return resolve(roots.get(key.substring(0, slash)), key.substring(slash + 1));
    }
    public static void write(Path path, byte[] data) throws IOException {
        Files.createDirectories(path.getParent()); Path temp = Files.createTempFile(path.getParent(), ".sync-", ".tmp");
        try {
            Files.write(temp, data);
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    public static JsonObject object(JsonObject value, String key) { return value.has(key) && value.get(key).isJsonObject() ? value.getAsJsonObject(key) : new JsonObject(); }
    public static String str(JsonObject value, String key) { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : ""; }
    public static String digest(byte[] data) { return data == null ? "" : hash(data); }
    public static byte[] decode(JsonObject entry) throws IOException {
        String data = str(entry, "data"), digest = str(entry, "hash");
        if (digest.isEmpty()) { if (!data.isEmpty()) throw new IOException("Invalid deletion"); return null; }
        if (data.length() > MAX_FILE * 2) throw new IOException("Resource file too large");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(data); } catch (IllegalArgumentException e) { throw new IOException("Invalid resource data", e); }
        if (bytes.length > MAX_FILE || !hash(bytes).equals(digest)) throw new IOException("Resource hash mismatch"); return bytes;
    }
    public static JsonObject entry(byte[] data) {
        JsonObject entry = new JsonObject(); entry.addProperty("hash", digest(data));
        if (data != null) entry.addProperty("data", Base64.getEncoder().encodeToString(data)); return entry;
    }
}
