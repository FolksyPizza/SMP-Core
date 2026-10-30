// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.economy;

import java.util.Arrays;

/** Failure controls for all-or-restore inventory batch delivery. */
public final class SafeInventoryDeliveryTest {
    private static int assertions;

    public static void main(String[] args) {
        int[] success = {63, 0};
        check(SafeInventoryDelivery.applyOrRestore(new int[]{63, 0}, () -> {
            success[0] = 64;
            return true;
        }, before -> System.arraycopy(before, 0, success, 0, before.length)),
            "complete insertion reports success");
        equal(new int[]{64, 0}, success, "complete insertion remains applied");

        int[] overflow = {63, 0};
        check(!SafeInventoryDelivery.applyOrRestore(new int[]{63, 0}, () -> {
            overflow[0] = 64;
            return false;
        }, before -> System.arraycopy(before, 0, overflow, 0, before.length)),
            "partial overflow is rejected");
        equal(new int[]{63, 0}, overflow, "partial insertion restores the original slots");

        int[] throwing = {63, 0};
        check(!SafeInventoryDelivery.applyOrRestore(new int[]{63, 0}, () -> {
            throwing[0] = 64;
            throw new IllegalStateException("injected partial add failure");
        }, before -> System.arraycopy(before, 0, throwing, 0, before.length)),
            "an add exception is rejected after rollback");
        equal(new int[]{63, 0}, throwing, "exception rollback removes the partial grant");
        System.out.println("Safe inventory delivery: " + assertions + " assertions passed");
    }

    private static void equal(int[] expected, int[] actual, String message) {
        check(Arrays.equals(expected, actual), message + ": expected " + Arrays.toString(expected)
            + " got " + Arrays.toString(actual));
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
