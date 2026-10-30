// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.economy;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Orders one player item mutation around the core journal and Canopy's acknowledged inventory lease. */
public final class MarketOperationCoordinator {
    public enum Outcome { COMPLETED, REJECTED, PENDING_REVIEW, BUSY }
    public record Result(Outcome outcome, String reason) {
        public Result { Objects.requireNonNull(outcome); Objects.requireNonNull(reason); }
        public boolean terminal() { return outcome == Outcome.COMPLETED || outcome == Outcome.REJECTED; }
    }
    public record Mutation(boolean started, boolean complete, String failureCode) {
        public Mutation {
            if (complete && !started) throw new IllegalArgumentException("completed mutation must have started");
            if (failureCode != null && !failureCode.matches("[a-z0-9_]{1,64}")) throw new IllegalArgumentException("failureCode");
        }
    }

    /** Adapter implemented by PNC at the player scheduler boundary. */
    public interface CustodyFlow {
        boolean qualified();
        CompletionStage<InventoryCustody.Lease> begin(MarketOperationJournal.Request request);
        CompletionStage<Mutation> applyItems(MarketOperationJournal.Request request, InventoryCustody.Lease lease);
        CompletionStage<InventoryCustody.CheckpointReceipt> checkpoint(InventoryCustody.Lease lease);
        CompletionStage<Void> finish(InventoryCustody.Lease lease);
        CompletionStage<Void> abortUntouched(InventoryCustody.Lease lease);
        CompletionStage<Void> forgetTerminal(UUID operationId, boolean committed);
    }

    private final MarketOperationJournal journal;
    private final Executor databaseExecutor;
    private final ConcurrentHashMap<UUID, UUID> activeByPlayer = new ConcurrentHashMap<>();

