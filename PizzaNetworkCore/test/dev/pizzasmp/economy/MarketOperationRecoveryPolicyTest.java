// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.economy;

import java.math.BigDecimal;
import java.util.UUID;

/** Failure-path decision checks for PNC journal versus Canopy's durable pre/post receipt. */
public final class MarketOperationRecoveryPolicyTest {
    private static int assertions;
    private static final UUID OPERATION = new UUID(0, 1);
    private static final UUID PLAYER = new UUID(0, 2);
    private static final long PRE_REVISION = 3;
    private static final long POST_REVISION = 4;
    private static final String PRE_DIGEST = "c".repeat(64);
    private static final String POST_DIGEST = "d".repeat(64);

    public static void main(String[] args) {
        var post = offer(InventoryCustody.RecoveryStage.POST_AVAILABLE, true, true);
        var pre = offer(InventoryCustody.RecoveryStage.PRE_ONLY, true, false);
        var begin = offer(InventoryCustody.RecoveryStage.BEGIN_ONLY, false, false);
        var committed = offer(InventoryCustody.RecoveryStage.COMMITTED, true, true);
        var aborted = offer(InventoryCustody.RecoveryStage.ABORTED, true, false);

        expect(InventoryCustody.RecoveryStage.POST_AVAILABLE, MarketOperationJournal.Phase.FUNDS_RESERVED, null,
            MarketOperationRecoveryPolicy.Action.RESTORE_POST_AND_SETTLE, post);
        expect(InventoryCustody.RecoveryStage.POST_AVAILABLE, MarketOperationJournal.Phase.ITEM_CHECKPOINTED, null,
            MarketOperationRecoveryPolicy.Action.RESTORE_POST_AND_SETTLE, post);
        expect(InventoryCustody.RecoveryStage.POST_AVAILABLE, MarketOperationJournal.Phase.SETTLED, null,
            MarketOperationRecoveryPolicy.Action.RESTORE_POST_AND_SETTLE, post);
        var pvpOperation = operation(MarketOperationJournal.Phase.FUNDS_RESERVED, null, "PVP_PURCHASE");
        check(MarketOperationRecoveryPolicy.decide(pvpOperation, post).action()
            == MarketOperationRecoveryPolicy.Action.RESTORE_POST_AND_SETTLE, "PVP catalog purchase uses the same recovery decision table");
        var auctionBuy = operation(MarketOperationJournal.Phase.FUNDS_RESERVED, null, "AUCTION_BUY");
        check(MarketOperationRecoveryPolicy.decide(auctionBuy, post).action()
            == MarketOperationRecoveryPolicy.Action.RESTORE_POST_AND_SETTLE, "auction buy uses the custody recovery decision table");
        var auctionList = operation(MarketOperationJournal.Phase.FUNDS_RESERVED, null, "AUCTION_LIST");
        check(MarketOperationRecoveryPolicy.decide(auctionList, post).action()
            == MarketOperationRecoveryPolicy.Action.RESTORE_POST_AND_SETTLE, "auction listing uses the custody recovery decision table");
        var auctionCancel = operation(MarketOperationJournal.Phase.FUNDS_RESERVED, null, "AUCTION_CANCEL");
        check(MarketOperationRecoveryPolicy.decide(auctionCancel, post).action()
            == MarketOperationRecoveryPolicy.Action.RESTORE_POST_AND_SETTLE, "auction cancellation uses the custody recovery decision table");
        expect(InventoryCustody.RecoveryStage.POST_AVAILABLE, MarketOperationJournal.Phase.REVIEW,
            MarketOperationJournal.Phase.FUNDS_RESERVED, MarketOperationRecoveryPolicy.Action.RESTORE_POST_AND_SETTLE, post);
        expect(InventoryCustody.RecoveryStage.POST_AVAILABLE, MarketOperationJournal.Phase.REVIEW,
            MarketOperationJournal.Phase.ITEM_CHECKPOINTED, MarketOperationRecoveryPolicy.Action.RESTORE_POST_AND_SETTLE, post);
        expect(InventoryCustody.RecoveryStage.POST_AVAILABLE, MarketOperationJournal.Phase.REVIEW,
            MarketOperationJournal.Phase.PREPARED, MarketOperationRecoveryPolicy.Action.HOLD_FOR_REVIEW, post);

        expect(InventoryCustody.RecoveryStage.PRE_ONLY, MarketOperationJournal.Phase.PREPARED, null,
            MarketOperationRecoveryPolicy.Action.RESTORE_PRE_AND_ABORT, pre);
        expect(InventoryCustody.RecoveryStage.PRE_ONLY, MarketOperationJournal.Phase.LEASED, null,
            MarketOperationRecoveryPolicy.Action.RESTORE_PRE_AND_ABORT, pre);
        expect(InventoryCustody.RecoveryStage.PRE_ONLY, MarketOperationJournal.Phase.FUNDS_RESERVED, null,
            MarketOperationRecoveryPolicy.Action.RESTORE_PRE_AND_ABORT, pre);
        expect(InventoryCustody.RecoveryStage.BEGIN_ONLY, MarketOperationJournal.Phase.FUNDS_RESERVED, null,
            MarketOperationRecoveryPolicy.Action.HOLD_FOR_REVIEW, begin);
        expect(InventoryCustody.RecoveryStage.BEGIN_ONLY, MarketOperationJournal.Phase.PREPARED, null,
            MarketOperationRecoveryPolicy.Action.RESTORE_PRE_AND_ABORT, begin);
        expect(InventoryCustody.RecoveryStage.PRE_ONLY, MarketOperationJournal.Phase.REVIEW,
            MarketOperationJournal.Phase.FUNDS_RESERVED, MarketOperationRecoveryPolicy.Action.RESTORE_PRE_AND_ABORT, pre);
        expect(InventoryCustody.RecoveryStage.PRE_ONLY, MarketOperationJournal.Phase.ITEM_CHECKPOINTED, null,
            MarketOperationRecoveryPolicy.Action.HOLD_FOR_REVIEW, pre);
        expect(InventoryCustody.RecoveryStage.PRE_ONLY, MarketOperationJournal.Phase.SETTLED, null,
            MarketOperationRecoveryPolicy.Action.HOLD_FOR_REVIEW, pre);

        expect(InventoryCustody.RecoveryStage.COMMITTED, MarketOperationJournal.Phase.SETTLED, null,
            MarketOperationRecoveryPolicy.Action.COMPLETE_COMMIT, committed);
        expect(InventoryCustody.RecoveryStage.ABORTED, MarketOperationJournal.Phase.REFUNDED, null,
            MarketOperationRecoveryPolicy.Action.COMPLETE_ABORT, aborted);
        var committedWithoutSnapshots = offer(InventoryCustody.RecoveryStage.COMMITTED, false, false);
        var abortedWithoutSnapshots = offer(InventoryCustody.RecoveryStage.ABORTED, false, false);
        check(MarketOperationRecoveryPolicy.decide(
                operation(MarketOperationJournal.Phase.SETTLED, null, "SHOP_PURCHASE"), committedWithoutSnapshots).action()
                == MarketOperationRecoveryPolicy.Action.COMPLETE_COMMIT,
            "terminal commit receipt may omit snapshots");
        check(MarketOperationRecoveryPolicy.decide(
                operation(MarketOperationJournal.Phase.REFUNDED, null, "SHOP_PURCHASE"), abortedWithoutSnapshots).action()
                == MarketOperationRecoveryPolicy.Action.COMPLETE_ABORT,
            "terminal abort receipt may omit snapshots");
        expect(InventoryCustody.RecoveryStage.COMMITTED, MarketOperationJournal.Phase.FUNDS_RESERVED, null,
            MarketOperationRecoveryPolicy.Action.HOLD_FOR_REVIEW, committed);
        expect(InventoryCustody.RecoveryStage.ABORTED, MarketOperationJournal.Phase.SETTLED, null,
            MarketOperationRecoveryPolicy.Action.HOLD_FOR_REVIEW, aborted);

        var wrongPlayer = new InventoryCustody.RecoveryOffer(OPERATION, new UUID(0, 999), new UUID(0, 3), "limbo",
            new UUID(0, 4), 5, InventoryCustody.RecoveryStage.POST_AVAILABLE,
            PRE_REVISION, PRE_DIGEST, POST_REVISION, POST_DIGEST);
        check(MarketOperationRecoveryPolicy.decide(operation(MarketOperationJournal.Phase.FUNDS_RESERVED, null, "SHOP_PURCHASE"), wrongPlayer).action()
            == MarketOperationRecoveryPolicy.Action.HOLD_FOR_REVIEW, "wrong player stays held");

        var wrongPreRevision = offer(InventoryCustody.RecoveryStage.POST_AVAILABLE,
            PRE_REVISION + 1, PRE_DIGEST, POST_REVISION + 1, POST_DIGEST);
        check(MarketOperationRecoveryPolicy.decide(
                operation(MarketOperationJournal.Phase.FUNDS_RESERVED, null, "SHOP_PURCHASE"), wrongPreRevision).action()
                == MarketOperationRecoveryPolicy.Action.HOLD_FOR_REVIEW,
            "different persisted pre revision stays held");
        var wrongPreDigest = offer(InventoryCustody.RecoveryStage.PRE_ONLY,
            PRE_REVISION, "e".repeat(64), -1, null);
        check(MarketOperationRecoveryPolicy.decide(
                operation(MarketOperationJournal.Phase.REVIEW, MarketOperationJournal.Phase.FUNDS_RESERVED, "SHOP_PURCHASE"),
                wrongPreDigest).action() == MarketOperationRecoveryPolicy.Action.HOLD_FOR_REVIEW,
            "different persisted pre digest in REVIEW stays held");
        var wrongPostRevision = offer(InventoryCustody.RecoveryStage.POST_AVAILABLE,
            PRE_REVISION, PRE_DIGEST, POST_REVISION + 1, POST_DIGEST);
        check(MarketOperationRecoveryPolicy.decide(
                operation(MarketOperationJournal.Phase.ITEM_CHECKPOINTED, null, "SHOP_PURCHASE"), wrongPostRevision).action()
                == MarketOperationRecoveryPolicy.Action.HOLD_FOR_REVIEW,
            "different persisted post revision stays held");
        var wrongPostDigest = offer(InventoryCustody.RecoveryStage.POST_AVAILABLE,
            PRE_REVISION, PRE_DIGEST, POST_REVISION, "e".repeat(64));
        check(MarketOperationRecoveryPolicy.decide(
                operation(MarketOperationJournal.Phase.REVIEW, MarketOperationJournal.Phase.ITEM_CHECKPOINTED, "SHOP_PURCHASE"),
                wrongPostDigest).action() == MarketOperationRecoveryPolicy.Action.HOLD_FOR_REVIEW,
            "different persisted post digest in REVIEW stays held");
        check(MarketOperationRecoveryPolicy.decide(
                operation(MarketOperationJournal.Phase.FUNDS_RESERVED, null, "SHOP_PURCHASE", false, false), post).action()
                == MarketOperationRecoveryPolicy.Action.HOLD_FOR_REVIEW,
            "funds-reserved phase without a journaled pre receipt stays held");
        check(MarketOperationRecoveryPolicy.decide(
                operation(MarketOperationJournal.Phase.ITEM_CHECKPOINTED, null, "SHOP_PURCHASE", true, false), post).action()
                == MarketOperationRecoveryPolicy.Action.HOLD_FOR_REVIEW,
            "item-checkpointed phase without a journaled post receipt stays held");

        var wrongKind = operation(MarketOperationJournal.Phase.FUNDS_RESERVED, null, "ORDER_FILL");
        check(MarketOperationRecoveryPolicy.decide(wrongKind, post).action()
                == MarketOperationRecoveryPolicy.Action.RESTORE_POST_AND_SETTLE,
            "order fill uses the same durable item recovery decision");
        for (String kind : java.util.List.of("SHOP_SELL", "ORDER_CLAIM", "MAILBOX_CLAIM")) {
            check(MarketOperationRecoveryPolicy.decide(operation(MarketOperationJournal.Phase.FUNDS_RESERVED, null, kind), post).action()
                    == MarketOperationRecoveryPolicy.Action.RESTORE_POST_AND_SETTLE,
                kind + " uses the durable post-checkpoint recovery decision");
            check(MarketOperationRecoveryPolicy.decide(operation(MarketOperationJournal.Phase.FUNDS_RESERVED, null, kind), pre).action()
                    == MarketOperationRecoveryPolicy.Action.RESTORE_PRE_AND_ABORT,
                kind + " restores the pre-state when no mutation checkpoint exists");
        }
        System.out.println("Market operation recovery policy: " + assertions + " assertions passed");
    }

