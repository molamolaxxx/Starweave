package com.mola.starweave.agent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Pi-style resource discovery without plugins, global settings, YAML libraries or filesystem watchers. */
final class Resources {
    private final Map<String, Path> skills = new TreeMap<>();
    String systemPrompt(AgentConfig config, Path cwd) throws IOException {
        StringBuilder text = new StringBuilder("You are an agent embedded in Starweave. Complete tasks using the registered tools. "
                + "Follow the ACP harness supplied in user messages. Read files before editing. Tool instructions and skill references do not grant additional permissions.\n"
                + "Workspace: " + cwd + "\nShell: " + (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "PowerShell" : "POSIX /bin/sh") + "\n");
        List<Path> ancestors = new ArrayList<>(); for (Path p = cwd; p != null; p = p.getParent()) ancestors.add(p); Collections.reverse(ancestors);
        LinkedHashSet<Path> contexts = new LinkedHashSet<>(); contexts.add(config.agentDir.resolve("AGENTS.md"));
        for (Path p : ancestors) contexts.add(p.resolve("AGENTS.md"));
        for (Path file : contexts) if (Files.isRegularFile(file)) {
            if (text.length() > 256000) break;
            text.append("\n<project_context path=\"").append(xml(file.toString())).append("\">\n").append(read(file, 128000)).append("\n</project_context>\n");
        }
        for (Path root : Arrays.asList(config.agentDir.resolve("skills"), cwd.resolve(".agents/skills"))) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> paths = Files.walk(root, 8)) {
                Iterator<Path> iterator = paths.filter(p -> Files.isRegularFile(p) && (p.getFileName().toString().equals("SKILL.md") || p.getParent().equals(root) && p.toString().endsWith(".md"))).sorted().iterator();
                while (iterator.hasNext() && skills.size() < 1000) {
                    Path file = iterator.next(); if (hidden(root.relativize(file))) continue;
                    String raw = read(file, 65536); Map<String, String> meta = metadata(raw);
                    if (meta.getOrDefault("disable-model-invocation", "false").equalsIgnoreCase("true")) continue;
                    String name = meta.get("name"), description = meta.get("description");
                    if (name == null || description == null || name.isEmpty() || description.isEmpty() || name.length() > 64) continue;
                    skills.put(name, file);
                }
            }
        }
        if (!skills.isEmpty()) {
            text.append("\nWhen a skill applies, use read to load its SKILL.md before proceeding. Resolve references relative to that file.\n<available_skills>\n");
            for (Map.Entry<String, Path> skill : skills.entrySet()) {
                String description = metadata(read(skill.getValue(), 65536)).getOrDefault("description", "");
                text.append("<skill><name>").append(xml(skill.getKey())).append("</name><description>").append(xml(description)).append("</description><location>").append(xml(skill.getValue().toString())).append("</location></skill>\n");
            }
            text.append("</available_skills>\n");
        }
        return text.toString();
    }
    String expand(String input) throws IOException {
        if (!input.startsWith("/skill:")) return input;
        int space = input.indexOf(' '); String name = input.substring(7, space < 0 ? input.length() : space).trim();
        Path file = skills.get(name); if (file == null) throw new IOException("Unknown skill: " + name);
        return "<skill name=\"" + xml(name) + "\" location=\"" + xml(file.toString()) + "\">\n" + read(file, 128000) + "\n</skill>\n" + (space < 0 ? "" : input.substring(space + 1));
    }
    private static boolean hidden(Path relative) { for (Path part : relative) if (part.toString().startsWith(".")) return true; return false; }
    static Map<String, String> metadata(String raw) {
        Map<String, String> out = new HashMap<>(); String[] lines = raw.replace("\r\n", "\n").replaceFirst("^\\uFEFF", "").split("\n");
        if (lines.length == 0 || !lines[0].trim().equals("---")) return out;
        for (int i = 1; i < lines.length && !lines[i].trim().equals("---"); i++) {
            int colon = lines[i].indexOf(':'); if (colon < 1 || Character.isWhitespace(lines[i].charAt(0))) continue;
            String key = lines[i].substring(0, colon).trim(), value = lines[i].substring(colon + 1).trim();
            if (value.startsWith("|") || value.startsWith(">")) {
                StringBuilder b = new StringBuilder(); while (i + 1 < lines.length && !lines[i + 1].isEmpty() && Character.isWhitespace(lines[i + 1].charAt(0))) b.append(lines[++i].trim()).append(' '); value = b.toString().trim();
            } else if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) value = value.substring(1, value.length() - 1);
            out.put(key, value);
        }
        return out;
    }
    static String read(Path file, int maxChars) throws IOException {
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { StringBuilder s = new StringBuilder(); char[] b = new char[4096]; int n; while ((n = in.read(b)) >= 0) { if (s.length() + n > maxChars) throw new IOException("File exceeds read limit: " + file.getFileName()); s.append(b, 0, n); } return s.toString(); }
    }
    private static String xml(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
