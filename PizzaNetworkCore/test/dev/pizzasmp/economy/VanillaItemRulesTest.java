// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.economy;

import java.util.List;
import java.util.Set;

public final class VanillaItemRulesTest {
    private static int assertions;

    public static void main(String[] args) {
        var efficiency = new VanillaItemRules.EnchantmentFact("minecraft:efficiency", 5, 5, true, Set.of());
        var unbreaking = new VanillaItemRules.EnchantmentFact("minecraft:unbreaking", 3, 3, true, Set.of());
        var mending = new VanillaItemRules.EnchantmentFact("minecraft:mending", 1, 1, true, Set.of());
        equal(null, valid(List.of(efficiency, unbreaking, mending)));

        equal("illegal_enchantment_level", valid(List.of(
            new VanillaItemRules.EnchantmentFact("minecraft:fortune", 100, 3, true, Set.of()))));
        equal("custom_enchantment", valid(List.of(
            new VanillaItemRules.EnchantmentFact("plugin:fortune", 1, 1, true, Set.of()))));
        equal("illegal_enchantment_target", valid(List.of(
            new VanillaItemRules.EnchantmentFact("minecraft:fortune", 3, 3, false, Set.of()))));
        equal("incompatible_enchantments", valid(List.of(
            new VanillaItemRules.EnchantmentFact("minecraft:fortune", 3, 3, true, Set.of("minecraft:silk_touch")),
            new VanillaItemRules.EnchantmentFact("minecraft:silk_touch", 1, 1, true, Set.of()))));
        equal("plugin_item_data", facts(true, false, false, false, true, List.of()));
        equal("custom_item_model", facts(false, true, false, false, true, List.of()));
        equal("unbreakable_component", facts(false, false, true, false, true, List.of()));
        equal("custom_potion_effects", facts(false, false, false, true, true, List.of()));
        equal("custom_attributes", facts(false, false, false, false, false, List.of()));
        equal("invalid_stack_size", VanillaItemRules.violation(new VanillaItemRules.Facts(true, 65, 64,
            false, false, false, false, true, List.of())));
        var validBedrockFacts = new VanillaItemRules.Facts(true, 1, 64,
            false, false, false, false, true, List.of());
        equal(null, VanillaItemRules.violation(validBedrockFacts));
        equal("survival_restricted_item", VanillaItemRules.violation(validBedrockFacts, "minecraft:bedrock", true));
        equal(null, VanillaItemRules.violation(validBedrockFacts, "minecraft:bedrock", false));
        equal("survival_restricted_item", VanillaItemRules.violation(validBedrockFacts, "minecraft:command_block", true));
        equal(null, VanillaItemRules.violation(validBedrockFacts, "minecraft:dragon_egg", true));
        equal(null, VanillaItemRules.violation(validBedrockFacts, "minecraft:diamond", true));
        var creativeIllegalEnchant = new VanillaItemRules.Facts(true, 1, 1,
            false, false, false, false, true,
            List.of(new VanillaItemRules.EnchantmentFact("minecraft:fortune", 100, 3, true, Set.of())));
        equal("illegal_enchantment_level", VanillaItemRules.violation(creativeIllegalEnchant, "minecraft:diamond_pickaxe", false));
        System.out.println("Vanilla item rules: " + assertions + " assertions passed");
    }

    private static String valid(List<VanillaItemRules.EnchantmentFact> enchants) {
        return VanillaItemRules.violation(new VanillaItemRules.Facts(true, 1, 1,
            false, false, false, false, true, enchants));
    }

    private static String facts(boolean pdc, boolean model, boolean unbreakable, boolean potion, boolean attributes, List<VanillaItemRules.EnchantmentFact> enchants) {
        return VanillaItemRules.violation(new VanillaItemRules.Facts(true, 1, 1,
            pdc, model, unbreakable, potion, attributes, enchants));
    }

    private static void equal(String expected, String actual) {
        assertions++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
    }
}
