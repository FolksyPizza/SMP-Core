// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.economy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class ShopSellAuditPayloadTest {
    private static int assertions;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000001");

    public static void main(String[] args) throws Exception {
        byte[] payload = payload(0x53534c31, false, 0);
        MarketOperationJournal.Request request = request("12.50", 48, payload);
        List<MarketOperationJournal.SellAuditLine> lines = MarketOperationJournal.decodeShopSellPayload(request);
        equal(2, lines.size(), "mixed sell captures two stack audit lines");
        equal("DIAMOND", lines.get(0).itemKey(), "first material is retained");
        equal(16, lines.get(0).amount(), "first stack amount is retained");
        equal(new BigDecimal("0.25"), lines.get(0).unitPrice(), "first worth is retained");
        equal("IRON_INGOT", lines.get(1).itemKey(), "second material is retained");
        equal(32, lines.get(1).amount(), "second stack amount is retained");
        equal(new BigDecimal("0.265625"), lines.get(1).unitPrice(), "fractional worth is retained");

        byte[] badMagic = payload(0, false, 0);
        byte[] trailing = payload(0x53534c31, true, 0);
        byte[] badSlot = payload(0x53534c31, false, 36);
        rejects(() -> MarketOperationJournal.decodeShopSellPayload(request("12.49", 48, payload)),
            "total mismatch is rejected before credit");
        rejects(() -> MarketOperationJournal.decodeShopSellPayload(request("12.50", 47, payload)),
            "item-count mismatch is rejected before credit");
        rejects(() -> MarketOperationJournal.decodeShopSellPayload(request("12.50", 48, badMagic)),
            "wrong payload version is rejected");
        rejects(() -> MarketOperationJournal.decodeShopSellPayload(request("12.50", 48, trailing)),
            "trailing payload bytes are rejected");
        rejects(() -> MarketOperationJournal.decodeShopSellPayload(request("12.50", 48, badSlot)),
            "out-of-range inventory slot is rejected");
        MarketOperationJournal.Request wrongKind = new MarketOperationJournal.Request(UUID.randomUUID(), PLAYER,
            "FixturePlayer", "SHOP_PURCHASE", "DIAMOND", 48, new BigDecimal("12.50"), "main", payload);
        rejects(() -> MarketOperationJournal.decodeShopSellPayload(wrongKind), "non-sale request cannot use the sell decoder");

        System.out.println("Shop sell audit payload: " + assertions + " assertions passed");
    }

    private static byte[] payload(int magic, boolean trailing, int firstSlot) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(magic);
            out.writeInt(2);
            line(out, firstSlot, "DIAMOND", 16, "0.25", 1);
            line(out, 1, "IRON_INGOT", 32, "0.265625", 2);
            if (trailing) out.writeByte(99);
        }
        return bytes.toByteArray();
    }

    private static void line(DataOutputStream out, int slot, String material, int amount, String unitWorth, int data) throws Exception {
        out.writeInt(slot);
        out.writeUTF(material);
        out.writeInt(amount);
        out.writeUTF(unitWorth);
        out.writeInt(1);
        out.writeByte(data);
    }

    private static MarketOperationJournal.Request request(String total, int count, byte[] payload) {
        return new MarketOperationJournal.Request(UUID.randomUUID(), PLAYER, "FixturePlayer", "SHOP_SELL", "MIXED",
            count, new BigDecimal(total), "main", payload);
    }

    private static void rejects(Runnable action, String message) {
        assertions++;
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        assertions++;
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + " got " + actual);
    }
}
