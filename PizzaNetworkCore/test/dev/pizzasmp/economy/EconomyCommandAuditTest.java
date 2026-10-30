// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.economy;

public final class EconomyCommandAuditTest {
    private static int assertions;

    public static void main(String[] args) {
        equal("/login <credentials redacted>", EconomyCommandAudit.redact("/login Alex super-secret"));
        equal("/essentials:register <credentials redacted>",
            EconomyCommandAudit.redact("/essentials:register Alex hunter2 hunter2"));
        equal("/msg Alex <message redacted>", EconomyCommandAudit.redact("/msg Alex please reset my password"));
        equal("/reply <message redacted>", EconomyCommandAudit.redact("/reply private reply body"));
        equal("/pay Alex 50", EconomyCommandAudit.redact("/pay Alex 50"));
        equal("say hello world", EconomyCommandAudit.redact("say hello world"));
        equal("", EconomyCommandAudit.redact("   "));
        String longCommand = "say " + "x".repeat(700);
        check(EconomyCommandAudit.redact(longCommand).length() == 500, "unredacted command is bounded");
        check(!EconomyCommandAudit.redact("/login Alex super-secret").contains("super-secret"),
            "credential never reaches audit output");
        check(!EconomyCommandAudit.redact("/msg Alex private body").contains("private body"),
            "private message body never reaches audit output");
        System.out.println("Economy command audit: " + assertions + " assertions passed");
    }

    private static void equal(String expected, String actual) {
        assertions++;
        if (!expected.equals(actual)) throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
