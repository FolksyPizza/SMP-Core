// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.economy;

import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;

/** Canopy-authoritative lease for a single core-owned player inventory mutation. */
public interface InventoryCustody {
    enum RecoveryStage { BEGIN_ONLY, PRE_ONLY, POST_AVAILABLE, COMMIT_PENDING, ABORT_PENDING, COMMITTED, ABORTED }

    record Lease(UUID operationId, UUID playerId, UUID connectionId, String backend,
                 long preRevision, String preDigest) {
        public Lease {
            java.util.Objects.requireNonNull(operationId, "operationId");
            java.util.Objects.requireNonNull(playerId, "playerId");
            java.util.Objects.requireNonNull(connectionId, "connectionId");
            if (backend == null || backend.isBlank()) throw new IllegalArgumentException("backend");
            if (preRevision < 0 || preDigest == null || !preDigest.matches("[a-fA-F0-9]{64}"))
                throw new IllegalArgumentException("pre-state receipt");
        }
    }

    record CheckpointReceipt(Lease lease, long revision, String digest) {
        public CheckpointReceipt {
            java.util.Objects.requireNonNull(lease, "lease");
            if (revision < 0 || digest == null || !digest.matches("[a-fA-F0-9]{64}"))
                throw new IllegalArgumentException("checkpoint receipt");
        }
    }

    record RecoveryOffer(UUID operationId, UUID playerId, UUID connectionId, String backend,
                         UUID gatewayConnectionId, long stayEpoch, RecoveryStage stage,
                         long preRevision, String preDigest, long postRevision, String postDigest) {
        public RecoveryOffer {
            java.util.Objects.requireNonNull(operationId, "operationId");
            java.util.Objects.requireNonNull(playerId, "playerId");
            java.util.Objects.requireNonNull(connectionId, "connectionId");
            if (backend == null || backend.isBlank()) throw new IllegalArgumentException("backend");
            java.util.Objects.requireNonNull(gatewayConnectionId, "gatewayConnectionId");
            java.util.Objects.requireNonNull(stage, "stage");
            if (stayEpoch < 0) throw new IllegalArgumentException("stayEpoch");
            boolean prePresent = preRevision >= 0;
            if (prePresent != (preDigest != null && preDigest.matches("[a-fA-F0-9]{64}")))
                throw new IllegalArgumentException("pre-state receipt");
            boolean postPresent = postRevision >= 0;
            if (postPresent != (postDigest != null && postDigest.matches("[a-fA-F0-9]{64}"))
                    || postPresent && (!prePresent || postRevision <= preRevision))
                throw new IllegalArgumentException("post-state receipt");
            if ((stage == RecoveryStage.BEGIN_ONLY) != !prePresent && stage != RecoveryStage.COMMITTED && stage != RecoveryStage.ABORTED)
                throw new IllegalArgumentException("recovery stage/state mismatch");
        }
        public boolean hasPreState() { return preRevision >= 0; }
        public boolean hasPostState() { return postRevision >= 0; }
    }

    record RecoveryReceipt(RecoveryOffer offer, boolean stateApplied, boolean restoredPostState, long revision, String digest) {
        public RecoveryReceipt {
            java.util.Objects.requireNonNull(offer, "offer");
            if (!stateApplied) {
                if (revision != -1 || digest != null) throw new IllegalArgumentException("absent recovery receipt");
            } else {
                long expectedRevision = restoredPostState ? offer.postRevision() : offer.preRevision();
                String expectedDigest = restoredPostState ? offer.postDigest() : offer.preDigest();
                if (revision != expectedRevision || !java.util.Objects.equals(digest, expectedDigest))
                    throw new IllegalArgumentException("recovery receipt does not match offered checkpoint");
            }
            if (restoredPostState && !stateApplied) throw new IllegalArgumentException("post-state not applied");
        }
    }

    /** True only after matched gateway, native-save, and player-mutation failure tests pass. */
    boolean qualified();

    /**
     * True while a Canopy lease, recovery or persistent quarantine owns this player's inventory snapshot.
     * Background cleanup and other non-custody code must not mutate player inventory while this returns true.
     */
    boolean mutationBlocked(Player player);

    /**
     * Completes with a lease only after the verified pre-mutation state is durable at Canopy. Exceptional completion
     * means no lease exists or the matching cancel receipt was confirmed. If remote state is uncertain, this future
     * remains pending and the player stays quarantined; callers must not infer that failure cleared the barrier.
     */
    CompletionStage<Lease> begin(Player player, UUID operation);

    /**
     * Persists and confirms the post-mutation state at Canopy. After a lost-reply failure,
     * callers may retry this same lease.
     */
    CompletionStage<CheckpointReceipt> checkpoint(Player player, Lease lease);

    /**
     * Release after the corresponding core economy transaction is durably settled. After a
     * lost-reply failure, callers may retry this same lease.
     */
    CompletionStage<Void> finish(Player player, Lease lease);

    /**
     * Clear a lease only when no player inventory mutation occurred. After a lost-reply
     * failure, callers may retry this same lease.
     */
    CompletionStage<Void> abortUntouched(Player player, Lease lease);

    /** Inspect the durable Canopy pre/post snapshots for a player held by a previous operation. */
    CompletionStage<RecoveryOffer> inspectRecovery(Player player, UUID operationId);

    /** Restore one of the offered exact snapshots while keeping the player held and the barrier active. */
    CompletionStage<RecoveryReceipt> applyRecovery(Player player, RecoveryOffer offer, boolean postState);

    /** Release recovery only after the matching PNC journal transaction is terminal. */
    CompletionStage<Void> completeRecovery(Player player, RecoveryReceipt receipt, boolean committed);

    /** Let the gateway discard its compact terminal receipt after PNC durably records the acknowledgment. */
    CompletionStage<Void> forgetTerminal(Player player, UUID operationId, boolean committed);
}
