package com.mola.cmd.proxy.app.acp.observation;

import com.google.gson.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Channel state and its change event commit in the same SQLite transaction. */
final class ObservationStore {
    private final String url;

    ObservationStore(Path directory) {
        try {
            Files.createDirectories(directory);
            Class.forName("org.sqlite.JDBC");
            url = "jdbc:sqlite:" + directory.resolve("observations.db").toAbsolutePath();
            try (Connection c = open();
                    Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute(
                        "CREATE TABLE IF NOT EXISTS observation_channel(id TEXT PRIMARY KEY, data"
                                + " TEXT NOT NULL)");
                s.execute(
                        "CREATE TABLE IF NOT EXISTS observation_event(id TEXT PRIMARY KEY,"
                                + " channel_id TEXT NOT NULL, owner_path TEXT NOT NULL, created_at"
                                + " INTEGER NOT NULL, status TEXT NOT NULL, data TEXT NOT NULL)");
                s.execute(
                        "CREATE INDEX IF NOT EXISTS observation_event_channel ON"
                                + " observation_event(channel_id,created_at DESC)");
                s.execute(
                        "CREATE INDEX IF NOT EXISTS observation_event_pending ON"
                                + " observation_event(status,owner_path,created_at)");
            }
        } catch (Exception e) {
            throw new IllegalStateException("观测存储初始化失败", e);
        }
    }

    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA synchronous=FULL");
        }
        return c;
    }

    synchronized List<JsonObject> channels() {
        List<JsonObject> result = new ArrayList<>();
        try (Connection c = open();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT data FROM observation_channel ORDER BY id")) {
            while (r.next()) result.add(JsonParser.parseString(r.getString(1)).getAsJsonObject());
            return result;
        } catch (Exception e) {
            throw failure(e);
        }
    }

    synchronized void save(JsonObject channel, JsonObject event) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement s =
                        c.prepareStatement(
                                "INSERT OR REPLACE INTO observation_channel(id,data)"
                                        + " VALUES(?,?)")) {
                    s.setString(1, channel.get("id").getAsString());
                    s.setString(2, channel.toString());
                    s.executeUpdate();
                }
                if (event != null) writeEvent(c, event);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw failure(e);
        }
    }

    synchronized void saveEvent(JsonObject event) {
        try (Connection c = open()) {
            writeEvent(c, event);
        } catch (Exception e) {
            throw failure(e);
        }
    }

    private void writeEvent(Connection c, JsonObject event) throws SQLException {
        try (PreparedStatement s =
                c.prepareStatement(
                        "INSERT OR REPLACE INTO"
                            + " observation_event(id,channel_id,owner_path,created_at,status,data)"
                            + " VALUES(?,?,?,?,?,?)")) {
            s.setString(1, event.get("id").getAsString());
            s.setString(2, event.get("channelId").getAsString());
            s.setString(3, event.get("ownerPath").getAsString());
            s.setLong(4, event.get("createdAt").getAsLong());
            s.setString(5, event.get("status").getAsString());
            s.setString(6, event.toString());
            s.executeUpdate();
        }
    }

    synchronized void delete(String id) {
        try (Connection c = open();
                PreparedStatement s =
                        c.prepareStatement("DELETE FROM observation_channel WHERE id=?")) {
            s.setString(1, id);
            s.executeUpdate();
        } catch (Exception e) {
            throw failure(e);
        }
    }

    synchronized JsonObject events(
            String owner,
            String channel,
            String status,
            String eventId,
            int page,
            int size,
            boolean full) {
        page = Math.max(1, page);
        size = Math.max(1, Math.min(100, size));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<String> args = new ArrayList<>();
        filter(where, args, "owner_path", owner);
        filter(where, args, "channel_id", channel);
        filter(where, args, "status", status);
        filter(where, args, "id", eventId);
        try (Connection c = open()) {
            long total;
            try (PreparedStatement s =
                    c.prepareStatement("SELECT COUNT(*) FROM observation_event" + where)) {
                bind(s, args);
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    total = r.getLong(1);
                }
            }
            JsonArray items = new JsonArray();
            try (PreparedStatement s =
                    c.prepareStatement(
                            "SELECT data FROM observation_event"
                                    + where
                                    + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?")) {
                bind(s, args);
                s.setInt(args.size() + 1, size);
                s.setLong(args.size() + 2, ((long) page - 1) * size);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        JsonObject item = JsonParser.parseString(r.getString(1)).getAsJsonObject();
                        if (!full) {
                            item.remove("eventAction");
                            for (String key : Arrays.asList("before", "after")) {
                                String value = item.get(key).getAsString();
                                item.addProperty(key + "Truncated", value.length() > 240);
                                item.addProperty(key, ObservationManager.preview(value, 240));
                            }
                        }
                        items.add(item);
                    }
                }
            }
            return ObservationManager.page(items, page, size, total);
        } catch (Exception e) {
            throw failure(e);
        }
    }

    synchronized List<JsonObject> pending(String owner, int limit) {
        List<JsonObject> result = new ArrayList<>();
        try (Connection c = open();
                PreparedStatement s =
                        c.prepareStatement(
                                "SELECT e.data FROM observation_event e JOIN observation_channel c"
                                    + " ON c.id=e.channel_id WHERE e.owner_path=? AND"
                                    + " e.status='PENDING' AND json_extract(c.data,'$.enabled')=1"
                                    + " AND COALESCE(json_extract(c.data,'$.capabilityPaused'),0)=0"
                                    + " ORDER BY e.created_at,e.id LIMIT ?")) {
            s.setString(1, owner);
            s.setInt(2, limit);
            try (ResultSet r = s.executeQuery()) {
                while (r.next())
                    result.add(JsonParser.parseString(r.getString(1)).getAsJsonObject());
            }
            return result;
        } catch (Exception e) {
            throw failure(e);
        }
    }

    synchronized List<JsonObject> sources() {
        List<JsonObject> result = new ArrayList<>();
        try (Connection c = open();
                Statement s = c.createStatement();
                ResultSet r =
                        s.executeQuery(
                                "SELECT"
                                    + " channel_id,owner_path,json_extract(data,'$.channelName'),json_extract(data,'$.owner'),MAX(created_at)"
                                    + " FROM observation_event GROUP BY channel_id")) {
            while (r.next()) {
                JsonObject source = new JsonObject();
                source.addProperty("channelId", r.getString(1));
                source.addProperty("ownerPath", r.getString(2));
                source.addProperty("channelName", r.getString(3));
                source.add("owner", JsonParser.parseString(r.getString(4)));
                result.add(source);
            }
            return result;
        } catch (Exception e) {
            throw failure(e);
        }
    }

    private static void filter(StringBuilder sql, List<String> args, String field, String value) {
        if (value != null && !value.isEmpty()) {
            sql.append(" AND ").append(field).append("=?");
            args.add(value);
        }
    }

    private static void bind(PreparedStatement s, List<String> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) s.setString(i + 1, args.get(i));
    }

    private static IllegalStateException failure(Exception e) {
        return new IllegalStateException("观测数据保存或读取失败", e);
    }
}
