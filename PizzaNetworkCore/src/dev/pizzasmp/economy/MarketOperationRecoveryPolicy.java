// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.economy;

import java.util.Objects;

/** Pure decision table for choosing a journaled purchase's durable Canopy snapshot after reconnect. */
public final class MarketOperationRecoveryPolicy {
    public enum Action { RESTORE_PRE_AND_ABORT, RESTORE_POST_AND_SETTLE, COMPLETE_COMMIT, COMPLETE_ABORT, HOLD_FOR_REVIEW }
    public record Decision(Action action, String reason) {
        public Decision { Objects.requireNonNull(action); Objects.requireNonNull(reason); }
    }

    private MarketOperationRecoveryPolicy() { }

    public static Decision decide(MarketOperationJournal.Operation operation, InventoryCustody.RecoveryOffer offer) {
        if (operation == null || offer == null) return hold("missing_receipt");
        if (!MarketOperationJournal.isJournaledItemKind(operation.request().operationKind())) return hold("unsupported_operation");
        if (!operation.request().operationId().equals(offer.operationId())
                || !operation.request().playerId().equals(offer.playerId())) return hold("binding_mismatch");

        MarketOperationJournal.Phase phase = operation.phase();
        if (phase == MarketOperationJournal.Phase.REVIEW) phase = operation.reviewFrom();
        if (phase == null) return hold("review_phase_missing");

        switch (offer.stage()) {
            case COMMITTED -> {
                return operation.phase() == MarketOperationJournal.Phase.RELEASED
                        || operation.phase() == MarketOperationJournal.Phase.SETTLED
                    ? new Decision(Action.COMPLETE_COMMIT, "gateway_commit_receipt") : hold("commit_without_settlement");
            }
            case ABORTED -> {
                return operation.phase() == MarketOperationJournal.Phase.ABORTED
                        || operation.phase() == MarketOperationJournal.Phase.REFUNDED
                    ? new Decision(Action.COMPLETE_ABORT, "gateway_abort_receipt") : hold("abort_without_refund");
            }
            default -> { }
        }

        // Canopy rebinds an unfinished operation to the player's current backend connection
        // during recovery. Its connection/backend binding can therefore differ from the
        // original PNC lease; the durable pre/post checkpoint receipts remain unchanged.
        // PREPARED (or a REFUNDED row with no lease fields) has no PNC pre-receipt to compare.
        // Canopy may already have durably recorded BEGIN/PRE, but reserve/mutation cannot have started.
        boolean journalHasPreReceipt = operation.backendConnectionId() != null
            || operation.preRevision() >= 0 || operation.preDigest() != null;
        if (requiresPreReceipt(phase) && (operation.backendConnectionId() == null
                || !validReceipt(operation.preRevision(), operation.preDigest()))) {
            return hold("journal_pre_checkpoint_missing");
        }
        if (journalHasPreReceipt && (!offer.hasPreState()
                || !sameReceipt(operation.preRevision(), operation.preDigest(), offer.preRevision(), offer.preDigest()))) {
            return hold("pre_checkpoint_mismatch");
        }

        boolean journalHasPostReceipt = operation.postRevision() >= 0 || operation.postDigest() != null;
        if (requiresPostReceipt(phase) && !validReceipt(operation.postRevision(), operation.postDigest())) {
            return hold("journal_post_checkpoint_missing");
        }
        if (journalHasPostReceipt && (!offer.hasPostState()
                || !sameReceipt(operation.postRevision(), operation.postDigest(), offer.postRevision(), offer.postDigest()))) {
            return hold("post_checkpoint_mismatch");
        }

        if (offer.hasPostState()) {
            if (phase == MarketOperationJournal.Phase.FUNDS_RESERVED
                    || phase == MarketOperationJournal.Phase.ITEM_CHECKPOINTED
                    || phase == MarketOperationJournal.Phase.SETTLED) {
                if (offer.stage() == InventoryCustody.RecoveryStage.PRE_ONLY
                        || offer.stage() == InventoryCustody.RecoveryStage.BEGIN_ONLY
                        || offer.stage() == InventoryCustody.RecoveryStage.ABORT_PENDING) return hold("post_stage_mismatch");
                return new Decision(Action.RESTORE_POST_AND_SETTLE, "durable_post_checkpoint");
            }
            return hold("post_without_funds_reservation");
        }

        if ((phase == MarketOperationJournal.Phase.LEASED || phase == MarketOperationJournal.Phase.FUNDS_RESERVED)
                && !offer.hasPreState()) return hold("leased_operation_missing_pre_state");
        if (phase == MarketOperationJournal.Phase.PREPARED || phase == MarketOperationJournal.Phase.LEASED
                || phase == MarketOperationJournal.Phase.FUNDS_RESERVED || phase == MarketOperationJournal.Phase.REFUNDED) {
            if (offer.stage() == InventoryCustody.RecoveryStage.POST_AVAILABLE
                    || offer.stage() == InventoryCustody.RecoveryStage.COMMIT_PENDING) return hold("missing_post_snapshot");
            return new Decision(Action.RESTORE_PRE_AND_ABORT, "no_post_checkpoint");
        }
        return hold("pre_state_cannot_reverse_operation");
    }

    private static boolean requiresPreReceipt(MarketOperationJournal.Phase phase) {
        return phase == MarketOperationJournal.Phase.LEASED || phase == MarketOperationJournal.Phase.FUNDS_RESERVED
            || phase == MarketOperationJournal.Phase.ITEM_CHECKPOINTED || phase == MarketOperationJournal.Phase.SETTLED;
    }

    private static boolean requiresPostReceipt(MarketOperationJournal.Phase phase) {
        return phase == MarketOperationJournal.Phase.ITEM_CHECKPOINTED || phase == MarketOperationJournal.Phase.SETTLED;
    }

    private static boolean validReceipt(long revision, String digest) {
        return revision >= 0 && digest != null && digest.matches("[a-fA-F0-9]{64}");
    }

    private static boolean sameReceipt(long savedRevision, String savedDigest, long offeredRevision, String offeredDigest) {
        return validReceipt(savedRevision, savedDigest) && validReceipt(offeredRevision, offeredDigest)
            && savedRevision == offeredRevision && savedDigest.equalsIgnoreCase(offeredDigest);
    }

    private static Decision hold(String reason) { return new Decision(Action.HOLD_FOR_REVIEW, reason); }
}
