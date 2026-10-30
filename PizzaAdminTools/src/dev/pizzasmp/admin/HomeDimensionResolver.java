// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.admin;

import java.util.Locale;
import java.util.Set;

/** Resolves only canonical Canopy dimensions; ambiguous custom world names fail closed. */
public final class HomeDimensionResolver {
    private static final Set<String> DIMENSIONS = Set.of(
        "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");

    private HomeDimensionResolver() { }

    public static String resolve(String storedDimension, String legacyWorldName) {
        if (storedDimension != null && !storedDimension.isBlank()) {
            String dimension = storedDimension.trim().toLowerCase(Locale.ROOT);
            return DIMENSIONS.contains(dimension) ? dimension : null;
        }
        if (legacyWorldName == null || legacyWorldName.isBlank()) return null;
        return switch (legacyWorldName.trim().toLowerCase(Locale.ROOT)) {
            case "world", "overworld", "world_overworld", "minecraft:overworld" -> "minecraft:overworld";
            case "world_nether", "nether", "the_nether", "minecraft:the_nether" -> "minecraft:the_nether";
            case "world_the_end", "end", "the_end", "minecraft:the_end" -> "minecraft:the_end";
            default -> null;
        };
    }
}
