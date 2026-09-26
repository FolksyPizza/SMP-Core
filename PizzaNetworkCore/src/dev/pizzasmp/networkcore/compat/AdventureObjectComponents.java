package dev.pizzasmp.networkcore.compat;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

import java.lang.reflect.Method;
import java.util.UUID;

/** Optional Adventure object components added after the plugin's minimum API version. */
public final class AdventureObjectComponents {
    private static final Accessors ACCESSORS = Accessors.load();

    private AdventureObjectComponents() {
    }

    public static Component playerHead(String playerName) {
        return ACCESSORS.invoke(ACCESSORS.playerHeadByName(), playerName);
    }

    public static Component playerHead(UUID playerId) {
        return ACCESSORS.invoke(ACCESSORS.playerHeadById(), playerId);
    }

    public static Component sprite(Key item, Key texture) {
        return ACCESSORS.invoke(ACCESSORS.sprite(), item, texture);
    }

    private record Accessors(Method componentObject, Method playerHeadByName, Method playerHeadById,
                             Method sprite) {
        private static Accessors load() {
            try {
                Class<?> objectContents = Class.forName(
                    "net.kyori.adventure.text.object.ObjectContents", false, Component.class.getClassLoader());
                return new Accessors(
                    Component.class.getMethod("object", objectContents),
                    objectContents.getMethod("playerHead", String.class),
                    objectContents.getMethod("playerHead", UUID.class),
                    objectContents.getMethod("sprite", Key.class, Key.class));
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return new Accessors(null, null, null, null);
            }
        }

        private Component invoke(Method objectFactory, Object... arguments) {
            if (objectFactory == null || componentObject == null) {
                return null;
            }
            try {
                Object contents = objectFactory.invoke(null, arguments);
                return (Component) componentObject.invoke(null, contents);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return null;
            }
        }
    }
}
