package com.cactuslauncher.game;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.*;

/** Local offline profiles; no passwords or Microsoft credentials are stored. */
public final class ProfileStore {
    public record Profile(String id, String name) {}
    public record Snapshot(List<Profile> profiles, String activeId) {}
    private final Path file;

    public ProfileStore(Path file) { this.file = file; }

    public static Path defaultFile() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        Path base = os.contains("mac") ? Path.of(home, "Library", "Application Support")
            : os.contains("win") ? Path.of(System.getenv().getOrDefault("APPDATA", home))
            : Path.of(System.getenv().getOrDefault("XDG_CONFIG_HOME", home + "/.config"));
        return base.resolve("CactusLauncher").resolve("profiles.properties");
    }

    public synchronized Snapshot load() throws IOException {
        if (!Files.exists(file)) {
            Snapshot initial = new Snapshot(List.of(new Profile(UUID.randomUUID().toString(), "Nick")), "");
            initial = new Snapshot(initial.profiles(), initial.profiles().getFirst().id());
            save(initial);
            return initial;
        }
        Properties p = new Properties();
        try (InputStream input = Files.newInputStream(file)) { p.load(input); }
        List<Profile> profiles = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String id : p.getProperty("order", "").split(",")) {
            if (id.isBlank()) continue;
            String name = p.getProperty("profile." + id);
            if (name == null || !name.matches("[A-Za-z0-9_]{3,16}") || !names.add(name.toLowerCase(Locale.ROOT)))
                throw new IOException("Файл профилей повреждён. Исходный файл сохранён.");
            profiles.add(new Profile(id, name));
        }
        if (profiles.isEmpty()) throw new IOException("В файле профилей отсутствуют профили.");
        String active = p.getProperty("active", profiles.getFirst().id());
        final String candidate = active;
        if (profiles.stream().noneMatch(it -> it.id().equals(candidate))) active = profiles.getFirst().id();
        return new Snapshot(List.copyOf(profiles), active);
    }

    public synchronized Snapshot add(String name) throws IOException {
        Snapshot old = load();
        name = validate(name, old.profiles(), null);
        Profile profile = new Profile(UUID.randomUUID().toString(), name);
        List<Profile> list = new ArrayList<>(old.profiles());
        list.add(profile);
        Snapshot next = new Snapshot(List.copyOf(list), profile.id());
        save(next);
        return next;
    }

    public synchronized Snapshot rename(String id, String name) throws IOException {
        Snapshot old = load();
        requireProfile(old, id);
        String valid = validate(name, old.profiles(), id);
        Snapshot next = new Snapshot(old.profiles().stream()
            .map(it -> it.id().equals(id) ? new Profile(id, valid) : it).toList(), old.activeId());
        save(next);
        return next;
    }

    public synchronized Snapshot select(String id) throws IOException {
        Snapshot old = load();
        requireProfile(old, id);
        Snapshot next = new Snapshot(old.profiles(), id);
        save(next);
        return next;
    }

    public synchronized Snapshot delete(String id) throws IOException {
        Snapshot old = load();
        requireProfile(old, id);
        if (old.profiles().size() == 1) throw new IllegalArgumentException("Нельзя удалить последний профиль.");
        List<Profile> list = old.profiles().stream().filter(it -> !it.id().equals(id)).toList();
        Snapshot next = new Snapshot(list, old.activeId().equals(id) ? list.getFirst().id() : old.activeId());
        save(next);
        return next;
    }

    private static void requireProfile(Snapshot snapshot, String id) {
        if (snapshot.profiles().stream().noneMatch(it -> it.id().equals(id)))
            throw new IllegalArgumentException("Профиль не найден.");
    }

    private static String validate(String name, List<Profile> profiles, String excluded) {
        String value = name.trim();
        if (!value.matches("[A-Za-z0-9_]{3,16}"))
            throw new IllegalArgumentException("Имя: 3–16 символов, латинские буквы, цифры и подчёркивание.");
        if (profiles.stream().anyMatch(it -> !it.id().equals(excluded) && it.name().equalsIgnoreCase(value)))
            throw new IllegalArgumentException("Профиль с таким именем уже существует.");
        return value;
    }

    private void save(Snapshot snapshot) throws IOException {
        Files.createDirectories(file.getParent());
        Properties p = new Properties();
        p.setProperty("active", snapshot.activeId());
        p.setProperty("order", String.join(",", snapshot.profiles().stream().map(Profile::id).toList()));
        for (Profile profile : snapshot.profiles()) p.setProperty("profile." + profile.id(), profile.name());
        Path temp = Files.createTempFile(file.getParent(), "profiles-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temp)) { p.store(output, "Cactus Launcher offline profiles"); }
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
