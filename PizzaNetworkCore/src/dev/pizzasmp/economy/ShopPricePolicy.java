// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Price floor policy for immediate server resale; unknown/invalid prices must not authorize a grant. */
public final class ShopPricePolicy {
    private ShopPricePolicy() { }

    /** Compare cent-denominated charges conservatively against the sale payout rounded up to a cent. */
    public static boolean coversSellWorth(double purchasePrice, double sellWorth) {
        if (!Double.isFinite(purchasePrice) || !Double.isFinite(sellWorth)
                || purchasePrice < 0.0 || sellWorth < 0.0) return false;
        try {
            BigDecimal charge = BigDecimal.valueOf(purchasePrice).setScale(2, RoundingMode.DOWN);
            BigDecimal payout = BigDecimal.valueOf(sellWorth).setScale(2, RoundingMode.CEILING);
            return charge.compareTo(payout) >= 0;
        } catch (ArithmeticException invalid) {
            return false;
        }
    }

    /** Missing, negative, or non-finite catalog entries have zero sale value and cannot be sold. */
    public static double effectiveWorth(double configuredValue) {
        return Double.isFinite(configuredValue) && configuredValue > 0.0 ? configuredValue : 0.0;
    }

    /**
     * Minimum displayed and charged amount for a purchase. Invalid configured prices fail closed;
     * valid prices are rounded up to cents and raised to at least the immediate resale payout.
     */
    public static double minimumPurchasePrice(double configuredPrice, double sellWorth) {
        if (!Double.isFinite(configuredPrice) || !Double.isFinite(sellWorth)
                || configuredPrice < 0.0 || sellWorth < 0.0) return -1.0;
        try {
            BigDecimal configured = BigDecimal.valueOf(configuredPrice).setScale(2, RoundingMode.CEILING);
            BigDecimal payout = BigDecimal.valueOf(sellWorth).setScale(2, RoundingMode.CEILING);
            return configured.max(payout).doubleValue();
        } catch (ArithmeticException invalid) {
            return -1.0;
        }
    }

    /** Floor a bundle by the cent-rounded resale value of every individual item in it. */
    public static double minimumBundlePrice(double configuredPrice, double unitSellWorth, int amount) {
        if (amount < 1 || !Double.isFinite(unitSellWorth) || unitSellWorth < 0.0) return -1.0;
        try {
            BigDecimal perItem = BigDecimal.valueOf(unitSellWorth).setScale(2, RoundingMode.CEILING);
            return minimumPurchasePrice(configuredPrice, perItem.multiply(BigDecimal.valueOf(amount)).doubleValue());
        } catch (ArithmeticException invalid) {
            return -1.0;
        }
    }
}
