// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.networkcore;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.enchantments.EnchantmentRarity;
import io.papermc.paper.registry.set.RegistryKeySet;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.EntityCategory;
import org.bukkit.entity.EntityType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentTarget;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import net.kyori.adventure.text.Component;

/** Focused no-server proof for the shard currency feature gate. */
public final class ShardCurrencyPolicyTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        installRegistryAccess();
        YamlConfiguration config = new YamlConfiguration();
        check(PizzaNetworkCore.shardsEnabled(config), "existing configs default to enabled");
        config.set("shards.enabled", true);
        check(PizzaNetworkCore.shardsEnabled(config), "explicit true keeps shards enabled");
        config.set("shards.enabled", false);
        check(!PizzaNetworkCore.shardsEnabled(config), "explicit false disables shards");

        PizzaNetworkCore plugin = allocateWithoutPluginLoader();
        Field settings = PizzaNetworkCore.class.getDeclaredField("settings");
        settings.setAccessible(true);
        settings.set(plugin, config);

        UUID playerId = UUID.randomUUID();
        check(((Long) invoke(plugin, "getShardBalance", new Class<?>[] { UUID.class }, playerId)) == 0L,
            "disabled balance reads return zero without storage access");
        check(!((Boolean) invoke(plugin, "withdrawShards", new Class<?>[] { UUID.class, long.class }, playerId, 1L)),
            "disabled withdrawals fail without storage access");
        invoke(plugin, "depositShards", new Class<?>[] { UUID.class, long.class }, playerId, 1L);
        assertions++;
        check(!((Boolean) invoke(plugin, "grantShards", new Class<?>[] { UUID.class, long.class }, playerId, 1L)),
            "disabled grants fail without storage access");

        System.out.println("Shard currency policy: " + assertions + " assertions passed");
    }

    private static PizzaNetworkCore allocateWithoutPluginLoader() throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        Method allocate = unsafeType.getMethod("allocateInstance", Class.class);
        return (PizzaNetworkCore) allocate.invoke(unsafe, PizzaNetworkCore.class);
    }

    private static void installRegistryAccess() throws Exception {
        RegistryAccess access = mock(RegistryAccess.class, (proxy, method, args) ->
            method.getName().equals("getRegistry") ? mockRegistry(String.valueOf(args[0])) : defaultValue(method.getReturnType()));
        Class<?> holder = Class.forName("io.papermc.paper.registry.RegistryAccessHolder");
        Field instance = holder.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Object base = unsafeType.getMethod("staticFieldBase", Field.class).invoke(unsafe, instance);
        long offset = (Long) unsafeType.getMethod("staticFieldOffset", Field.class).invoke(unsafe, instance);
        unsafeType.getMethod("putObjectVolatile", Object.class, long.class, Object.class)
            .invoke(unsafe, base, offset, java.util.Optional.of(access));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Registry<?> mockRegistry(String registryKey) {
        String registryName = registryKey.toLowerCase(java.util.Locale.ROOT);
        return mock(Registry.class, (proxy, method, args) -> {
            if (method.getName().equals("get")) return null;
            if (method.getName().equals("getOrThrow")) {
                if (registryName.contains("material")) return Material.STONE;
                if (registryName.contains("sound")) return mock(Sound.class, (sound, soundMethod, soundArgs) -> defaultValue(soundMethod.getReturnType()));
                if (registryName.contains("enchant")) return new TestEnchantment();
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static final class TestEnchantment extends Enchantment {
        @Override public NamespacedKey getKey() { return NamespacedKey.minecraft("test"); }
        @Override public String getName() { return "TEST"; }
        @Override public int getMaxLevel() { return 1; }
        @Override public int getStartLevel() { return 1; }
        @Override public EnchantmentTarget getItemTarget() { return null; }
        @Override public boolean isTreasure() { return false; }
        @Override public boolean isCursed() { return false; }
        @Override public boolean conflictsWith(Enchantment other) { return false; }
        @Override public boolean canEnchantItem(ItemStack item) { return false; }
        @Override public Component displayName(int level) { return Component.text("Test"); }
        @Override public boolean isTradeable() { return false; }
        @Override public boolean isDiscoverable() { return false; }
        @Override public int getMinModifiedCost(int level) { return 0; }
        @Override public int getMaxModifiedCost(int level) { return 0; }
        @Override public int getAnvilCost() { return 0; }
        @Override public EnchantmentRarity getRarity() { return EnchantmentRarity.COMMON; }
        @Override public float getDamageIncrease(int level, EntityCategory category) { return 0; }
        @Override public float getDamageIncrease(int level, EntityType type) { return 0; }
        @Override public Set<EquipmentSlotGroup> getActiveSlotGroups() { return Set.of(); }
        @Override public Component description() { return Component.text("Test"); }
        @Override public RegistryKeySet<ItemType> getSupportedItems() { return null; }
        @Override public RegistryKeySet<ItemType> getPrimaryItems() { return null; }
        @Override public int getWeight() { return 1; }
        @Override public RegistryKeySet<Enchantment> getExclusiveWith() { return null; }
        @Override public String translationKey() { return "enchantment.test"; }
        @Override public String getTranslationKey() { return "enchantment.test"; }
    }

    private static <T> T mock(Class<T> type, InvocationHandler handler) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (instance, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> type.getSimpleName() + " mock";
                    case "hashCode" -> System.identityHashCode(instance);
                    case "equals" -> instance == args[0];
                    default -> null;
                };
            }
            return handler.invoke(instance, method, args == null ? new Object[0] : args);
        });
        return type.cast(proxy);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class || !type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }

    private static Object invoke(PizzaNetworkCore plugin, String name, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = PizzaNetworkCore.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(plugin, args);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
