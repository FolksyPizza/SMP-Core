// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.economy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.util.UUID;

public final class OrderCreatePayloadTest {
    private static int assertions;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000001");

    public static void main(String[] args) throws Exception {
        byte[] valid = payload(0x4f524431, "2.50", 18, false);
        MarketOperationJournal.Request request = request("200.00", 80, valid);
        MarketOperationJournal.OrderCreateTerms terms = MarketOperationJournal.readOrderCreateTerms(request);
        equal(new BigDecimal("2.50"), terms.unitPrice(), "order unit price is exact cents");
        equal(18, terms.maxOpenOrders(), "seller order cap is request-bound");
        byte[] badMagic = payload(0, "2.50", 18, false);
        byte[] badCap = payload(0x4f524431, "2.50", 0, false);
        byte[] fractional = payload(0x4f524431, "2.501", 18, false);
        byte[] trailing = payload(0x4f524431, "2.50", 18, true);
        rejects(() -> MarketOperationJournal.readOrderCreateTerms(request("199.99", 80, valid)), "escrow mismatch is rejected");
        rejects(() -> MarketOperationJournal.readOrderCreateTerms(request("200.00", 80, badMagic)),
            "wrong payload version is rejected");
        rejects(() -> MarketOperationJournal.readOrderCreateTerms(request("200.00", 80, badCap)),
            "invalid cap is rejected");
        rejects(() -> MarketOperationJournal.readOrderCreateTerms(request("200.00", 80, fractional)),
            "fractional-cent unit price is rejected");
        rejects(() -> MarketOperationJournal.readOrderCreateTerms(request("200.00", 80, trailing)),
            "trailing bytes are rejected");
        System.out.println("Order create payload: " + assertions + " assertions passed");
    }

    private static byte[] payload(int magic, String unit, int cap, boolean trailing) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(magic); out.writeUTF(unit); out.writeInt(cap);
            if (trailing) out.writeByte(1);
        }
        return bytes.toByteArray();
    }

    private static MarketOperationJournal.Request request(String total, int amount, byte[] payload) {
        return new MarketOperationJournal.Request(UUID.randomUUID(), PLAYER, "FixturePlayer", "ORDER_CREATE",
            "DIAMOND", amount, new BigDecimal(total), "main", payload);
    }

    private static void rejects(Runnable action, String message) {
        assertions++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        assertions++;
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + " got " + actual);
    }
}
