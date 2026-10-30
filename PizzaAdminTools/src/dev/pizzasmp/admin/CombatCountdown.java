// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.admin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

final class CombatCountdown {
    static final long DURATION_MILLIS = 20_000L;
    private CombatCountdown() { }

    static long secondsRemaining(long expiresAt, long now) {
        long remaining = Math.max(0L, expiresAt - now);
        return remaining / 1_000L + (remaining % 1_000L == 0 ? 0 : 1);
    }

    static Component message(long seconds) {
        return Component.text("Combat: ", NamedTextColor.WHITE)
            .append(Component.text(Long.toString(seconds), TextColor.color(0x00BFFF)));
    }
}
