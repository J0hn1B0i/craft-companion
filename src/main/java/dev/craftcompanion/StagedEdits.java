package dev.craftcompanion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Claude never edits game files directly. In edit mode it writes complete new versions to
 * craft-companion/staged/<root>/<path>. The player reviews the list and applies it, which backs up
 * the originals to craft-companion/backups/<time>/ so /claude undo can put them back.
 * No Minecraft dependencies.
 */
public final class StagedEdits {

    /** Game folders that may be changed. */
    public static final List<String> ROOTS = List.of("config", "defaultconfigs", "kubejs", "datapacks");

    private static final String NEW_FILES = "_new_files.txt";

    private final Path gameDir;
    private final Path staged;
    private final Path backups;

    public StagedEdits(Path gameDir, Path chatDir) {
        this.gameDir = gameDir.toAbsolutePath().normalize();
        this.staged = chatDir.resolve("staged");
        this.backups = chatDir.resolve("backups");
    }

    /** Empties the staging folder before a new edit request. */
    public void clear() throws IOException {
        deleteTree(staged);
    }

    /** Staged files as game-relative paths ("config/foo.toml"). Ignores anything outside the allowed roots. */
    public List<String> pending() throws IOException {
        if (!Files.isDirectory(staged)) return List.of();
        try (Stream<Path> s = Files.walk(staged)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> staged.relativize(p).toString().replace('\\', '/'))
                    .filter(StagedEdits::allowed)
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    static boolean allowed(String rel) {
        if (rel.contains("..") || rel.startsWith("/")) return false;
        int i = rel.indexOf('/');
        return i > 0 && ROOTS.contains(rel.substring(0, i));
    }

    /** Backs up the originals, copies staged files into the game folder, empties staging. Returns files applied. */
    public List<String> apply() throws IOException {
        List<String> files = pending();
        if (files.isEmpty()) return files;
        Path backup = backups.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Files.createDirectories(backup);
        List<String> created = new ArrayList<>();
        for (String rel : files) {
            Path target = gameDir.resolve(rel).normalize();
            if (!target.startsWith(gameDir)) continue;
            if (Files.exists(target)) {
                Path b = backup.resolve(rel);
                Files.createDirectories(b.getParent());
                Files.copy(target, b, StandardCopyOption.REPLACE_EXISTING);
            } else {
                created.add(rel);
            }
            Files.createDirectories(target.getParent());
            Files.copy(staged.resolve(rel), target, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(backup.resolve(NEW_FILES), String.join("\n", created));
        clear();
        return files;
    }

    /** Restores the most recent backup and removes it. Returns the files restored or removed. */
    public Optional<List<String>> undo() throws IOException {
        if (!Files.isDirectory(backups)) return Optional.empty();
        Optional<Path> latest;
        try (Stream<Path> s = Files.list(backups)) {
            latest = s.filter(Files::isDirectory).max(Comparator.comparing(p -> p.getFileName().toString()));
        }
        if (latest.isEmpty()) return Optional.empty();
        Path backup = latest.get();
        List<String> touched = new ArrayList<>();
        try (Stream<Path> s = Files.walk(backup)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                String rel = backup.relativize(p).toString().replace('\\', '/');
                if (rel.equals(NEW_FILES) || !allowed(rel)) continue;
                Path target = gameDir.resolve(rel).normalize();
                if (!target.startsWith(gameDir)) continue;
                Files.createDirectories(target.getParent());
                Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                touched.add(rel);
            }
        }
        Path nf = backup.resolve(NEW_FILES);
        if (Files.exists(nf)) {
            for (String rel : Files.readAllLines(nf)) {
                rel = rel.trim();
                if (rel.isEmpty() || !allowed(rel)) continue;
                Path target = gameDir.resolve(rel).normalize();
                if (target.startsWith(gameDir) && Files.deleteIfExists(target)) touched.add(rel + " (removed)");
            }
        }
        deleteTree(backup);
        return Optional.of(touched);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
        }
    }
}
