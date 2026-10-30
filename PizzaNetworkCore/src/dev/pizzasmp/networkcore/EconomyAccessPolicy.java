// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.networkcore;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Owner UUID allowlist plus the explicit staff/operator access bypass for the staged economy rollout. */
public final class EconomyAccessPolicy {
    private final Set<UUID> allowedPlayers;
    /** {@code "*"} in the list opens the economy to every player. */
    private final boolean everyone;

    public EconomyAccessPolicy(Collection<String> configuredUuids) {
        if (configuredUuids == null || configuredUuids.isEmpty()) {
            this.allowedPlayers = Set.of();
            this.everyone = false;
            return;
        }
        if (configuredUuids.stream().anyMatch(value -> value != null && "*".equals(value.trim()))) {
            this.allowedPlayers = Set.of();
            this.everyone = true;
            return;
        }
        this.everyone = false;
        Set<UUID> parsed = new HashSet<>();
        boolean valid = true;
        for (String configured : configuredUuids) {
            if (configured == null || configured.isBlank()) {
                valid = false;
                break;
            }
            try {
                UUID uuid = UUID.fromString(configured.trim());
                if (!uuid.toString().equalsIgnoreCase(configured.trim())) {
                    valid = false;
                    break;
                }
                parsed.add(uuid);
            } catch (IllegalArgumentException invalid) {
                valid = false;
                break;
            }
        }
        this.allowedPlayers = valid ? Set.copyOf(parsed) : Set.of();
    }

    public boolean allows(UUID playerId) {
        return playerId != null && (everyone || allowedPlayers.contains(playerId));
    }

    public boolean allows(UUID playerId, boolean operator, boolean adminPermission) {
        return operator || adminPermission || allows(playerId);
    }

    public boolean configured() {
        return everyone || !allowedPlayers.isEmpty();
    }
}