    public MarketOperationCoordinator(MarketOperationJournal journal, Executor databaseExecutor) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
    }

    public CompletionStage<Result> purchaseShop(MarketOperationJournal.Request request, CustodyFlow custody) {
        return executeItemOperation(request, custody);
    }

    /** Runs a journaled player inventory operation, including a sale that credits only after its post-state checkpoint. */
    public CompletionStage<Result> executeItemOperation(MarketOperationJournal.Request request, CustodyFlow custody) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(custody, "custody");
        if (!MarketOperationJournal.isJournaledItemKind(request.operationKind())) return completed(Outcome.REJECTED, "wrong_operation_kind");
        if (!custody.qualified()) return completed(Outcome.REJECTED, "custody_unqualified");
        UUID player = request.playerId();
        if (activeByPlayer.putIfAbsent(player, request.operationId()) != null) return completed(Outcome.BUSY, "operation_in_progress");

        CompletableFuture<Result> result = new CompletableFuture<>();
        db(() -> journal.prepare(request)).whenComplete((prepared, failure) -> {
            if (failure != null || prepared == MarketOperationJournal.Status.UNKNOWN) {
                db(() -> journal.find(request.operationId())).whenComplete((existing, readFailure) -> {
                    if (readFailure == null && existing == null) completeResult(Outcome.REJECTED, "database_unavailable", result);
                    else pending("prepare_unknown", result);
                });
                return;
            }
            if (prepared == MarketOperationJournal.Status.DUPLICATE) {
                db(() -> journal.find(request.operationId())).whenComplete((existing, readFailure) -> {
                    if (readFailure != null || existing == null) pending("duplicate_state_unknown", result);
                    else switch (existing.phase()) {
                        case RELEASED -> completeResult(Outcome.COMPLETED, "already_released", result);
                        case ABORTED -> completeResult(Outcome.REJECTED, existing.detail() == null ? "previously_aborted" : existing.detail(), result);
                        default -> pending("existing_operation_unresolved", result);
                    }
                });
                return;
            }
            if (prepared != MarketOperationJournal.Status.APPLIED) {
                completeResult(Outcome.REJECTED, "request_conflict", result);
                return;
            }
            db(() -> journal.hasOtherUnresolvedForPlayer(player, request.operationId())).whenComplete((other, fenceFailure) -> {
                if (fenceFailure != null) { pending("player_fence_unknown", result); return; }
                if (Boolean.TRUE.equals(other)) {
                    abortBeforeLease(request, "other_operation_active", result);
                    return;
                }
                begin(request, custody, result);
            });
        });
        return result.whenComplete((finished, failure) -> {
            if (finished != null && finished.terminal()) activeByPlayer.remove(player, request.operationId());
        });
    }

    /** Clear only the in-memory fence matching a durably reconciled terminal operation. */
    public void clearRecoveredFence(UUID playerId, UUID operationId) {
        if (playerId != null && operationId != null) activeByPlayer.remove(playerId, operationId);
    }

    private void begin(MarketOperationJournal.Request request, CustodyFlow custody, CompletableFuture<Result> result) {
        CompletionStage<InventoryCustody.Lease> begin;
        try { begin = custody.begin(request); }
        catch (RuntimeException failure) { abortBeforeLease(request, "custody_begin_rejected", custody, result); return; }
        begin.whenComplete((lease, failure) -> {
            if (failure != null || lease == null) {
                // InventoryCustody documents that exceptional begin means never sent or cancel receipt confirmed.
                abortBeforeLease(request, "custody_begin_rejected", custody, result);
                return;
            }
            if (!request.operationId().equals(lease.operationId()) || !request.playerId().equals(lease.playerId())
                    || !request.backend().equals(lease.backend())) {
                abortLease(request, lease, custody, "lease_binding_mismatch", result);
                return;
            }
            db(() -> journal.markLeased(lease.operationId(), lease.connectionId(), lease.backend(),
                lease.preRevision(), lease.preDigest())).whenComplete((status, leaseFailure) -> {
                if (leaseFailure != null || !applied(status)) {
                    db(() -> journal.find(request.operationId())).whenComplete((current, readFailure) -> {
                        if (readFailure == null && sameLease(current, lease)) reserve(request, lease, custody, result);
                        else abortLease(request, lease, custody, "journal_lease_write_failed", result);
                    });
                } else reserve(request, lease, custody, result);
            });
        });
    }

    private void reserve(MarketOperationJournal.Request request, InventoryCustody.Lease lease,
                         CustodyFlow custody, CompletableFuture<Result> result) {
        db(() -> journal.reserveShopPurchase(lease.operationId())).whenComplete((status, failure) -> {
            if (failure == null && (status == MarketOperationJournal.Status.APPLIED || status == MarketOperationJournal.Status.DUPLICATE)) {
                apply(request, lease, custody, result);
            } else if (failure == null && status == MarketOperationJournal.Status.INSUFFICIENT_FUNDS) {
                abortLease(request, lease, custody, "insufficient_funds", result);
            } else if (failure == null && status == MarketOperationJournal.Status.LIMIT_REACHED) {
                abortLease(request, lease, custody, "listing_limit_reached", result);
            } else {
                db(() -> journal.find(request.operationId())).whenComplete((current, readFailure) -> {
                    if (readFailure == null && current != null && current.phase() == MarketOperationJournal.Phase.FUNDS_RESERVED) {
                        apply(request, lease, custody, result);
                    } else abortLease(request, lease, custody, "reserve_outcome_unknown", result);
                });
            }
        });
    }

    private void apply(MarketOperationJournal.Request request, InventoryCustody.Lease lease,
                       CustodyFlow custody, CompletableFuture<Result> result) {
        CompletionStage<Mutation> mutation;
        try { mutation = custody.applyItems(request, lease); }
        catch (RuntimeException failure) { review(request, "item_mutation_exception", result); return; }
        mutation.whenComplete((applied, failure) -> {
            if (failure != null || applied == null) { review(request, "item_mutation_unknown", result); return; }
            if (!applied.started()) { abortLease(request, lease, custody,
                applied.failureCode() == null ? "pre_mutation_rejected" : applied.failureCode(), result); return; }
            if (!applied.complete()) { review(request,
                applied.failureCode() == null ? "partial_item_mutation" : applied.failureCode(), result); return; }
            checkpoint(request, lease, custody, result);
        });
    }

    private void checkpoint(MarketOperationJournal.Request request, InventoryCustody.Lease lease,
                            CustodyFlow custody, CompletableFuture<Result> result) {
        CompletionStage<InventoryCustody.CheckpointReceipt> checkpoint;
        try { checkpoint = custody.checkpoint(lease); }
        catch (RuntimeException failure) { review(request, "post_checkpoint_unknown", result); return; }
        checkpoint.whenComplete((receipt, failure) -> {
            if (failure != null || receipt == null || !lease.equals(receipt.lease())) {
                review(request, "post_checkpoint_unknown", result);
                return;
            }
            db(() -> journal.markItemCheckpointed(request.operationId(), receipt.revision(), receipt.digest()))
                .whenComplete((status, journalFailure) -> {
                    if (journalFailure != null || !(status == MarketOperationJournal.Status.APPLIED
                            || status == MarketOperationJournal.Status.DUPLICATE)) {
                        db(() -> journal.find(request.operationId())).whenComplete((current, readFailure) -> {
                            if (readFailure == null && current != null
                                    && current.phase() == MarketOperationJournal.Phase.ITEM_CHECKPOINTED
                                    && current.postRevision() == receipt.revision()
                            && receipt.digest().equals(current.postDigest())) settle(request, lease, custody, 0, result);
                            else review(request, "checkpoint_journal_unknown", result);
                        });
                    } else settle(request, lease, custody, 0, result);
                });
        });
    }

    private void settle(MarketOperationJournal.Request request, InventoryCustody.Lease lease,
                        CustodyFlow custody, int retries, CompletableFuture<Result> result) {
        db(() -> journal.settleShopPurchase(request.operationId(), request.backend())).whenComplete((status, failure) -> {
            if (failure == null && (status == MarketOperationJournal.Status.APPLIED || status == MarketOperationJournal.Status.DUPLICATE)) {
                finish(request, lease, custody, 0, result);
                return;
            }
            db(() -> journal.find(request.operationId())).whenComplete((current, readFailure) -> {
                if (readFailure == null && current != null && (current.phase() == MarketOperationJournal.Phase.SETTLED
                        || current.phase() == MarketOperationJournal.Phase.RELEASED)) {
                    finish(request, lease, custody, 0, result);
                } else if (readFailure == null && current != null
                        && current.phase() == MarketOperationJournal.Phase.ITEM_CHECKPOINTED
                        && status == MarketOperationJournal.Status.UNKNOWN && retries < 1) {
                    settle(request, lease, custody, retries + 1, result);
                } else if (readFailure == null && current != null
                        && current.phase() == MarketOperationJournal.Phase.ITEM_CHECKPOINTED
                        && status == MarketOperationJournal.Status.UNKNOWN) {
                    pending("settlement_unknown", result);
                } else review(request, "settlement_unknown", result);
            });
        });
    }

    private void finish(MarketOperationJournal.Request request, InventoryCustody.Lease lease,
                        CustodyFlow custody, int retries, CompletableFuture<Result> result) {
        CompletionStage<Void> finish;
        try { finish = custody.finish(lease); }
        catch (RuntimeException failure) { retryFinish(request, lease, custody, retries, result); return; }
        finish.whenComplete((ignored, failure) -> {
            if (failure != null) { retryFinish(request, lease, custody, retries, result); return; }
            db(() -> journal.markReleased(request.operationId())).whenComplete((status, journalFailure) -> {
                if (journalFailure == null && (status == MarketOperationJournal.Status.APPLIED || status == MarketOperationJournal.Status.DUPLICATE)) {
                    forget(request, custody, true, "released", result);
                    return;
                }
                db(() -> journal.find(request.operationId())).whenComplete((current, readFailure) -> {
                    if (readFailure == null && current != null && current.phase() == MarketOperationJournal.Phase.RELEASED)
                        forget(request, custody, true, "released", result);
                    else completeResult(Outcome.PENDING_REVIEW, "release_receipt_needs_reconciliation", result);
                });
            });
        });
    }

    private void retryFinish(MarketOperationJournal.Request request, InventoryCustody.Lease lease,
                             CustodyFlow custody, int retries, CompletableFuture<Result> result) {
        if (retries < 2) finish(request, lease, custody, retries + 1, result);
        else completeResult(Outcome.PENDING_REVIEW, "finish_receipt_needs_reconciliation", result);
    }

    private void abortLease(MarketOperationJournal.Request request, InventoryCustody.Lease lease,
                            CustodyFlow custody, String reason, CompletableFuture<Result> result) {
        // The flow reports that mutation never started. Refund/terminalize first; only then release Canopy.
        abortBeforeLease(request, reason, result, () -> {
            CompletionStage<Void> abort;
            try { abort = custody.abortUntouched(lease); }
            catch (RuntimeException failure) { pending("abort_receipt_unknown", result); return; }
            abort.whenComplete((ignored, failure) -> {
                if (failure != null) pending("abort_receipt_unknown", result);
                else completeAbort(request, reason, custody, result);
            });
        });
    }

    private void abortBeforeLease(MarketOperationJournal.Request request, String reason, CompletableFuture<Result> result) {
        abortBeforeLease(request, reason, null, result);
    }

    private void abortBeforeLease(MarketOperationJournal.Request request, String reason, CustodyFlow custody,
                                  CompletableFuture<Result> result) {
        abortBeforeLease(request, reason, result, () -> completeAbort(request, reason, custody, result));
    }

    private void abortBeforeLease(MarketOperationJournal.Request request, String reason,
                                  CompletableFuture<Result> result, Runnable afterRefund) {
        db(() -> journal.abortUntouched(request.operationId(), true, reason)).whenComplete((status, failure) -> {
            if (failure == null && (status == MarketOperationJournal.Status.APPLIED || status == MarketOperationJournal.Status.DUPLICATE)) {
                afterRefund.run();
                return;
            }
            db(() -> journal.find(request.operationId())).whenComplete((current, readFailure) -> {
                if (readFailure == null && current != null && (current.phase() == MarketOperationJournal.Phase.REFUNDED
                        || current.phase() == MarketOperationJournal.Phase.ABORTED))
                    afterRefund.run();
                else pending("abort_journal_unknown", result);
            });
        });
    }

    private void completeAbort(MarketOperationJournal.Request request, String reason, CustodyFlow custody,
                               CompletableFuture<Result> result) {
        db(() -> journal.completeAbort(request.operationId())).whenComplete((status, failure) -> {
            if (failure == null && applied(status)) {
                if (custody == null) completeResult(Outcome.REJECTED, reason, result);
                else forget(request, custody, false, reason, result);
                return;
            }
            db(() -> journal.find(request.operationId())).whenComplete((current, readFailure) -> {
                if (readFailure == null && current != null && current.phase() == MarketOperationJournal.Phase.ABORTED)
                    if (custody == null) completeResult(Outcome.REJECTED, reason, result);
                    else forget(request, custody, false, reason, result);
                else pending("abort_receipt_unknown", result);
            });
        });
    }

    private void forget(MarketOperationJournal.Request request, CustodyFlow custody, boolean committed, String terminalReason,
                        CompletableFuture<Result> result) {
        // Persist PNC's permission to forget before asking Gateway to delete its recovery receipt. If the
        // response is lost, startup can safely retry forgetTerminal with the same operation id.
        db(() -> journal.acknowledgeGatewayTerminal(request.operationId())).whenComplete((status, dbFailure) -> {
            if (dbFailure != null || !applied(status)) { pending("terminal_receipt_ack_pending", result); return; }
            CompletionStage<Void> acknowledgement;
            try { acknowledgement = custody.forgetTerminal(request.operationId(), committed); }
            catch (RuntimeException failure) { pending("terminal_receipt_ack_pending", result); return; }
            acknowledgement.whenComplete((ignored, failure) -> {
                if (failure != null) { pending("terminal_receipt_ack_pending", result); return; }
                db(() -> journal.markGatewayTerminalForgotten(request.operationId())).whenComplete((forgotten, forgetFailure) -> {
                    if (forgetFailure == null && applied(forgotten))
                        completeResult(committed ? Outcome.COMPLETED : Outcome.REJECTED, terminalReason, result);
                    else pending("terminal_receipt_ack_pending", result);
                });
            });
        });
    }

    private void review(MarketOperationJournal.Request request, String reason, CompletableFuture<Result> result) {
        db(() -> journal.markReview(request.operationId(), reason)).whenComplete((ignored, failure) ->
            completeResult(Outcome.PENDING_REVIEW, reason, result));
    }

    private <T> CompletableFuture<T> db(Supplier<T> operation) {
        return CompletableFuture.supplyAsync(operation, databaseExecutor);
    }

    private static boolean sameLease(MarketOperationJournal.Operation operation, InventoryCustody.Lease lease) {
        return operation != null && operation.phase() == MarketOperationJournal.Phase.LEASED
            && operation.request().operationId().equals(lease.operationId())
            && operation.backendConnectionId().equals(lease.connectionId())
            && operation.request().backend().equals(lease.backend())
            && operation.preRevision() == lease.preRevision() && lease.preDigest().equals(operation.preDigest());
    }

    private static boolean applied(MarketOperationJournal.Status status) {
        return status == MarketOperationJournal.Status.APPLIED || status == MarketOperationJournal.Status.DUPLICATE;
    }

    private static CompletionStage<Result> completed(Outcome outcome, String reason) {
        return CompletableFuture.completedFuture(new Result(outcome, reason));
    }

    private void completeResult(Outcome outcome, String reason, CompletableFuture<Result> result) {
        result.complete(new Result(outcome, reason));
    }

    private void pending(String reason, CompletableFuture<Result> result) {
        completeResult(Outcome.PENDING_REVIEW, reason, result);
    }
}
