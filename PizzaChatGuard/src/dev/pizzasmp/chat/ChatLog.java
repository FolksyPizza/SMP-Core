// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the SMP-Core Source License, version 1.0 (see LICENSE). All rights reserved.
package dev.pizzasmp.chat;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Chat log: every public message and private message, with the player's name and UUID and the time (UTC), one file
 * per day under chat-log/. Messages the filter blocked are logged too, marked as blocked. Files older than the
 * retention are deleted. Writes happen on one background thread, so chat never waits on the disk.
 */
final class ChatLog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_INSTANT;

    private final File dir;
    private final int retentionDays;
    private final Logger logger;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PizzaChatGuard chat log");
        t.setDaemon(true);
        return t;
    });

    ChatLog(File dataFolder, int retentionDays, Logger logger) {
        this.dir = new File(dataFolder, "chat-log");
        this.retentionDays = Math.max(1, retentionDays);
        this.logger = logger;
        writer.execute(this::prune);
    }

    void chat(String name, UUID id, String message, boolean blocked) {
        write("CHAT", name, id, message, blocked);
    }

    void privateMessage(String name, UUID id, String command, boolean blocked) {
        write("PM  ", name, id, command, blocked);
    }

    void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void write(String kind, String name, UUID id, String text, boolean blocked) {
        Instant now = Instant.now();
        String line = TIME.format(now) + " " + kind + " " + name + " (" + id + ")" + (blocked ? " [blocked]" : "")
            + ": " + text.replace('\n', ' ').replace('\r', ' ') + System.lineSeparator();
        writer.execute(() -> {
            try {
                Files.createDirectories(dir.toPath());
                File file = new File(dir, LocalDate.ofInstant(now, ZoneOffset.UTC) + ".log");
                try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(line);
                }
            } catch (IOException e) {
                logger.warning("Chat log write failed: " + e.getMessage());
            }
        });
    }

    private void prune() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".log"));
        if (files == null) return;
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(retentionDays);
        for (File f : files) {
            try {
                if (LocalDate.parse(f.getName().substring(0, f.getName().length() - 4)).isBefore(cutoff) && !f.delete()) {
                    logger.warning("Could not delete old chat log " + f.getName());
                }
            } catch (Exception ignored) {
                // Not a dated log file.
            }
        }
    }
}