    private static InventoryCustody.RecoveryOffer offer(InventoryCustody.RecoveryStage stage, boolean hasPre, boolean hasPost) {
        return offer(stage, hasPre ? PRE_REVISION : -1, hasPre ? PRE_DIGEST : null,
            hasPost ? POST_REVISION : -1, hasPost ? POST_DIGEST : null);
    }
    private static InventoryCustody.RecoveryOffer offer(InventoryCustody.RecoveryStage stage, long preRevision,
            String preDigest, long postRevision, String postDigest) {
        // Recovery is rebound to the currently authenticated backend connection and stay.
        // The original PNC lease connection/backend are intentionally different.
        return new InventoryCustody.RecoveryOffer(OPERATION, PLAYER, new UUID(0, 3), "limbo", new UUID(0, 4), 5,
            stage, preRevision, preDigest, postRevision, postDigest);
    }
    private static MarketOperationJournal.Operation operation(MarketOperationJournal.Phase phase,
            MarketOperationJournal.Phase reviewFrom, String kind) {
        MarketOperationJournal.Phase durablePhase = phase == MarketOperationJournal.Phase.REVIEW ? reviewFrom : phase;
        boolean hasPre = durablePhase == MarketOperationJournal.Phase.LEASED
            || durablePhase == MarketOperationJournal.Phase.FUNDS_RESERVED
            || durablePhase == MarketOperationJournal.Phase.ITEM_CHECKPOINTED
            || durablePhase == MarketOperationJournal.Phase.SETTLED
            || durablePhase == MarketOperationJournal.Phase.REFUNDED
            || durablePhase == MarketOperationJournal.Phase.RELEASED;
        boolean hasPost = durablePhase == MarketOperationJournal.Phase.ITEM_CHECKPOINTED
            || durablePhase == MarketOperationJournal.Phase.SETTLED
            || durablePhase == MarketOperationJournal.Phase.RELEASED;
        return operation(phase, reviewFrom, kind, hasPre, hasPost);
    }
    private static MarketOperationJournal.Operation operation(MarketOperationJournal.Phase phase,
            MarketOperationJournal.Phase reviewFrom, String kind, boolean hasPre, boolean hasPost) {
        var request = new MarketOperationJournal.Request(OPERATION, PLAYER, "RecoveryProbe", kind, "DIAMOND", 1,
            new BigDecimal("AUCTION_CANCEL".equals(kind) ? "0.00" : "1.00"), "main", new byte[]{1},
            "AUCTION_LIST".equals(kind) ? 18 : 0);
        return new MarketOperationJournal.Operation(request, phase, hasPre ? new UUID(0, 5) : null,
            hasPre ? PRE_REVISION : -1, hasPre ? PRE_DIGEST : null,
            hasPost ? POST_REVISION : -1, hasPost ? POST_DIGEST : null,
            null, reviewFrom, false, false, "AUCTION_LIST".equals(kind) ? 18 : 0);
    }
    private static void expect(InventoryCustody.RecoveryStage stage, MarketOperationJournal.Phase phase,
            MarketOperationJournal.Phase reviewFrom, MarketOperationRecoveryPolicy.Action expected,
            InventoryCustody.RecoveryOffer offer) {
        var operation = operation(phase, reviewFrom, "SHOP_PURCHASE");
        check(MarketOperationRecoveryPolicy.decide(operation, offer).action() == expected,
            stage + " with " + phase + "/" + reviewFrom + " selects " + expected);
    }
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
}
