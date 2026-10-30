// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.economy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Guarded integration proof. It refuses non-loopback, non-fixture ports and data directories. */
public final class MarketOperationJournalJdbcProof {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String FIXTURE_SCHEMA = "pnc_market_fixture";
    private static int assertions;

    public static void main(String[] args) throws Exception {
        String url = required("PNC_MARKET_TEST_JDBC");
        Path dataRoot = Path.of(required("PNC_MARKET_FIXTURE_ROOT")).toRealPath();
        String user = System.getenv().getOrDefault("PNC_MARKET_TEST_USER", "root");
        String password = System.getenv().getOrDefault("PNC_MARKET_TEST_PASSWORD", "");
        int port = guard(url, dataRoot, user, password);
        String adminUrl = "jdbc:mariadb://127.0.0.1:" + port + "/mysql";
        try {
            try (Connection admin = DriverManager.getConnection(adminUrl, user, password); Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + FIXTURE_SCHEMA);
                statement.execute("CREATE DATABASE " + FIXTURE_SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
            }
            initialize(url, user, password);
            completeAndResolveAmbiguousCommits(url, user, password);
            retryWhenCommitDidNotApply(url, user, password);
            insufficientFundsAndSafeRefund(url, user, password);
            coordinatorOrdersMutationAndSettlement(url, user, password);
            shopSellCreditsOnlyAfterCheckpointAndReplaysSafely(url, user, password);
            orderEscrowFillClaimAndMailboxAreReplaySafe(url, user, password);
            auctionBuyUsesBuyerCustodyAndAtomicSellerPayout(url, user, password);
            auctionListingReservesCapacityAndUsesCustody(url, user, password);
            concurrentAuctionListingsCannotOverbookSellerCapacity(url, user, password);
            expiredAuctionListingsDoNotConsumeOpenSlots(url, user, password);
            auctionCancelUsesJournaledCustody(url, user, password);
            coordinatorPinsUncertainInventory(url, user, password);
            requestIdentityAndReviewRetention(url, user, password);
            System.out.println("MarketOperationJournalJdbcProof: " + assertions + " assertions passed on isolated port " + port);
        } finally {
            try (Connection admin = DriverManager.getConnection(adminUrl, user, password); Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + FIXTURE_SCHEMA);
            }
        }
    }

    private static void completeAndResolveAmbiguousCommits(String url, String user, String password) throws Exception {
        reset(url, user, password, "50.00");
        UUID operation = id(10);
        MarketOperationJournal journal = journal(url, user, password);
        check(journal.prepare(request(operation, "10.00", 2)) == MarketOperationJournal.Status.APPLIED, "first prepare is durable");
        check(journal.markLeased(operation, id(20), "main", 7, "c".repeat(64)) == MarketOperationJournal.Status.APPLIED,
            "lease binds the exact pre-state receipt");

        MarketOperationJournal reserving = journal(afterNextCommit(url, user, password, true));
        check(reserving.reserveShopPurchase(operation) == MarketOperationJournal.Status.UNKNOWN, "lost reserve acknowledgment is unknown");
        check(journal(url, user, password).find(operation).phase() == MarketOperationJournal.Phase.FUNDS_RESERVED,
            "reserve commit can be resolved by operation id");
        check(journal(url, user, password).reserveShopPurchase(operation) == MarketOperationJournal.Status.DUPLICATE,
            "replayed reserve does not debit twice");
        check(journal(url, user, password).markItemCheckpointed(operation, 8, "a".repeat(64)) == MarketOperationJournal.Status.APPLIED,
            "post-state checkpoint is recorded");

        MarketOperationJournal settling = journal(afterNextCommit(url, user, password, true));
        check(settling.settleShopPurchase(operation, "main") == MarketOperationJournal.Status.UNKNOWN, "lost settlement acknowledgment is unknown");
        check(journal(url, user, password).find(operation).phase() == MarketOperationJournal.Phase.SETTLED,
            "settlement state and effects commit together");
        check(journal(url, user, password).settleShopPurchase(operation, "main") == MarketOperationJournal.Status.DUPLICATE,
            "replayed settlement has no extra effects");
        check(journal(url, user, password).markReleased(operation) == MarketOperationJournal.Status.APPLIED, "release follows Canopy receipt");
        check(journal(url, user, password).markReleased(operation) == MarketOperationJournal.Status.DUPLICATE, "release is idempotent");
        check(journal(url, user, password).findPendingGatewayTerminal(PLAYER) != null,
            "release remains visible until gateway terminal receipt is forgotten");
        check(journal(url, user, password).acknowledgeGatewayTerminal(operation) == MarketOperationJournal.Status.APPLIED,
            "gateway terminal acknowledgment is durable");
        check(journal(url, user, password).acknowledgeGatewayTerminal(operation) == MarketOperationJournal.Status.DUPLICATE,
            "gateway acknowledgment is idempotent");
        check(journal(url, user, password).findPendingGatewayTerminal(PLAYER) == null,
            "acknowledged operation leaves terminal-recovery queue");
        check(journal(url, user, password).findPendingGatewayForget(PLAYER) != null,
            "durable PNC acknowledgment remains queued until Canopy receipt cleanup");
        check(journal(url, user, password).markGatewayTerminalForgotten(operation) == MarketOperationJournal.Status.APPLIED,
            "Canopy receipt cleanup is recorded only after its response");
        check(journal(url, user, password).markGatewayTerminalForgotten(operation) == MarketOperationJournal.Status.DUPLICATE,
            "Canopy receipt cleanup can be replayed");
        check(journal(url, user, password).findPendingGatewayForget(PLAYER) == null,
            "completed receipt cleanup leaves the retry queue");
        equal("40.00", balance(url, user, password), "one debit");
        equal("10.00", shopSpent(url, user, password), "one spend statistic");
        equal(1, auditCount(url, user, password, "SHOP_BUY_RESERVED"), "one reserve audit");
        equal(1, auditCount(url, user, password, "SHOP_BUY"), "one settlement audit");
    }

    private static void retryWhenCommitDidNotApply(String url, String user, String password) throws Exception {
        reset(url, user, password, "25.00");
        UUID operation = id(11);
        MarketOperationJournal journal = journal(url, user, password);
        check(journal.prepare(request(operation, "4.00", 1)) == MarketOperationJournal.Status.APPLIED, "prepare retry test");
        journal.markLeased(operation, id(21), "main", 7, "c".repeat(64));
        check(journal(afterNextCommit(url, user, password, false)).reserveShopPurchase(operation) == MarketOperationJournal.Status.UNKNOWN,
            "lost pre-commit acknowledgment remains unknown");
        check(journal(url, user, password).find(operation).phase() == MarketOperationJournal.Phase.LEASED,
            "failed commit leaves operation before reservation");
        check(journal(url, user, password).reserveShopPurchase(operation) == MarketOperationJournal.Status.APPLIED,
            "same operation can retry a proven-unapplied reserve");
        equal("21.00", balance(url, user, password), "retried reserve debits once");
        equal(1, auditCount(url, user, password, "SHOP_BUY_RESERVED"), "retried reserve audits once");
    }

    private static void insufficientFundsAndSafeRefund(String url, String user, String password) throws Exception {
        reset(url, user, password, "5.00");
        MarketOperationJournal journal = journal(url, user, password);
        UUID shortFunds = id(12);
        journal.prepare(request(shortFunds, "6.00", 1)); journal.markLeased(shortFunds, id(22), "main", 7, "c".repeat(64));
        check(journal.reserveShopPurchase(shortFunds) == MarketOperationJournal.Status.INSUFFICIENT_FUNDS,
            "concurrent balance change cannot overdraw the player");
        equal("5.00", balance(url, user, password), "insufficient reserve leaves balance untouched");
        check(journal.abortUntouched(shortFunds, false, "pre_mutation_failure") == MarketOperationJournal.Status.INVALID,
            "refund without Canopy untouched proof is refused");
        check(journal.abortUntouched(shortFunds, true, "pre_mutation_failure") == MarketOperationJournal.Status.APPLIED,
            "no-mutation operation is durably refunded before releasing Canopy");
        check(journal.find(shortFunds).phase() == MarketOperationJournal.Phase.REFUNDED,
            "refunded operation remains unresolved until the Canopy abort receipt");
        check(journal.completeAbort(shortFunds) == MarketOperationJournal.Status.APPLIED,
            "Canopy receipt completes the terminal abort");

        UUID reserved = id(13);
        journal.prepare(request(reserved, "3.00", 1)); journal.markLeased(reserved, id(23), "main", 7, "c".repeat(64));
        check(journal.reserveShopPurchase(reserved) == MarketOperationJournal.Status.APPLIED, "test funds reserve");
        check(journal.abortUntouched(reserved, false, "checkpoint_missing") == MarketOperationJournal.Status.INVALID,
            "uncertain state cannot trigger a refund");
        check(journal.abortUntouched(reserved, true, "checkpoint_missing") == MarketOperationJournal.Status.APPLIED,
            "verified pre-mutation state refunds reserved funds");
        check(journal.abortUntouched(reserved, true, "checkpoint_missing") == MarketOperationJournal.Status.DUPLICATE,
            "refund replay cannot credit twice");
        check(journal.completeAbort(reserved) == MarketOperationJournal.Status.APPLIED, "abort completes after Canopy receipt");
        check(journal.completeAbort(reserved) == MarketOperationJournal.Status.DUPLICATE, "terminal abort is idempotent");
        check(journal.acknowledgeGatewayTerminal(reserved) == MarketOperationJournal.Status.APPLIED,
            "Canopy abort receipt can be acknowledged in PNC");
        check(journal.findPendingGatewayForget(PLAYER) != null,
            "aborted receipt cleanup remains retryable");
        check(journal.markGatewayTerminalForgotten(reserved) == MarketOperationJournal.Status.APPLIED,
            "aborted receipt cleanup can be completed");
        equal("5.00", balance(url, user, password), "refund restores the original balance once");
        equal(1, auditCount(url, user, password, "SHOP_BUY_REFUND"), "one refund audit");
        check(journal.markItemCheckpointed(reserved, 9, "b".repeat(64)) == MarketOperationJournal.Status.CONFLICT,
            "aborted operation cannot later accept a checkpoint");
    }

    private static void requestIdentityAndReviewRetention(String url, String user, String password) throws Exception {
        reset(url, user, password, "20.00");
        MarketOperationJournal journal = journal(url, user, password);
        UUID operation = id(14);
        MarketOperationJournal.Request request = request(operation, "2.00", 1);
        check(journal.prepare(request) == MarketOperationJournal.Status.APPLIED, "identity request creates");
        check(journal.prepare(request(operation, "2.00", 2)) == MarketOperationJournal.Status.CONFLICT,
            "same operation id with changed item outcome is rejected");
        check(journal.markReview(operation, "receipt_unknown") == MarketOperationJournal.Status.APPLIED, "ambiguous operation can be held for review");
        check(journal.markReview(operation, "receipt_unknown") == MarketOperationJournal.Status.DUPLICATE, "review state is idempotent");
        check(journal.find(operation).reviewFrom() == MarketOperationJournal.Phase.PREPARED,
            "review preserves the phase needed to choose recovery");
        check(journal.resumeReviewWithPostCheckpoint(operation, 4, "e".repeat(64)) == MarketOperationJournal.Status.CONFLICT,
            "a prepared operation cannot claim a post-mutation state");
        List<MarketOperationJournal.Operation> unresolved = journal.unresolved(50);
        check(unresolved.stream().anyMatch(row -> row.request().operationId().equals(operation)
            && row.phase() == MarketOperationJournal.Phase.REVIEW), "review rows remain visible for reconciliation");
    }

    private static void coordinatorOrdersMutationAndSettlement(String url, String user, String password) throws Exception {
        reset(url, user, password, "30.00");
        MarketOperationJournal journal = journal(url, user, password);
        MarketOperationCoordinator coordinator = new MarketOperationCoordinator(journal, Runnable::run);
        UUID operation = id(31);
        MarketOperationJournal.Request request = request(operation, "8.00", 2);
        FakeCustodyFlow success = new FakeCustodyFlow(operation, true, true, true);
        MarketOperationCoordinator.Result result = coordinator.purchaseShop(request, success).toCompletableFuture().get();
        check(result.outcome() == MarketOperationCoordinator.Outcome.COMPLETED, "coordinator completes a shop purchase");
        equal(List.of("begin", "mutate", "checkpoint", "finish", "forget"), success.calls, "Canopy fence surrounds item mutation and terminal receipt acknowledgment");
        check(journal.find(operation).phase() == MarketOperationJournal.Phase.RELEASED, "complete operation has terminal release receipt");
        equal("22.00", balance(url, user, password), "coordinator debits once");
        equal("8.00", shopSpent(url, user, password), "coordinator settles spend stat");
        equal(1, auditCount(url, user, password, "SHOP_BUY"), "coordinator writes one settlement audit");

        UUID pvpOperation = id(34);
        MarketOperationJournal.Request pvpRequest = requestKind(pvpOperation, "4.00", 1, "PVP_PURCHASE", "PVP:NETHERITE_SWORD");
        FakeCustodyFlow pvpFlow = new FakeCustodyFlow(pvpOperation, true, true, true);
        MarketOperationCoordinator.Result pvpResult = coordinator.purchaseShop(pvpRequest, pvpFlow).toCompletableFuture().get();
        check(pvpResult.outcome() == MarketOperationCoordinator.Outcome.COMPLETED, "PVP catalog purchase uses the same custody transaction");
        equal(1, auditCount(url, user, password, "PVP_BUY_RESERVED"), "PVP reserve is audited separately");
        equal(1, auditCount(url, user, password, "PVP_BUY"), "PVP settlement is audited separately");
        equal(1, auditCount(url, user, password, "SHOP_BUY"), "PVP purchase keeps the earlier shop audit count unchanged");
        equal("18.00", balance(url, user, password), "shop and PVP catalog purchases each reserve one exact amount");

        FakeCustodyFlow duplicateFlow = new FakeCustodyFlow(operation, true, true, true);
        MarketOperationCoordinator.Result duplicate = coordinator.purchaseShop(request, duplicateFlow).toCompletableFuture().get();
        check(duplicate.outcome() == MarketOperationCoordinator.Outcome.COMPLETED, "replayed released request returns its stored result");
        equal(List.of(), duplicateFlow.calls, "replay does not reacquire a lease or grant items");
        equal("18.00", balance(url, user, password), "replayed purchase does not debit twice after the PVP debit");
    }

    private static void coordinatorPinsUncertainInventory(String url, String user, String password) throws Exception {
        reset(url, user, password, "30.00");
        MarketOperationJournal journal = journal(url, user, password);
        MarketOperationCoordinator coordinator = new MarketOperationCoordinator(journal, Runnable::run);
        UUID operation = id(32);
        MarketOperationJournal.Request request = request(operation, "8.00", 2);
        FakeCustodyFlow uncertain = new FakeCustodyFlow(operation, true, true, false);
        MarketOperationCoordinator.Result result = coordinator.purchaseShop(request, uncertain).toCompletableFuture().get();
        check(result.outcome() == MarketOperationCoordinator.Outcome.PENDING_REVIEW, "failed post-checkpoint remains pending");
        check(journal.find(operation).phase() == MarketOperationJournal.Phase.REVIEW, "uncertain grant is retained for recovery");
        check(uncertain.calls.contains("mutate") && uncertain.calls.contains("checkpoint")
                && !uncertain.calls.contains("finish") && !uncertain.calls.contains("abort"),
            "uncertain inventory is neither refunded nor released");
        equal("22.00", balance(url, user, password), "uncertain post-mutation operation keeps its reservation");

        MarketOperationCoordinator.Result blocked = coordinator.purchaseShop(
            request(id(33), "1.00", 1), new FakeCustodyFlow(id(33), true, true, true)).toCompletableFuture().get();
        check(blocked.outcome() == MarketOperationCoordinator.Outcome.BUSY,
            "player cannot start another item operation while the first remains uncertain");
    }

    private static void shopSellCreditsOnlyAfterCheckpointAndReplaysSafely(String url, String user, String password) throws Exception {
        reset(url, user, password, "5.00");
        MarketOperationJournal journal = journal(url, user, password);
        MarketOperationCoordinator coordinator = new MarketOperationCoordinator(journal, Runnable::run);
        UUID operation = id(120);
        MarketOperationJournal.Request request = shopSellRequest(operation);
        FakeCustodyFlow success = new FakeCustodyFlow(operation, true, true, true);
        MarketOperationCoordinator.Result result = coordinator.executeItemOperation(request, success).toCompletableFuture().get();
        check(result.outcome() == MarketOperationCoordinator.Outcome.COMPLETED, "fixed-worth sell completes inside the custody lease");
        equal(List.of("begin", "mutate", "checkpoint", "finish", "forget"), success.calls,
            "seller inventory is checkpointed before payout and terminal receipt cleanup");
        equal("17.50", balance(url, user, password), "sale credits the seller once");
        equal("12.50", scalar(url, user, password, "SELECT sell_earned FROM player_stats WHERE uuid='" + PLAYER + "'"),
            "sale statistics commit with the credit");
        equal(1, auditCount(url, user, password, "SELL_RESERVED"), "sale reservation is audited once");
        equal(2, auditCount(url, user, password, "SELL"), "mixed sale settlement is item-audited once per stack");
        equal("16", scalar(url, user, password,
            "SELECT amount FROM transaction_audit_log WHERE event_type='SELL' AND item_key='DIAMOND'"),
            "sale audit retains the diamond stack amount");
        equal("8.5", scalar(url, user, password,
            "SELECT total_price FROM transaction_audit_log WHERE event_type='SELL' AND item_key='IRON_INGOT'"),
            "sale audit retains the other stack value");

        FakeCustodyFlow replay = new FakeCustodyFlow(operation, true, true, true);
        check(coordinator.executeItemOperation(request, replay).toCompletableFuture().get().outcome()
            == MarketOperationCoordinator.Outcome.COMPLETED, "released sale replay returns its durable result");
        equal(List.of(), replay.calls, "sale replay never removes inventory or reacquires custody");
        equal("17.50", balance(url, user, password), "sale replay cannot pay twice");
        equal(2, auditCount(url, user, password, "SELL"), "sale replay creates no duplicate settlement audit");

        reset(url, user, password, "5.00");
        UUID uncertainAck = id(121);
        MarketOperationJournal.Request uncertainRequest = shopSellRequest(uncertainAck);
        check(journal(url, user, password).prepare(uncertainRequest) == MarketOperationJournal.Status.APPLIED,
            "ambiguous sale operation is prepared");
        check(journal(url, user, password).markLeased(uncertainAck, id(1121), "main", 7, "c".repeat(64))
            == MarketOperationJournal.Status.APPLIED, "sale binds the exact Canopy pre-state");
        check(journal(url, user, password).reserveShopPurchase(uncertainAck) == MarketOperationJournal.Status.APPLIED,
            "sale reserves a durable journal phase without debiting the seller");
        check(journal(url, user, password).markItemCheckpointed(uncertainAck, 8, "d".repeat(64))
            == MarketOperationJournal.Status.APPLIED, "sale records its durable post-state");
        check(journal(afterNextCommit(url, user, password, true)).settleShopPurchase(uncertainAck, "main")
            == MarketOperationJournal.Status.UNKNOWN, "lost sale settlement acknowledgment remains unknown");
        check(journal(url, user, password).find(uncertainAck).phase() == MarketOperationJournal.Phase.SETTLED,
            "committed sale can be resolved by operation id");
        check(journal(url, user, password).settleShopPurchase(uncertainAck, "main") == MarketOperationJournal.Status.DUPLICATE,
            "sale settlement replay does not credit twice");
        equal("17.50", balance(url, user, password), "ambiguous commit leaves one sale credit");
        equal("12.50", scalar(url, user, password, "SELECT sell_earned FROM player_stats WHERE uuid='" + PLAYER + "'"),
            "ambiguous commit leaves one sale statistic");
        equal(2, auditCount(url, user, password, "SELL"), "ambiguous commit leaves one audit line per sold stack");

        reset(url, user, password, "5.00");
        UUID refusedId = id(122);
        MarketOperationJournal.Request refusedRequest = shopSellRequest(refusedId);
        FakeCustodyFlow refused = new FakeCustodyFlow(refusedId, false, false, true);
        MarketOperationCoordinator.Result rejected = coordinator.executeItemOperation(refusedRequest, refused).toCompletableFuture().get();
        check(rejected.outcome() == MarketOperationCoordinator.Outcome.REJECTED, "pre-mutation sale failure aborts cleanly");
        equal(List.of("begin", "mutate", "abort", "forget"), refused.calls,
            "failed sale releases custody only after a durable untouched abort");
        equal("5.00", balance(url, user, password), "aborted sale neither debits nor credits the seller");
        equal(1, auditCount(url, user, password, "SELL_ABORTED"), "aborted sale outcome is audited once");
        equal(0, auditCount(url, user, password, "SELL"), "aborted sale has no settlement record");
    }

    private static void orderEscrowFillClaimAndMailboxAreReplaySafe(String url, String user, String password) throws Exception {
        reset(url, user, password, "100.00");
        MarketOperationJournal journal = journal(url, user, password);
        MarketOperationCoordinator coordinator = new MarketOperationCoordinator(journal, Runnable::run);
        UUID createId = id(130);
        MarketOperationJournal.Request create = orderCreateRequest(createId, 10, "2.00", 1);
        check(journal.prepare(create) == MarketOperationJournal.Status.APPLIED, "order creation intent is journaled");
        check(journal(afterNextCommit(url, user, password, true)).createOrder(createId) == MarketOperationJournal.Status.DUPLICATE,
            "lost order-create commit acknowledgment resolves by operation id");
        long orderId = Long.parseLong(scalar(url, user, password,
            "SELECT id FROM order_listings WHERE create_operation_id='" + createId + "'"));
        equal("80.00", balance(url, user, password), "order creation reserves the full escrow once");
        equal(1, Integer.parseInt(scalar(url, user, password, "SELECT orders_created FROM player_stats WHERE uuid='" + PLAYER + "'")),
            "order creation statistic commits with escrow");
        equal(1, auditCount(url, user, password, "ORDER_CREATE"), "order creation settlement is audited once");
        check(journal.createOrder(createId) == MarketOperationJournal.Status.DUPLICATE, "order create replay is idempotent");
        equal("80.00", balance(url, user, password), "order create replay cannot debit twice");

        UUID cappedCreate = id(131);
        check(journal.prepare(orderCreateRequest(cappedCreate, 2, "2.00", 1)) == MarketOperationJournal.Status.APPLIED,
            "second order create intent is journaled");
        check(journal.createOrder(cappedCreate) == MarketOperationJournal.Status.LIMIT_REACHED,
            "seller cap is checked while the balance row is locked");
        check(journal.abortUntouched(cappedCreate, true, "order_cap_reached") == MarketOperationJournal.Status.APPLIED,
            "capped order create is durably rejected");
        check(journal.completeAbort(cappedCreate) == MarketOperationJournal.Status.APPLIED,
            "capped order create reaches a terminal receipt without Canopy");
        equal("80.00", balance(url, user, password), "capped order create leaves funds untouched");

        UUID seller = id(503);
        balanceRow(url, user, password, seller, "0.00");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             PreparedStatement insert = connection.prepareStatement("INSERT INTO players (uuid,username) VALUES (?,?)")) {
            insert.setString(1, seller.toString()); insert.setString(2, "Seller"); insert.executeUpdate();
        }
        UUID fillId = id(132);
        MarketOperationJournal.Request fill = orderFillRequest(fillId, seller, "Seller", orderId, 2, "2.00");
        FakeCustodyFlow fillFlow = new FakeCustodyFlow(fillId, true, true, true);
        MarketOperationCoordinator.Result filled = coordinator.executeItemOperation(fill, fillFlow).toCompletableFuture().get();
        check(filled.outcome() == MarketOperationCoordinator.Outcome.COMPLETED, "seller fill commits through Canopy custody");
        check(journal.find(fillId).phase() == MarketOperationJournal.Phase.RELEASED,
            "coordinator journals and releases the fill after durable settlement");
        equal("4.00", balance(url, user, password, seller), "seller payout commits once after the post-state checkpoint");
        equal("2", scalar(url, user, password, "SELECT amount_filled FROM order_listings WHERE id=" + orderId),
            "filled units move from reserved to completed");
        equal("0", scalar(url, user, password, "SELECT amount_reserved FROM order_listings WHERE id=" + orderId),
            "successful fill clears its reservation");
        long deliveryId = Long.parseLong(scalar(url, user, password,
            "SELECT id FROM order_deliveries WHERE operation_id='" + fillId + "'"));
        equal("", scalar(url, user, password,
            "SELECT COALESCE(lock_operation_id,'') FROM order_deliveries WHERE id=" + deliveryId),
            "delivery becomes claimable only after settlement");
        equal(1, auditCount(url, user, password, "ORDER_FILL"), "order fill audit commits with seller payout");
        FakeCustodyFlow fillReplay = new FakeCustodyFlow(fillId, true, true, true);
        check(coordinator.executeItemOperation(fill, fillReplay).toCompletableFuture().get().outcome()
            == MarketOperationCoordinator.Outcome.COMPLETED, "settled order fill replays its durable result");
        equal(List.of(), fillReplay.calls, "order fill replay does not remove a second item stack");
        equal("4.00", balance(url, user, password, seller), "order fill replay cannot pay twice");
        equal(1, Integer.parseInt(scalar(url, user, password,
            "SELECT COUNT(*) FROM order_deliveries WHERE operation_id='" + fillId + "'")), "fill replay cannot duplicate delivery");

        UUID unknownFillId = id(137);
        MarketOperationJournal.Request unknownFill = orderFillRequest(unknownFillId, seller, "Seller", orderId, 1, "2.00");
        check(journal.prepare(unknownFill) == MarketOperationJournal.Status.APPLIED, "second fill with ambiguous commit prepares");
        check(journal.markLeased(unknownFillId, id(1137), "main", 8, "e".repeat(64)) == MarketOperationJournal.Status.APPLIED,
            "ambiguous fill binds its exact pre-state");
        check(journal.reserveShopPurchase(unknownFillId) == MarketOperationJournal.Status.APPLIED,
            "ambiguous fill reserves its order quantity and delivery row");
        check(journal.markItemCheckpointed(unknownFillId, 9, "f".repeat(64)) == MarketOperationJournal.Status.APPLIED,
            "ambiguous fill records its post-state");
        check(journal(afterNextCommit(url, user, password, true)).settleShopPurchase(unknownFillId, "main")
            == MarketOperationJournal.Status.UNKNOWN, "lost fill commit acknowledgment stays unknown");
        check(journal.find(unknownFillId).phase() == MarketOperationJournal.Phase.SETTLED,
            "fill settlement and item delivery receipt commit together");
        check(journal.settleShopPurchase(unknownFillId, "main") == MarketOperationJournal.Status.DUPLICATE,
            "ambiguous fill settlement replays without a second payout");
        equal("6.00", balance(url, user, password, seller), "unknown fill commit pays the seller once");
        equal("3", scalar(url, user, password, "SELECT amount_filled FROM order_listings WHERE id=" + orderId),
            "unknown fill commit advances order quantity once");
        check(journal.markReleased(unknownFillId) == MarketOperationJournal.Status.APPLIED, "fill receipt releases after settlement");
        check(journal.acknowledgeGatewayTerminal(unknownFillId) == MarketOperationJournal.Status.APPLIED,
            "fill terminal receipt is acknowledged");
        check(journal.markGatewayTerminalForgotten(unknownFillId) == MarketOperationJournal.Status.APPLIED,
            "fill recovery receipt is forgotten after Canopy acknowledgment");

        UUID claimId = id(133);
        byte[] deliveryBlob = bytes(url, user, password, "SELECT item_blob FROM order_deliveries WHERE id=" + deliveryId);
        MarketOperationJournal.Request claim = new MarketOperationJournal.Request(claimId, PLAYER, "FixturePlayer",
            "ORDER_CLAIM", "ORDER_DELIVERY:" + deliveryId + ":DIAMOND", 1, BigDecimal.ZERO, "main", deliveryBlob);
        FakeCustodyFlow claimFlow = new FakeCustodyFlow(claimId, true, true, true);
        check(coordinator.executeItemOperation(claim, claimFlow).toCompletableFuture().get().outcome()
            == MarketOperationCoordinator.Outcome.COMPLETED, "delivery claim grants only under recipient custody");
        check(journal.find(claimId).phase() == MarketOperationJournal.Phase.RELEASED,
            "coordinator journals and releases the delivery claim after checkpoint");
        equal("0", scalar(url, user, password, "SELECT claimed FROM order_deliveries WHERE id=" + deliveryId),
            "partial delivery claim stays queued until all units are delivered");
        equal("1", scalar(url, user, password, "SELECT amount FROM order_deliveries WHERE id=" + deliveryId),
            "partial claim decrements only the delivered units");
        check(Double.parseDouble(scalar(url, user, password,
            "SELECT payout FROM order_deliveries WHERE id=" + deliveryId)) == 2.0,
            "partial claim decrements the associated payout receipt proportionally");
        equal(1, auditCount(url, user, password, "ORDER_DELIVERY_CLAIMED"), "delivery claim is audited");
        FakeCustodyFlow claimReplay = new FakeCustodyFlow(claimId, true, true, true);
        check(coordinator.executeItemOperation(claim, claimReplay).toCompletableFuture().get().outcome()
            == MarketOperationCoordinator.Outcome.COMPLETED, "claimed delivery replay returns its durable result");
        equal(List.of(), claimReplay.calls, "claim replay cannot grant a second item");

        UUID lastClaimId = id(138);
        MarketOperationJournal.Request lastClaim = new MarketOperationJournal.Request(lastClaimId, PLAYER, "FixturePlayer",
            "ORDER_CLAIM", "ORDER_DELIVERY:" + deliveryId + ":DIAMOND", 1, BigDecimal.ZERO, "main", deliveryBlob);
        FakeCustodyFlow lastClaimFlow = new FakeCustodyFlow(lastClaimId, true, true, true);
        check(coordinator.executeItemOperation(lastClaim, lastClaimFlow).toCompletableFuture().get().outcome()
            == MarketOperationCoordinator.Outcome.COMPLETED, "remaining units claim under the second checkpoint");
        check(journal.find(lastClaimId).phase() == MarketOperationJournal.Phase.RELEASED,
            "coordinator journals and releases the final delivery claim");
        equal("1", scalar(url, user, password, "SELECT claimed FROM order_deliveries WHERE id=" + deliveryId),
            "delivery is removed from queue after its final unit");
        equal(2, auditCount(url, user, password, "ORDER_DELIVERY_CLAIMED"), "each partial claim has its own audit row");

        UUID cancelId = id(134);
        MarketOperationJournal.Request cancel = orderCancelRequest(cancelId, orderId);
        check(journal.prepare(cancel) == MarketOperationJournal.Status.APPLIED, "order cancellation intent is journaled");
        check(journal.cancelOrder(cancelId) == MarketOperationJournal.Status.APPLIED, "order cancellation and escrow refund commit together");
        equal("94.00", balance(url, user, password), "unfilled escrow is refunded after three filled units");
        equal("CANCELLED", scalar(url, user, password, "SELECT status FROM order_listings WHERE id=" + orderId),
            "cancelled order remains durable after its delivery is claimed");
        check(journal.cancelOrder(cancelId) == MarketOperationJournal.Status.DUPLICATE, "order cancel replay cannot refund twice");
        equal("94.00", balance(url, user, password), "order cancel replay preserves the single refund");

        reset(url, user, password, "100.00");
        UUID cancelRaceCreate = id(140);
        check(journal(url, user, password).prepare(orderCreateRequest(cancelRaceCreate, 5, "2.00", 4))
            == MarketOperationJournal.Status.APPLIED, "reserved-fill order create prepares");
        check(journal(url, user, password).createOrder(cancelRaceCreate) == MarketOperationJournal.Status.APPLIED,
            "reserved-fill order escrow is funded");
        long cancelRaceOrder = Long.parseLong(scalar(url, user, password,
            "SELECT id FROM order_listings WHERE create_operation_id='" + cancelRaceCreate + "'"));
        UUID reservedFillId = id(141), cancelRaceCancelId = id(142), reservedSeller = id(504);
        balanceRow(url, user, password, reservedSeller, "0.00");
        MarketOperationJournal orderJournal = journal(url, user, password);
        MarketOperationJournal.Request reservedFill = orderFillRequest(reservedFillId, reservedSeller, "ReservedSeller",
            cancelRaceOrder, 2, "2.00");
        check(orderJournal.prepare(reservedFill) == MarketOperationJournal.Status.APPLIED, "pending fill is journaled");
        check(orderJournal.markLeased(reservedFillId, id(1141), "main", 7, "a".repeat(64)) == MarketOperationJournal.Status.APPLIED,
            "pending fill is bound to its Canopy pre-state");
        check(orderJournal.reserveShopPurchase(reservedFillId) == MarketOperationJournal.Status.APPLIED,
            "pending fill reserves two units of escrow and a hidden delivery");
        check(orderJournal.prepare(orderCancelRequest(cancelRaceCancelId, cancelRaceOrder)) == MarketOperationJournal.Status.APPLIED,
            "cancellation racing a fill is journaled");
        check(orderJournal.cancelOrder(cancelRaceCancelId) == MarketOperationJournal.Status.APPLIED,
            "order cancellation refunds only units outside the pending fill");
        equal("96.00", balance(url, user, password), "reserved fill escrow remains locked during cancellation");
        check(orderJournal.abortUntouched(reservedFillId, true, "recovered_before_mutation") == MarketOperationJournal.Status.APPLIED,
            "untouched cancelled fill releases its reservation and refunds reserved escrow");
        check(orderJournal.completeAbort(reservedFillId) == MarketOperationJournal.Status.APPLIED,
            "cancelled fill abort reaches a terminal state");
        check(orderJournal.acknowledgeGatewayTerminal(reservedFillId) == MarketOperationJournal.Status.APPLIED,
            "cancelled fill abort receipt is acknowledged");
        check(orderJournal.markGatewayTerminalForgotten(reservedFillId) == MarketOperationJournal.Status.APPLIED,
            "cancelled fill recovery receipt is forgotten after acknowledgment");
        equal("100.00", balance(url, user, password), "order and fill abort refund the full escrow exactly once");
        equal("0", scalar(url, user, password, "SELECT amount_reserved FROM order_listings WHERE id=" + cancelRaceOrder),
            "aborted fill removes its reserved capacity");
        equal("2", scalar(url, user, password,
            "SELECT claimed FROM order_deliveries WHERE operation_id='" + reservedFillId + "'"),
            "aborted fill retires its hidden delivery");
        equal("0.00", balance(url, user, password, reservedSeller), "aborted order fill never pays the seller");

        reset(url, user, password, "25.00");
        long mailboxId = mailboxItem(url, user, password, PLAYER, new byte[]{5, 4, 3});
        UUID mailClaimId = id(135);
        MarketOperationJournal.Request mailClaim = new MarketOperationJournal.Request(mailClaimId, PLAYER, "FixturePlayer",
            "MAILBOX_CLAIM", "MAILBOX:" + mailboxId + ":DIAMOND", 1, BigDecimal.ZERO, "main", new byte[]{5, 4, 3});
        FakeCustodyFlow mailFlow = new FakeCustodyFlow(mailClaimId, true, true, true);
        MarketOperationJournal mailJournal = journal(url, user, password);
        check(new MarketOperationCoordinator(mailJournal, Runnable::run)
            .executeItemOperation(mailClaim, mailFlow).toCompletableFuture().get().outcome()
            == MarketOperationCoordinator.Outcome.COMPLETED, "returned item leaves mailbox only after a durable checkpoint");
        check(mailJournal.find(mailClaimId).phase() == MarketOperationJournal.Phase.RELEASED,
            "coordinator journals and releases the mailbox claim after checkpoint");
        equal(0, Integer.parseInt(scalar(url, user, password, "SELECT COUNT(*) FROM player_mailbox WHERE id=" + mailboxId)),
            "mailbox row deletes after successful claim");
        equal(1, auditCount(url, user, password, "MAILBOX_CLAIM"), "mailbox claim is audited");

        long retainedMail = mailboxItem(url, user, password, PLAYER, new byte[]{8, 7});
        UUID refusedId = id(136);
        MarketOperationJournal.Request refusedRequest = new MarketOperationJournal.Request(refusedId, PLAYER, "FixturePlayer",
            "MAILBOX_CLAIM", "MAILBOX:" + retainedMail + ":DIAMOND", 1, BigDecimal.ZERO, "main", new byte[]{8, 7});
        FakeCustodyFlow refused = new FakeCustodyFlow(refusedId, false, false, true);
        MarketOperationCoordinator.Result refusedResult = new MarketOperationCoordinator(journal(url, user, password), Runnable::run)
            .executeItemOperation(refusedRequest, refused).toCompletableFuture().get();
        check(refusedResult.outcome() == MarketOperationCoordinator.Outcome.REJECTED, "untouched mailbox claim refusal aborts");
        equal("", scalar(url, user, password, "SELECT COALESCE(lock_operation_id,'') FROM player_mailbox WHERE id=" + retainedMail),
            "untouched mailbox abort unlocks the item row");
        equal(1, Integer.parseInt(scalar(url, user, password, "SELECT COUNT(*) FROM player_mailbox WHERE id=" + retainedMail)),
            "untouched mailbox abort retains the item");
    }

    private static void auctionBuyUsesBuyerCustodyAndAtomicSellerPayout(String url, String user, String password) throws Exception {
        reset(url, user, password, "20.00");
        UUID seller = id(60), listingOperation = id(90);
        byte[] itemBlob = new byte[]{9, 8, 7, 6};
        balanceRow(url, user, password, seller, "0.00");
        auctionListing(url, user, password, 900, seller, "7.50", itemBlob);
        MarketOperationJournal journal = journal(url, user, password);
        MarketOperationCoordinator coordinator = new MarketOperationCoordinator(journal, Runnable::run);
        MarketOperationJournal.Request request = new MarketOperationJournal.Request(listingOperation, PLAYER, "FixturePlayer",
            "AUCTION_BUY", "AUCTION_LISTING:900:DIAMOND", 1, new BigDecimal("7.50"), "main", itemBlob);
        FakeCustodyFlow success = new FakeCustodyFlow(listingOperation, true, true, true);
        MarketOperationCoordinator.Result result = coordinator.purchaseShop(request, success).toCompletableFuture().get();
        check(result.outcome() == MarketOperationCoordinator.Outcome.COMPLETED, "auction buyer gets the custody-backed purchase path");
        equal(List.of("begin", "mutate", "checkpoint", "finish", "forget"), success.calls,
            "auction item delivery is checkpointed before seller settlement");
        equal("12.50", balance(url, user, password), "auction buyer is debited once");
        equal("7.50", balance(url, user, password, seller), "seller credit commits with the sold listing");
        equal("SOLD", scalar(url, user, password, "SELECT status FROM auction_listings WHERE id=900"), "listing becomes sold atomically");
        equal("", scalar(url, user, password, "SELECT COALESCE(lock_operation_id,'') FROM auction_listings WHERE id=900"),
            "terminal sale releases the listing lock");
        equal(1, Integer.parseInt(scalar(url, user, password, "SELECT COUNT(*) FROM auction_payouts WHERE listing_id=900")),
            "seller payout receipt is written once");
        equal(1, auditCount(url, user, password, "AH_BUY_RESERVED"), "auction reservation is audited");
        equal(1, auditCount(url, user, password, "AH_BUY"), "auction settlement is audited");
        FakeCustodyFlow replay = new FakeCustodyFlow(listingOperation, true, true, true);
        check(coordinator.purchaseShop(request, replay).toCompletableFuture().get().outcome() == MarketOperationCoordinator.Outcome.COMPLETED,
            "replayed auction operation returns the durable result");
        equal(List.of(), replay.calls, "auction replay does not reacquire inventory custody");
        equal("7.50", balance(url, user, password, seller), "auction replay does not pay seller twice");
        equal(1, Integer.parseInt(scalar(url, user, password, "SELECT COUNT(*) FROM auction_payouts WHERE listing_id=900")),
            "auction replay creates no second payout receipt");

        reset(url, user, password, "20.00");
        balanceRow(url, user, password, seller, "0.00");
        auctionListing(url, user, password, 901, seller, "5.00", itemBlob);
        UUID abortedOperation = id(91);
        MarketOperationJournal.Request abortedRequest = new MarketOperationJournal.Request(abortedOperation, PLAYER, "FixturePlayer",
            "AUCTION_BUY", "AUCTION_LISTING:901:DIAMOND", 1, new BigDecimal("5.00"), "main", itemBlob);
        FakeCustodyFlow refused = new FakeCustodyFlow(abortedOperation, false, false, true);
        check(coordinator.purchaseShop(abortedRequest, refused).toCompletableFuture().get().outcome()
                == MarketOperationCoordinator.Outcome.REJECTED, "untouched auction attempt aborts cleanly");
        equal("20.00", balance(url, user, password), "untouched auction abort refunds the buyer");
        equal("0.00", balance(url, user, password, seller), "untouched auction abort does not pay the seller");
        equal("ACTIVE", scalar(url, user, password, "SELECT status FROM auction_listings WHERE id=901"),
            "untouched abort reopens the listing");
        equal("", scalar(url, user, password, "SELECT COALESCE(lock_operation_id,'') FROM auction_listings WHERE id=901"),
            "untouched abort clears the operation lock");
        equal(1, auditCount(url, user, password, "AH_BUY_REFUND"), "auction refund has a distinct audit record");

        reset(url, user, password, "20.00");
        UUID secondBuyer = id(61), firstLock = id(92), secondLock = id(93);
        balanceRow(url, user, password, seller, "0.00"); balanceRow(url, user, password, secondBuyer, "20.00");
        auctionListing(url, user, password, 902, seller, "7.50", itemBlob);
        MarketOperationJournal.Request firstRequest = new MarketOperationJournal.Request(firstLock, PLAYER, "FixturePlayer",
            "AUCTION_BUY", "AUCTION_LISTING:902:DIAMOND", 1, new BigDecimal("7.50"), "main", itemBlob);
        MarketOperationJournal.Request secondRequest = new MarketOperationJournal.Request(secondLock, secondBuyer, "OtherBuyer",
            "AUCTION_BUY", "AUCTION_LISTING:902:DIAMOND", 1, new BigDecimal("7.50"), "main", itemBlob);
        journal.prepare(firstRequest); journal.markLeased(firstLock, id(700), "main", 7, "c".repeat(64));
        check(journal.reserveShopPurchase(firstLock) == MarketOperationJournal.Status.APPLIED, "first auction buyer locks and reserves the listing");
        journal.prepare(secondRequest); journal.markLeased(secondLock, id(701), "main", 7, "d".repeat(64));
        check(journal.reserveShopPurchase(secondLock) == MarketOperationJournal.Status.CONFLICT,
            "a concurrent buyer cannot reserve a listing already locked by another operation");
        equal("20.00", balance(url, user, password, secondBuyer), "rejected concurrent buyer is not debited");
        check(journal.abortUntouched(firstLock, true, "fixture_no_mutation") == MarketOperationJournal.Status.APPLIED,
            "untouched first buyer refunds and unlocks the listing atomically");
        equal("ACTIVE", scalar(url, user, password, "SELECT status FROM auction_listings WHERE id=902"),
            "a pre-mutation abort restores listing availability");
        equal("", scalar(url, user, password, "SELECT COALESCE(lock_operation_id,'') FROM auction_listings WHERE id=902"),
            "aborted lock operation is removed");
    }

    private static void auctionListingReservesCapacityAndUsesCustody(String url, String user, String password) throws Exception {
        byte[] itemBlob = new byte[]{4, 3, 2, 1};
        reset(url, user, password, "25.00");
        MarketOperationJournal journal = journal(url, user, password);
        UUID first = id(94), second = id(95);
        MarketOperationJournal.Request firstRequest = auctionListRequest(first, "8.00", itemBlob, 1);
        check(journal.prepare(firstRequest) == MarketOperationJournal.Status.APPLIED, "first list operation is journaled");
        check(journal.markLeased(first, id(940), "main", 7, "c".repeat(64)) == MarketOperationJournal.Status.APPLIED,
            "auction listing is bound to Canopy pre-state before reserve");
        check(journal.reserveShopPurchase(first) == MarketOperationJournal.Status.APPLIED,
            "listing slot is reserved in the same transaction as its journal phase");
        equal("PREPARING", scalar(url, user, password,
            "SELECT status FROM auction_listings WHERE lock_operation_id='" + first + "'"),
            "capacity reservation is invisible to active listing queries");
        equal(1, Integer.parseInt(scalar(url, user, password,
            "SELECT COUNT(*) FROM auction_listings WHERE seller_uuid='" + PLAYER + "' AND status IN ('ACTIVE','BUYING','PREPARING')")),
            "unsettled listing still occupies one capacity slot");

        MarketOperationJournal.Request secondRequest = auctionListRequest(second, "9.00", itemBlob, 1);
        check(journal.prepare(secondRequest) == MarketOperationJournal.Status.APPLIED, "second list operation is journaled");
        check(journal.markLeased(second, id(941), "main", 7, "d".repeat(64)) == MarketOperationJournal.Status.APPLIED,
            "second listing is bound before its cap check");
        check(journal.reserveShopPurchase(second) == MarketOperationJournal.Status.LIMIT_REACHED,
            "concurrent in-flight listing cannot reserve beyond the seller cap");
        check(journal.abortUntouched(second, true, "listing_limit_reached") == MarketOperationJournal.Status.APPLIED,
            "cap rejection writes a terminal no-mutation abort");
        check(journal.completeAbort(second) == MarketOperationJournal.Status.APPLIED,
            "cap rejection releases only after its durable terminal state");
        check(journal.markItemCheckpointed(first, 8, "a".repeat(64)) == MarketOperationJournal.Status.APPLIED,
            "listing item removal receives a post-state checkpoint");
        check(journal.settleShopPurchase(first, "main") == MarketOperationJournal.Status.APPLIED,
            "settlement activates the pre-reserved listing");
        check(journal.settleShopPurchase(first, "main") == MarketOperationJournal.Status.DUPLICATE,
            "replayed listing settlement does not duplicate the listing");
        equal("ACTIVE", scalar(url, user, password,
            "SELECT status FROM auction_listings WHERE lock_operation_id IS NULL AND seller_uuid='" + PLAYER + "'"),
            "committed listing is visible only after settlement");
        equal(1, Integer.parseInt(scalar(url, user, password,
            "SELECT COUNT(*) FROM auction_listings WHERE seller_uuid='" + PLAYER + "'")),
            "listing commit creates exactly one row");
        equal("25.00", balance(url, user, password), "creating a listing does not change wallet funds");
        equal(1, auditCount(url, user, password, "AH_LIST_RESERVED"), "listing slot reservation is audited once");
        equal(1, auditCount(url, user, password, "AH_LIST_ABORTED"), "rejected listing is audited once");
        equal(1, auditCount(url, user, password, "AH_LIST"), "listing settlement is audited once");

        reset(url, user, password, "25.00");
        MarketOperationCoordinator coordinator = new MarketOperationCoordinator(journal(url, user, password), Runnable::run);
        UUID coordinated = id(96);
        MarketOperationJournal.Request coordinatedRequest = auctionListRequest(coordinated, "8.00", itemBlob, 1);
        FakeCustodyFlow success = new FakeCustodyFlow(coordinated, true, true, true);
        MarketOperationCoordinator.Result created = coordinator.purchaseShop(coordinatedRequest, success).toCompletableFuture().get();
        check(created.outcome() == MarketOperationCoordinator.Outcome.COMPLETED,
            "auction listing goes through the same verified Canopy custody coordinator");
        equal(List.of("begin", "mutate", "checkpoint", "finish", "forget"), success.calls,
            "listing removes the source item only after lease and finishes after settlement");
        FakeCustodyFlow replay = new FakeCustodyFlow(coordinated, true, true, true);
        check(coordinator.purchaseShop(coordinatedRequest, replay).toCompletableFuture().get().outcome()
                == MarketOperationCoordinator.Outcome.COMPLETED,
            "listing operation replay returns the durable result");
        equal(List.of(), replay.calls, "listing replay never mutates inventory again");
        UUID capped = id(97);
        FakeCustodyFlow refused = new FakeCustodyFlow(capped, true, true, true);
        MarketOperationCoordinator.Result rejected = coordinator.purchaseShop(
            auctionListRequest(capped, "9.00", itemBlob, 1), refused).toCompletableFuture().get();
        check(rejected.outcome() == MarketOperationCoordinator.Outcome.REJECTED
                && "listing_limit_reached".equals(rejected.reason()),
            "coordinator reports the atomic listing-cap rejection");
        equal(List.of("begin", "abort", "forget"), refused.calls,
            "cap rejection aborts custody before any item mutation");
        equal(1, Integer.parseInt(scalar(url, user, password,
            "SELECT COUNT(*) FROM auction_listings WHERE seller_uuid='" + PLAYER + "' AND status='ACTIVE'")),
            "rejected capped listing leaves the existing listing unchanged");
    }

    private static MarketOperationJournal.Request auctionListRequest(UUID operation, String price, byte[] payload, int limit) {
        return new MarketOperationJournal.Request(operation, PLAYER, "FixturePlayer", "AUCTION_LIST", "DIAMOND", 1,
            new BigDecimal(price), "main", payload, limit);
    }

    private static void concurrentAuctionListingsCannotOverbookSellerCapacity(String url, String user, String password)
            throws Exception {
        for (int trial = 0; trial < 8; trial++) {
            reset(url, user, password, "20.00");
            MarketOperationJournal journal = journal(url, user, password);
            UUID first = id(2000 + (trial * 2L)), second = id(2001 + (trial * 2L));
            check(journal.prepare(auctionListRequest(first, "4.00", new byte[]{1}, 1)) == MarketOperationJournal.Status.APPLIED,
                "first concurrent listing request prepares");
            check(journal.prepare(auctionListRequest(second, "5.00", new byte[]{2}, 1)) == MarketOperationJournal.Status.APPLIED,
                "second concurrent listing request prepares");
            check(journal.markLeased(first, id(3000 + (trial * 2L)), "main", 7, "a".repeat(64)) == MarketOperationJournal.Status.APPLIED,
                "first request is leased before competing for seller capacity");
            check(journal.markLeased(second, id(3001 + (trial * 2L)), "main", 7, "b".repeat(64)) == MarketOperationJournal.Status.APPLIED,
                "second request is leased before competing for seller capacity");

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            Future<MarketOperationJournal.Status> firstResult = workers.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("first worker start timeout");
                return journal.reserveShopPurchase(first);
            });
            Future<MarketOperationJournal.Status> secondResult = workers.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("second worker start timeout");
                return journal.reserveShopPurchase(second);
            });
            try {
                check(ready.await(5, TimeUnit.SECONDS), "both database workers reach the simultaneous start gate");
                start.countDown();
                MarketOperationJournal.Status firstStatus = firstResult.get(10, TimeUnit.SECONDS);
                MarketOperationJournal.Status secondStatus = secondResult.get(10, TimeUnit.SECONDS);
                MarketOperationJournal.Operation firstRow = journal.find(first);
                MarketOperationJournal.Operation secondRow = journal.find(second);
                boolean firstReserved = firstRow != null && firstRow.phase() == MarketOperationJournal.Phase.FUNDS_RESERVED;
                boolean secondReserved = secondRow != null && secondRow.phase() == MarketOperationJournal.Phase.FUNDS_RESERVED;
                check(firstReserved != secondReserved
                        && firstRow != null && secondRow != null
                        && (firstReserved ? secondRow.phase() : firstRow.phase()) == MarketOperationJournal.Phase.LEASED,
                    "journal resolves concurrent results to exactly one capacity reservation (statuses="
                        + firstStatus + "," + secondStatus + "; phases="
                        + (firstRow == null ? "missing" : firstRow.phase()) + ","
                        + (secondRow == null ? "missing" : secondRow.phase()) + ")");
                equal(1, Integer.parseInt(scalar(url, user, password,
                    "SELECT COUNT(*) FROM auction_listings WHERE seller_uuid='" + PLAYER + "' AND status='PREPARING'")),
                "concurrent attempts leave exactly one hidden capacity reservation");
                UUID winner = firstReserved ? first : second;
                UUID loser = firstReserved ? second : first;
                check(journal.abortUntouched(winner, true, "fixture_no_mutation") == MarketOperationJournal.Status.APPLIED,
                    "winning reservation can release its unused capacity slot");
                check(journal.completeAbort(winner) == MarketOperationJournal.Status.APPLIED,
                    "winning unused slot reaches terminal state");
                check(journal.abortUntouched(loser, true, "listing_limit_reached") == MarketOperationJournal.Status.APPLIED,
                    "losing cap request aborts without touching the winning reservation");
                check(journal.completeAbort(loser) == MarketOperationJournal.Status.APPLIED,
                    "losing cap request reaches terminal state");
                equal(0, Integer.parseInt(scalar(url, user, password,
                    "SELECT COUNT(*) FROM auction_listings WHERE seller_uuid='" + PLAYER + "'")),
                    "both no-mutation aborts leave no listing rows");
            } finally {
                start.countDown();
                workers.shutdownNow();
                workers.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void expiredAuctionListingsDoNotConsumeOpenSlots(String url, String user, String password) throws Exception {
        reset(url, user, password, "20.00");
        byte[] payload = new byte[]{3, 1, 4};
        auctionListing(url, user, password, 899, PLAYER, "12.00", payload);
        try (Connection connection = DriverManager.getConnection(url, user, password); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE auction_listings SET expires_at=TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP) WHERE id=899");
        }
        UUID operation = id(106);
        MarketOperationJournal journal = journal(url, user, password);
        check(journal.prepare(auctionListRequest(operation, "8.00", payload, 1)) == MarketOperationJournal.Status.APPLIED,
            "replacement listing prepares beside a stale expired listing");
        check(journal.markLeased(operation, id(1060), "main", 7, "a".repeat(64)) == MarketOperationJournal.Status.APPLIED,
            "replacement listing is leased before the seller cap check");
        check(journal.reserveShopPurchase(operation) == MarketOperationJournal.Status.APPLIED,
            "expired ACTIVE rows do not consume an open seller listing slot");
        check(journal.abortUntouched(operation, true, "fixture_no_mutation") == MarketOperationJournal.Status.APPLIED,
            "unmutated replacement listing can release its reserved slot");
        check(journal.completeAbort(operation) == MarketOperationJournal.Status.APPLIED,
            "replacement listing abort is terminal");
        equal("ACTIVE", scalar(url, user, password, "SELECT status FROM auction_listings WHERE id=899"),
            "cap check preserves the stale row rather than deleting player data");
        equal(0, Integer.parseInt(scalar(url, user, password,
            "SELECT COUNT(*) FROM auction_listings WHERE seller_uuid='" + PLAYER + "' AND status='PREPARING'")),
            "failed replacement flow leaves no reserved placeholder");
    }

    private static void auctionCancelUsesJournaledCustody(String url, String user, String password) throws Exception {
        byte[] itemBlob = new byte[]{7, 6, 5, 4};
        reset(url, user, password, "20.00");
        auctionListing(url, user, password, 903, PLAYER, "12.00", itemBlob);
        MarketOperationJournal journal = journal(url, user, password);
        MarketOperationCoordinator coordinator = new MarketOperationCoordinator(journal, Runnable::run);
        UUID operation = id(98);
        MarketOperationJournal.Request request = auctionCancelRequest(operation, 903, itemBlob);
        FakeCustodyFlow success = new FakeCustodyFlow(operation, true, true, true);
        MarketOperationCoordinator.Result cancelled = coordinator.purchaseShop(request, success).toCompletableFuture().get();
        check(cancelled.outcome() == MarketOperationCoordinator.Outcome.COMPLETED,
            "auction cancellation returns its item only through Canopy custody");
        equal(List.of("begin", "mutate", "checkpoint", "finish", "forget"), success.calls,
            "listing remains locked until item return is checkpointed and settled");
        equal("CANCELLED", scalar(url, user, password, "SELECT status FROM auction_listings WHERE id=903"),
            "listing cancellation commits after the custody checkpoint");
        equal("", scalar(url, user, password, "SELECT COALESCE(lock_operation_id,'') FROM auction_listings WHERE id=903"),
            "committed cancellation clears its listing lock");
        equal("20.00", balance(url, user, password), "cancellation does not credit or debit money");
        equal(1, auditCount(url, user, password, "AH_CANCEL_RESERVED"), "cancellation lock is audited once");
        equal(1, auditCount(url, user, password, "AH_CANCEL"), "successful item return is audited once");
        FakeCustodyFlow replay = new FakeCustodyFlow(operation, true, true, true);
        check(coordinator.purchaseShop(request, replay).toCompletableFuture().get().outcome() == MarketOperationCoordinator.Outcome.COMPLETED,
            "replayed cancellation returns its durable result");
        equal(List.of(), replay.calls, "replayed cancellation does not return a second item");

        reset(url, user, password, "20.00");
        auctionListing(url, user, password, 904, PLAYER, "12.00", itemBlob);
        UUID heldCancel = id(100), blockedList = id(101);
        check(journal.prepare(auctionCancelRequest(heldCancel, 904, itemBlob)) == MarketOperationJournal.Status.APPLIED,
            "held cancellation operation is journaled");
        check(journal.markLeased(heldCancel, id(1000), "main", 7, "e".repeat(64)) == MarketOperationJournal.Status.APPLIED,
            "held cancellation has Canopy pre-state before locking the listing");
        check(journal.reserveShopPurchase(heldCancel) == MarketOperationJournal.Status.APPLIED,
            "cancelling listing remains reserved while inventory return is pending");
        equal("CANCELLING", scalar(url, user, password, "SELECT status FROM auction_listings WHERE id=904"),
            "pending cancellation has a distinct hidden state");
        check(journal.prepare(auctionListRequest(blockedList, "9.00", itemBlob, 1)) == MarketOperationJournal.Status.APPLIED,
            "second listing operation is journaled while cancellation is pending");
        check(journal.markLeased(blockedList, id(1001), "main", 7, "f".repeat(64)) == MarketOperationJournal.Status.APPLIED,
            "second listing is leased before the atomic capacity decision");
        check(journal.reserveShopPurchase(blockedList) == MarketOperationJournal.Status.LIMIT_REACHED,
            "CANCELLING state still occupies seller listing capacity");
        check(journal.abortUntouched(blockedList, true, "listing_limit_reached") == MarketOperationJournal.Status.APPLIED,
            "blocked listing leaves no mutation reservation");
        check(journal.completeAbort(blockedList) == MarketOperationJournal.Status.APPLIED,
            "blocked listing reaches a durable terminal abort");
        check(journal.abortUntouched(heldCancel, true, "fixture_no_mutation") == MarketOperationJournal.Status.APPLIED,
            "untouched held cancellation restores the listing");
        check(journal.completeAbort(heldCancel) == MarketOperationJournal.Status.APPLIED,
            "held cancellation abort reaches a durable terminal state");
        equal("ACTIVE", scalar(url, user, password, "SELECT status FROM auction_listings WHERE id=904"),
            "restored active listing still occupies its seller slot");

        reset(url, user, password, "20.00");
        auctionListing(url, user, password, 905, PLAYER, "12.00", itemBlob);
        UUID aborted = id(99);
        FakeCustodyFlow refused = new FakeCustodyFlow(aborted, false, false, true);
        MarketOperationCoordinator.Result rejected = coordinator.purchaseShop(
            auctionCancelRequest(aborted, 905, itemBlob), refused).toCompletableFuture().get();
        check(rejected.outcome() == MarketOperationCoordinator.Outcome.REJECTED,
            "untouched cancellation is rejected cleanly");
        equal(List.of("begin", "mutate", "abort", "forget"), refused.calls,
            "failed item return releases the lease without deleting the listing");
        equal("ACTIVE", scalar(url, user, password, "SELECT status FROM auction_listings WHERE id=905"),
            "pre-mutation cancellation abort reopens the original listing");
        equal("", scalar(url, user, password, "SELECT COALESCE(lock_operation_id,'') FROM auction_listings WHERE id=905"),
            "pre-mutation cancellation clears its listing lock");
        equal(1, auditCount(url, user, password, "AH_CANCEL_ABORTED"), "failed cancellation outcome is audited");
    }

    private static MarketOperationJournal.Request auctionCancelRequest(UUID operation, long listingId, byte[] payload) {
        return new MarketOperationJournal.Request(operation, PLAYER, "FixturePlayer", "AUCTION_CANCEL",
            "AUCTION_LISTING:" + listingId + ":DIAMOND", 1, new BigDecimal("0.00"), "main", payload);
    }

    private static final class FakeCustodyFlow implements MarketOperationCoordinator.CustodyFlow {
        final UUID operation;
        final boolean mutationStarted;
        final boolean mutationComplete;
        final boolean checkpointAccepted;
        final List<String> calls = new ArrayList<>();
        InventoryCustody.Lease lease;

        FakeCustodyFlow(UUID operation, boolean mutationStarted, boolean mutationComplete, boolean checkpointAccepted) {
            this.operation = operation;
            this.mutationStarted = mutationStarted;
            this.mutationComplete = mutationComplete;
            this.checkpointAccepted = checkpointAccepted;
        }

        @Override public boolean qualified() { return true; }
        @Override public CompletableFuture<InventoryCustody.Lease> begin(MarketOperationJournal.Request request) {
            calls.add("begin");
            lease = new InventoryCustody.Lease(operation, request.playerId(), id(700 + operation.getLeastSignificantBits()), "main", 7, "c".repeat(64));
            return CompletableFuture.completedFuture(lease);
        }
        @Override public CompletableFuture<MarketOperationCoordinator.Mutation> applyItems(MarketOperationJournal.Request request,
                                                                                           InventoryCustody.Lease lease) {
            calls.add("mutate");
            return CompletableFuture.completedFuture(new MarketOperationCoordinator.Mutation(mutationStarted,
                mutationComplete, mutationComplete ? null : "fixture_partial"));
        }
        @Override public CompletableFuture<InventoryCustody.CheckpointReceipt> checkpoint(InventoryCustody.Lease lease) {
            calls.add("checkpoint");
            if (!checkpointAccepted) return CompletableFuture.failedFuture(new IllegalStateException("fixture checkpoint lost"));
            return CompletableFuture.completedFuture(new InventoryCustody.CheckpointReceipt(lease, 8, "d".repeat(64)));
        }
        @Override public CompletableFuture<Void> finish(InventoryCustody.Lease lease) {
            calls.add("finish");
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> abortUntouched(InventoryCustody.Lease lease) {
            calls.add("abort");
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> forgetTerminal(UUID operationId, boolean committed) {
            calls.add("forget");
            return CompletableFuture.completedFuture(null);
        }
    }

    private static void initialize(String url, String user, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE balances (uuid CHAR(36) PRIMARY KEY,money DECIMAL(18,2) NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE player_stats (uuid CHAR(36) PRIMARY KEY,shop_spent DECIMAL(18,2) NOT NULL DEFAULT 0,sell_earned DECIMAL(18,2) NOT NULL DEFAULT 0,orders_created INT NOT NULL DEFAULT 0,orders_fulfilled INT NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE players (uuid CHAR(36) PRIMARY KEY,username VARCHAR(16) NOT NULL)");
            statement.execute("CREATE TABLE transaction_audit_log (id BIGINT AUTO_INCREMENT PRIMARY KEY,player_uuid CHAR(36) NOT NULL,player_name VARCHAR(16) NOT NULL,event_type VARCHAR(32) NOT NULL,item_key VARCHAR(96) NOT NULL,amount INT NOT NULL,unit_price DOUBLE NOT NULL,total_price DOUBLE NOT NULL,extra_info VARCHAR(255),server_name VARCHAR(32) NOT NULL,logged_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE economy_market_operations (operation_id CHAR(36) PRIMARY KEY,player_uuid CHAR(36) NOT NULL,player_name VARCHAR(16) NOT NULL,operation_kind VARCHAR(32) NOT NULL,item_key VARCHAR(96) NOT NULL,item_count INT NOT NULL,total_price DECIMAL(18,2) NOT NULL,backend_name VARCHAR(32) NOT NULL,item_payload LONGBLOB NOT NULL,request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,phase VARCHAR(24) NOT NULL,backend_connection_id CHAR(36) NULL,pre_revision BIGINT NULL,pre_digest CHAR(64) NULL,post_revision BIGINT NULL,post_digest CHAR(64) NULL,review_from_phase VARCHAR(24) NULL,result_detail VARCHAR(64) NULL,gateway_terminal_ack TINYINT(1) NOT NULL DEFAULT 0,gateway_terminal_forgotten TINYINT(1) NOT NULL DEFAULT 0,market_limit INT NOT NULL DEFAULT 0,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,INDEX idx_market_player_phase (player_uuid,phase,updated_at),INDEX idx_market_phase_updated (phase,updated_at))");
            statement.execute("CREATE TABLE auction_listings (id BIGINT AUTO_INCREMENT PRIMARY KEY,seller_uuid CHAR(36) NOT NULL,buyer_uuid CHAR(36) NULL,lock_operation_id CHAR(36) NULL,item_blob LONGBLOB NOT NULL,item_key VARCHAR(96) NOT NULL,price DOUBLE NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',expires_at TIMESTAMP NULL DEFAULT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE auction_payouts (id BIGINT AUTO_INCREMENT PRIMARY KEY,recipient_uuid CHAR(36) NOT NULL,listing_id BIGINT NOT NULL,amount DOUBLE NOT NULL,claimed TINYINT(1) NOT NULL DEFAULT 0,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE order_listings (id BIGINT AUTO_INCREMENT PRIMARY KEY,create_operation_id CHAR(36) NULL UNIQUE,creator_uuid CHAR(36) NOT NULL,item_key VARCHAR(96) NOT NULL,amount_total INT NOT NULL,amount_filled INT NOT NULL DEFAULT 0,amount_reserved INT NOT NULL DEFAULT 0,unit_price DOUBLE NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,expires_at TIMESTAMP NULL)");
            statement.execute("CREATE TABLE order_deliveries (id BIGINT AUTO_INCREMENT PRIMARY KEY,operation_id CHAR(36) NULL UNIQUE,lock_operation_id CHAR(36) NULL,order_id BIGINT NOT NULL,fulfiller_uuid CHAR(36) NOT NULL,amount INT NOT NULL,payout DOUBLE NOT NULL,item_blob LONGBLOB NULL,claimed TINYINT(1) NOT NULL DEFAULT 0,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,claimed_at TIMESTAMP NULL)");
            statement.execute("CREATE TABLE player_mailbox (id BIGINT AUTO_INCREMENT PRIMARY KEY,uuid CHAR(36) NOT NULL,item_blob LONGBLOB NOT NULL,reason VARCHAR(64) NOT NULL,lock_operation_id CHAR(36) NULL)");
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO balances (uuid,money) VALUES (?,?)")) {
                insert.setString(1, PLAYER.toString()); insert.setBigDecimal(2, new BigDecimal("0.00")); insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO players (uuid,username) VALUES (?,?)")) {
                insert.setString(1, PLAYER.toString()); insert.setString(2, "FixturePlayer"); insert.executeUpdate();
            }
        }
    }

    private static void reset(String url, String user, String password, String startingBalance) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM economy_market_operations");
            statement.executeUpdate("DELETE FROM auction_payouts");
            statement.executeUpdate("DELETE FROM auction_listings");
            statement.executeUpdate("DELETE FROM order_deliveries");
            statement.executeUpdate("DELETE FROM order_listings");
            statement.executeUpdate("DELETE FROM player_mailbox");
            statement.executeUpdate("DELETE FROM transaction_audit_log");
            statement.executeUpdate("DELETE FROM player_stats");
            statement.executeUpdate("DELETE FROM balances WHERE uuid<>'" + PLAYER + "'");
            statement.executeUpdate("UPDATE balances SET money=" + new BigDecimal(startingBalance).toPlainString() + " WHERE uuid='" + PLAYER + "'");
        }
    }

    private static MarketOperationJournal journal(String url, String user, String password) {
        return new MarketOperationJournal(() -> DriverManager.getConnection(url, user, password));
    }

    private static MarketOperationJournal journal(MarketOperationJournal.ConnectionFactory factory) {
        return new MarketOperationJournal(factory);
    }

    private static MarketOperationJournal.ConnectionFactory afterNextCommit(String url, String user, String password,
                                                                              boolean applyBeforeLostAck) {
        AtomicBoolean first = new AtomicBoolean(true);
        return () -> {
            Connection delegate = DriverManager.getConnection(url, user, password);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("commit") && first.compareAndSet(true, false)) {
                        if (applyBeforeLostAck) delegate.commit();
                        throw new SQLException("fixture commit acknowledgment lost");
                    }
                    try { return method.invoke(delegate, args); }
                    catch (InvocationTargetException ex) { throw ex.getCause(); }
                });
        };
    }

    private static int guard(String url, Path dataRoot, String user, String password) throws Exception {
        String prefix = "jdbc:mariadb://127.0.0.1:";
        if (!url.startsWith(prefix)) throw new IllegalArgumentException("fixture URL must use loopback MariaDB");
        String suffix = url.substring(prefix.length());
        int slash = suffix.indexOf('/');
        if (slash <= 0) throw new IllegalArgumentException("fixture URL database is missing");
        int port = Integer.parseInt(suffix.substring(0, slash));
        String schema = suffix.substring(slash + 1).split("[?;]", 2)[0];
        if (port < 33300 || port > 33999 || !FIXTURE_SCHEMA.equals(schema))
            throw new IllegalArgumentException("fixture URL must use isolated port and schema");
        try (Connection connection = DriverManager.getConnection("jdbc:mariadb://127.0.0.1:" + port + "/mysql", user, password);
             Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT @@port,@@datadir")) {
            if (!result.next() || result.getInt(1) != port) throw new IllegalStateException("fixture port mismatch");
            Path actualData = Path.of(result.getString(2)).toRealPath();
            if (!actualData.startsWith(dataRoot)) throw new IllegalStateException("MariaDB datadir is outside the private fixture root");
        }
        return port;
    }

    private static MarketOperationJournal.Request request(UUID id, String price, int count) {
        return requestKind(id, price, count, "SHOP_PURCHASE", "DIAMOND");
    }

    private static MarketOperationJournal.Request requestKind(UUID id, String price, int count, String kind, String itemKey) {
        return new MarketOperationJournal.Request(id, PLAYER, "FixturePlayer", kind, itemKey,
            count, new BigDecimal(price), "main", new byte[]{1, 2, (byte) count});
    }

    private static MarketOperationJournal.Request shopSellRequest(UUID operation) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
            out.writeInt(0x53534c31); out.writeInt(2);
            out.writeInt(0); out.writeUTF("DIAMOND"); out.writeInt(16); out.writeUTF("0.25"); out.writeInt(1); out.writeByte(1);
            out.writeInt(1); out.writeUTF("IRON_INGOT"); out.writeInt(32); out.writeUTF("0.265625"); out.writeInt(1); out.writeByte(2);
        }
        return new MarketOperationJournal.Request(operation, PLAYER, "FixturePlayer", "SHOP_SELL", "MIXED",
            48, new BigDecimal("12.50"), "main", bytes.toByteArray());
    }

    private static MarketOperationJournal.Request orderCreateRequest(UUID operation, int amount, String unitPrice, int cap)
            throws Exception {
        BigDecimal unit = new BigDecimal(unitPrice);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
            out.writeInt(0x4f524431); out.writeUTF(unit.toPlainString()); out.writeInt(cap);
        }
        return new MarketOperationJournal.Request(operation, PLAYER, "FixturePlayer", "ORDER_CREATE", "DIAMOND",
            amount, unit.multiply(BigDecimal.valueOf(amount)), "main", bytes.toByteArray());
    }

    private static MarketOperationJournal.Request orderFillRequest(UUID operation, UUID seller, String sellerName,
            long orderId, int amount, String unitPrice) throws Exception {
        BigDecimal unit = new BigDecimal(unitPrice);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
            out.writeInt(0x53534c31); out.writeInt(1);
            out.writeInt(0); out.writeUTF("DIAMOND"); out.writeInt(amount); out.writeUTF(unit.toPlainString());
            out.writeInt(1); out.writeByte(7);
        }
        return new MarketOperationJournal.Request(operation, seller, sellerName, "ORDER_FILL", "ORDER_FILL:" + orderId,
            amount, unit.multiply(BigDecimal.valueOf(amount)), "main", bytes.toByteArray());
    }

    private static MarketOperationJournal.Request orderCancelRequest(UUID operation, long orderId) {
        return new MarketOperationJournal.Request(operation, PLAYER, "FixturePlayer", "ORDER_CANCEL",
            "ORDER_CANCEL:" + orderId, 1, BigDecimal.ZERO, "main", new byte[]{1});
    }

    private static long mailboxItem(String url, String user, String password, UUID owner, byte[] item) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO player_mailbox (uuid,item_blob,reason) VALUES (?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, owner.toString()); insert.setBytes(2, item); insert.setString(3, "fixture");
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("mailbox id missing");
                return keys.getLong(1);
            }
        }
    }

    private static byte[] bytes(String url, String user, String password, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new AssertionError("missing byte row");
            return rows.getBytes(1);
        }
    }

    private static UUID id(long leastBits) { return new UUID(0, leastBits); }

    private static String balance(String url, String user, String password) throws SQLException {
        return scalar(url, user, password, "SELECT money FROM balances WHERE uuid='" + PLAYER + "'");
    }

    private static String balance(String url, String user, String password, UUID player) throws SQLException {
        return scalar(url, user, password, "SELECT money FROM balances WHERE uuid='" + player + "'");
    }

    private static void balanceRow(String url, String user, String password, UUID player, String amount) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO balances (uuid,money) VALUES (?,?) ON DUPLICATE KEY UPDATE money=VALUES(money)")) {
            insert.setString(1, player.toString()); insert.setBigDecimal(2, new BigDecimal(amount)); insert.executeUpdate();
        }
    }

    private static void auctionListing(String url, String user, String password, long id, UUID seller,
                                      String price, byte[] item) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO auction_listings (id,seller_uuid,item_blob,item_key,price,status) VALUES (?,?,?,?,?,'ACTIVE')")) {
            insert.setLong(1, id); insert.setString(2, seller.toString()); insert.setBytes(3, item);
            insert.setString(4, "DIAMOND"); insert.setDouble(5, Double.parseDouble(price)); insert.executeUpdate();
        }
    }

    private static String shopSpent(String url, String user, String password) throws SQLException {
        return scalar(url, user, password, "SELECT shop_spent FROM player_stats WHERE uuid='" + PLAYER + "'");
    }

    private static int auditCount(String url, String user, String password, String event) throws SQLException {
        return Integer.parseInt(scalar(url, user, password,
            "SELECT COUNT(*) FROM transaction_audit_log WHERE event_type='" + event + "'"));
    }

    private static String scalar(String url, String user, String password, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new AssertionError("missing scalar result");
            return rows.getString(1);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + name);
        return value;
    }

    private static void equal(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual), message + ": expected " + expected + " got " + actual);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
