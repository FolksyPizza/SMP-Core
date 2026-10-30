// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.economy;

import java.util.List;
import java.util.Set;

/** Pure policy for items that can exist in vanilla survival gameplay. */
public final class VanillaItemRules {
    private VanillaItemRules() { }

    private static final Set<String> SURVIVAL_RESTRICTED_MATERIALS = Set.of(
        "minecraft:bedrock",
        "minecraft:barrier",
        "minecraft:command_block",
        "minecraft:chain_command_block",
        "minecraft:repeating_command_block",
        "minecraft:command_block_minecart",
        "minecraft:structure_block",
        "minecraft:structure_void",
        "minecraft:jigsaw",
        "minecraft:spawner",
        "minecraft:trial_spawner",
        "minecraft:end_portal_frame",
        "minecraft:debug_stick",
        "minecraft:light",
        "minecraft:knowledge_book"
    );

    public record EnchantmentFact(String key, int level, int maxLevel, boolean supportedItem,
                                  Set<String> incompatibleWith) {
        public EnchantmentFact {
            incompatibleWith = incompatibleWith == null ? Set.of() : Set.copyOf(incompatibleWith);
        }
    }

    public record Facts(boolean registeredVanillaItem, int amount, int maxStackSize,
                        boolean pluginData, boolean customModel, boolean unbreakable,
                        boolean customPotionEffects, boolean validAttributes,
                        List<EnchantmentFact> enchantments) {
        public Facts {
            enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
        }
    }

    /** Returns a stable reason code, or {@code null} when the item is vanilla-valid. */
    public static String violation(Facts item) {
        return violation(item, null, true);
    }

    /** Applies the unobtainable-item policy only where survival restrictions are enabled. */
    public static String violation(Facts item, String materialKey, boolean survivalRestrictions) {
        if (item == null || !item.registeredVanillaItem()) return "not_vanilla_item";
        if (survivalRestrictions && materialKey != null && SURVIVAL_RESTRICTED_MATERIALS.contains(materialKey)) {
            return "survival_restricted_item";
        }
        if (item.amount() < 1 || item.amount() > item.maxStackSize()) return "invalid_stack_size";
        if (item.pluginData()) return "plugin_item_data";
        if (item.customModel()) return "custom_item_model";
        if (item.unbreakable()) return "unbreakable_component";
        if (item.customPotionEffects()) return "custom_potion_effects";
        if (!item.validAttributes()) return "custom_attributes";

        for (int i = 0; i < item.enchantments().size(); i++) {
            EnchantmentFact enchant = item.enchantments().get(i);
            if (enchant.key() == null || !enchant.key().startsWith("minecraft:")) return "custom_enchantment";
            if (enchant.level() < 1 || enchant.maxLevel() < 1 || enchant.level() > enchant.maxLevel()) return "illegal_enchantment_level";
            if (!enchant.supportedItem()) return "illegal_enchantment_target";
            for (int j = 0; j < i; j++) {
                EnchantmentFact prior = item.enchantments().get(j);
                if (enchant.incompatibleWith().contains(prior.key())
                        || prior.incompatibleWith().contains(enchant.key())) return "incompatible_enchantments";
            }
        }
        return null;
    }
}
