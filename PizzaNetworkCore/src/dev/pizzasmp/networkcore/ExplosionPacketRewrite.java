// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.networkcore;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;

/**
 * Rewrites the 1.21.2+ explosion packet (a record of center, radius, block count, player knockback, particle, sound
 * and block particles) with its center far below the world: the client then draws no particle, plays no audible
 * sound and spawns no block particles, while the knockback, which is relative to the player, still applies. A new
 * packet is built because one packet object can be sent to several players.
 */
final class ExplosionPacketRewrite {
    /** Far enough below the viewer that particles are culled and the sound has fully attenuated. */
    private static final double HIDE_DEPTH = 4096.0;

    record Rewritten(Object packet, double x, double y, double z, boolean large) { }

    private static final Constructor<?> PACKET;
    private static final Method[] COMPONENTS;
    private static final Constructor<?> VEC3;
    private static final Method VEC_X, VEC_Y, VEC_Z;
    private static final int CENTER, PARTICLE;

    static {
        Constructor<?> packet = null, vec3 = null;
        Method[] components = null;
        Method x = null, y = null, z = null;
        int center = -1, particle = -1;
        try {
            Class<?> type = Class.forName("net.minecraft.network.protocol.game.ClientboundExplodePacket");
            RecordComponent[] parts = type.getRecordComponents();
            Class<?>[] types = new Class<?>[parts.length];
            components = new Method[parts.length];
            for (int i = 0; i < parts.length; i++) {
                types[i] = parts[i].getType();
                components[i] = parts[i].getAccessor();
                components[i].setAccessible(true);
                if (parts[i].getName().equals("center")) center = i;
                if (parts[i].getName().equals("explosionParticle")) particle = i;
            }
            packet = type.getDeclaredConstructor(types);
            packet.setAccessible(true);
            Class<?> vec = Class.forName("net.minecraft.world.phys.Vec3");
            vec3 = vec.getConstructor(double.class, double.class, double.class);
            x = vec.getMethod("x");
            y = vec.getMethod("y");
            z = vec.getMethod("z");
        } catch (ReflectiveOperationException | RuntimeException unsupported) {
            packet = null;
        }
        PACKET = center >= 0 ? packet : null;
        COMPONENTS = components;
        VEC3 = vec3;
        VEC_X = x; VEC_Y = y; VEC_Z = z;
        CENTER = center;
        PARTICLE = particle;
    }

    private ExplosionPacketRewrite() { }

    static boolean supported() { return PACKET != null; }

    /** The packet with its effects hidden, and where they were; null when the packet is not the expected type. */
    static Rewritten hideEffects(Object handle) {
        if (PACKET == null || handle == null || !PACKET.getDeclaringClass().isInstance(handle)) return null;
        try {
            Object[] values = new Object[COMPONENTS.length];
            for (int i = 0; i < values.length; i++) values[i] = COMPONENTS[i].invoke(handle);
            Object center = values[CENTER];
            double cx = (double) VEC_X.invoke(center), cy = (double) VEC_Y.invoke(center), cz = (double) VEC_Z.invoke(center);
            values[CENTER] = VEC3.newInstance(cx, cy - HIDE_DEPTH, cz);
            boolean large = PARTICLE >= 0 && String.valueOf(values[PARTICLE]).toLowerCase(java.util.Locale.ROOT).contains("emitter");
            return new Rewritten(PACKET.newInstance(values), cx, cy, cz, large);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }
}
