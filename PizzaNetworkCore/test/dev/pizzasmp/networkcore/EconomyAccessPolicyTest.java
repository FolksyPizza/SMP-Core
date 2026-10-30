// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.networkcore;

import java.util.List;
import java.util.UUID;

public final class EconomyAccessPolicyTest {
    private static int assertions;
    private static final UUID OWNER = UUID.fromString("1159b163-b2e1-4d1c-a9e2-ffa82b95a16e");

    public static void main(String[] args) {
        EconomyAccessPolicy ownerOnly = new EconomyAccessPolicy(List.of(OWNER.toString()));
        check(ownerOnly.configured(), "owner allowlist is active");
        check(ownerOnly.allows(OWNER), "configured owner can use the economy");
        check(ownerOnly.allows(UUID.randomUUID(), false, false) == false, "ordinary player remains denied");
        check(ownerOnly.allows(UUID.randomUUID(), true, false), "server operator can use the economy");
        check(ownerOnly.allows(UUID.randomUUID(), false, true), "admin economy permission can use the economy");
        check(ownerOnly.allows(OWNER, false, false), "owner UUID remains allowed without staff permissions");
        check(!ownerOnly.allows(UUID.randomUUID()), "other players are denied");
        check(!ownerOnly.allows(null), "missing identity is denied");

        EconomyAccessPolicy empty = new EconomyAccessPolicy(List.of());
        check(!empty.configured() && !empty.allows(OWNER), "empty allowlist denies everyone");
        EconomyAccessPolicy malformed = new EconomyAccessPolicy(List.of(OWNER.toString(), "not-a-uuid"));
        check(!malformed.configured() && !malformed.allows(OWNER), "malformed allowlist fails closed as a whole");
        EconomyAccessPolicy nonCanonical = new EconomyAccessPolicy(List.of("1159b163b2e14d1ca9e2ffa82b95a16e"));
        check(!nonCanonical.configured(), "non-canonical UUID formatting fails closed");

        System.out.println("Economy access policy: " + assertions + " assertions passed");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
