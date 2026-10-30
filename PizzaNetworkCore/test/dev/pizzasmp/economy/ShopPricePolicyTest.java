// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.economy;

public final class ShopPricePolicyTest {
    private static int assertions;

    public static void main(String[] args) {
        check(ShopPricePolicy.coversSellWorth(300.00, 300.00), "equal price and resale worth are safe");
        check(ShopPricePolicy.coversSellWorth(300.01, 300.00), "price above resale worth is safe");
        check(!ShopPricePolicy.coversSellWorth(299.99, 300.00), "price below resale worth is blocked");
        check(!ShopPricePolicy.coversSellWorth(300.00, 300.001), "resale rounds up to the next cent");
        check(!ShopPricePolicy.coversSellWorth(Double.NaN, 1.00), "non-finite purchase price is blocked");
        check(!ShopPricePolicy.coversSellWorth(1.00, Double.POSITIVE_INFINITY), "non-finite resale value is blocked");
        check(!ShopPricePolicy.coversSellWorth(-0.01, 0.00), "negative prices are blocked");
        equal(0.0, ShopPricePolicy.effectiveWorth(-1.0));
        equal(0.0, ShopPricePolicy.effectiveWorth(Double.NaN));
        equal(0.0, ShopPricePolicy.effectiveWorth(Double.POSITIVE_INFINITY));
        equal(0.0, ShopPricePolicy.effectiveWorth(Double.NEGATIVE_INFINITY));
        equal(4.0, ShopPricePolicy.effectiveWorth(4.0));
        equal(300.0, ShopPricePolicy.minimumPurchasePrice(200.0, 300.0));
        equal(305.0, ShopPricePolicy.minimumPurchasePrice(305.0, 300.0));
        equal(300.01, ShopPricePolicy.minimumPurchasePrice(299.999, 300.001));
        equal(-1.0, ShopPricePolicy.minimumPurchasePrice(Double.NaN, 2.0));
        equal(-1.0, ShopPricePolicy.minimumPurchasePrice(2.0, Double.POSITIVE_INFINITY));
        equal(0.36, ShopPricePolicy.minimumBundlePrice(0.34, 0.113, 3));
        equal(0.36, ShopPricePolicy.minimumBundlePrice(0.36, 0.113, 3));
        equal(-1.0, ShopPricePolicy.minimumBundlePrice(0.36, 0.113, 0));
        System.out.println("Shop price floor: " + assertions + " assertions passed");
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    private static void equal(double expected, double actual) {
        assertions++;
        if (Double.compare(expected, actual) != 0) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
