// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.admin;

public final class HomeDimensionResolverTest {
    private static int assertions;

    public static void main(String[] args) {
        equal("minecraft:the_nether", HomeDimensionResolver.resolve("minecraft:the_nether", "world"));
        equal("minecraft:overworld", HomeDimensionResolver.resolve(null, "world"));
        equal("minecraft:the_nether", HomeDimensionResolver.resolve(null, "world_nether"));
        equal("minecraft:the_end", HomeDimensionResolver.resolve(null, "the_end"));
        equal(null, HomeDimensionResolver.resolve(null, "survival_custom"));
        equal(null, HomeDimensionResolver.resolve("plugin:dimension", "world"));
        System.out.println("Home dimension resolution: " + assertions + " assertions passed");
    }

    private static void equal(String expected, String actual) {
        assertions++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
    }
}
