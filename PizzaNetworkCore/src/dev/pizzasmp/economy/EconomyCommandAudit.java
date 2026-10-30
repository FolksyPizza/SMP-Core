// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.economy;

import java.util.Locale;
import java.util.Set;

/** Removes credential and private-message bodies before command audit text is persisted. */
public final class EconomyCommandAudit {
    private static final int MAX_LENGTH = 500;
    private static final Set<String> CREDENTIAL_ROOTS = Set.of(
        "auth", "authme", "login", "l", "register", "reg", "changepassword", "changepass",
        "password", "passwd", "2fa", "totp");
    private static final Set<String> PRIVATE_MESSAGE_ROOTS = Set.of(
        "msg", "tell", "w", "whisper", "reply", "r");

    private EconomyCommandAudit() { }

    public static String redact(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String text = cleanControls(raw).strip();
        boolean slash = text.startsWith("/");
        String body = slash ? text.substring(1).stripLeading() : text;
        String[] words = body.split("\\s+", 3);
        if (words.length == 0 || words[0].isBlank()) return "";

        String root = words[0];
        String effectiveRoot = root.substring(root.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
        String safe;
        if (CREDENTIAL_ROOTS.contains(effectiveRoot)) {
            safe = root + " <credentials redacted>";
        } else if (PRIVATE_MESSAGE_ROOTS.contains(effectiveRoot)) {
            boolean hasRecipient = !Set.of("reply", "r").contains(effectiveRoot) && words.length >= 2;
            safe = root + (hasRecipient ? " " + words[1] : "") + " <message redacted>";
        } else {
            safe = body;
        }
        if (slash) safe = "/" + safe;
        if (safe.length() > MAX_LENGTH) safe = safe.substring(0, MAX_LENGTH);
        return safe;
    }

    private static String cleanControls(String raw) {
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_LENGTH));
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            out.append(Character.isISOControl(ch) ? ' ' : ch);
        }
        return out.toString();
    }
}
