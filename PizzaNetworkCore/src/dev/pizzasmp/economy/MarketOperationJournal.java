// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.economy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Durable, idempotent journal for item-changing economy operations. */
public final class MarketOperationJournal {
    public static final int MAX_ITEM_PAYLOAD_BYTES = 1 << 20;
    private static final int SHOP_SELL_PAYLOAD_MAGIC = 0x53534c31;
    private static final int ORDER_CREATE_PAYLOAD_MAGIC = 0x4f524431;

    public enum Phase {
        PREPARED, LEASED, FUNDS_RESERVED, ITEM_CHECKPOINTED, SETTLED, RELEASED, REFUNDED, ABORTED, REVIEW
    }

    public enum Status {
        APPLIED, DUPLICATE, CONFLICT, INSUFFICIENT_FUNDS, LIMIT_REACHED, NOT_FOUND, INVALID, UNKNOWN
    }

    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    public record Request(UUID operationId, UUID playerId, String playerName, String operationKind,
                          String itemKey, int itemCount, BigDecimal totalPrice, String backend,
                          byte[] itemPayload, int marketLimit) {
        public Request {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(playerId, "playerId");
            playerName = requireText(playerName, 16, "playerName");
            operationKind = requireIdentifier(operationKind, 32, "operationKind");
            itemKey = requireText(itemKey, 96, "itemKey");
            backend = requireText(backend, 32, "backend");
            if (itemCount <= 0) throw new IllegalArgumentException("itemCount");
            totalPrice = cents(totalPrice);
            if (itemPayload == null || itemPayload.length == 0 || itemPayload.length > MAX_ITEM_PAYLOAD_BYTES)
                throw new IllegalArgumentException("itemPayload");
            if (marketLimit < 0 || marketLimit > 1000 || "AUCTION_LIST".equals(operationKind) && marketLimit < 1
                    || !"AUCTION_LIST".equals(operationKind) && marketLimit != 0)
                throw new IllegalArgumentException("marketLimit");
            itemPayload = itemPayload.clone();
        }

        public Request(UUID operationId, UUID playerId, String playerName, String operationKind,
                       String itemKey, int itemCount, BigDecimal totalPrice, String backend, byte[] itemPayload) {
            this(operationId, playerId, playerName, operationKind, itemKey, itemCount, totalPrice, backend, itemPayload, 0);
        }

        @Override public byte[] itemPayload() { return itemPayload.clone(); }
    }

    public record Operation(Request request, Phase phase, UUID backendConnectionId,
                            long preRevision, String preDigest, long postRevision, String postDigest,
                            String detail, Phase reviewFrom, boolean gatewayTerminalAck, boolean gatewayTerminalForgotten,
                            int marketLimit) { }

    private static final String SELECT_COLUMNS = "operation_id,player_uuid,player_name,operation_kind,item_key,item_count,total_price,backend_name,item_payload,request_sha256,phase,backend_connection_id,pre_revision,pre_digest,post_revision,post_digest,result_detail,review_from_phase,gateway_terminal_ack,gateway_terminal_forgotten,market_limit";
    private static final String SYSTEM_SELLER_UUID = "00000000-0000-0000-0000-000000000001";

    private final ConnectionFactory connections;

    public MarketOperationJournal(ConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /** Insert once. A repeated operation id is accepted only for the byte-identical request. */
    public Status prepare(Request request) {
        Objects.requireNonNull(request, "request");
        String digest = requestDigest(request);
        String sql = "INSERT IGNORE INTO economy_market_operations "
            + "(operation_id,player_uuid,player_name,operation_kind,item_key,item_count,total_price,backend_name,item_payload,request_sha256,phase,market_limit) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = connections.open(); PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, request.operationId().toString());
            insert.setString(2, request.playerId().toString());
            insert.setString(3, request.playerName());
            insert.setString(4, request.operationKind());
            insert.setString(5, request.itemKey());
            insert.setInt(6, request.itemCount());
            insert.setBigDecimal(7, request.totalPrice());
            insert.setString(8, request.backend());
            insert.setBytes(9, request.itemPayload());
            insert.setString(10, digest);
            insert.setString(11, Phase.PREPARED.name());
            insert.setInt(12, request.marketLimit());
            if (insert.executeUpdate() == 1) return Status.APPLIED;
        } catch (SQLException failure) {
            try {
                Operation current = find(request.operationId());
                if (current != null) return requestDigest(current.request()).equals(digest) ? Status.DUPLICATE : Status.CONFLICT;
            } catch (RuntimeException ignored) { }
            return Status.UNKNOWN;
        }
        try {
            Operation existing = find(request.operationId());
            if (existing == null) return Status.UNKNOWN;
            return requestDigest(existing.request()).equals(digest) ? Status.DUPLICATE : Status.CONFLICT;
        } catch (RuntimeException failure) {
            return Status.UNKNOWN;
        }
    }

    /** Creates one escrow-backed order and its audit receipt in a single idempotent SQL transaction. */
    public Status createOrder(UUID operationId) {
        if (operationId == null) return Status.INVALID;
        Connection connection = null;
        boolean transaction = false;
        boolean commitAttempted = false;
        try {
            connection = connections.open();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            transaction = true;
            Operation operation = selectForUpdate(connection, operationId);
            if (operation == null) { rollback(connection); return Status.NOT_FOUND; }
            if (!isOrderCreate(operation.request().operationKind())) { rollback(connection); return Status.CONFLICT; }
            if (operation.phase() == Phase.RELEASED) { rollback(connection); return Status.DUPLICATE; }
            if (operation.phase() != Phase.PREPARED) { rollback(connection); return Status.CONFLICT; }
            OrderCreateTerms terms;
            try { terms = readOrderCreateTerms(operation.request()); }
            catch (IllegalArgumentException invalid) { rollback(connection); return Status.INVALID; }

            String uuid = operation.request().playerId().toString();
            try (PreparedStatement ensure = connection.prepareStatement("INSERT IGNORE INTO balances (uuid,money) VALUES (?,0.00)")) {
                ensure.setString(1, uuid); ensure.executeUpdate();
            }
            try (PreparedStatement lock = connection.prepareStatement("SELECT money FROM balances WHERE uuid=? FOR UPDATE")) {
                lock.setString(1, uuid);
                try (ResultSet row = lock.executeQuery()) { if (!row.next()) throw new SQLException("order escrow balance row is missing"); }
            }
            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM order_listings WHERE creator_uuid=? AND status='ACTIVE' "
                        + "AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)")) {
                count.setString(1, uuid);
                try (ResultSet row = count.executeQuery()) {
                    if (!row.next()) throw new SQLException("active order count is missing");
                    if (row.getLong(1) >= terms.maxOpenOrders()) { rollback(connection); return Status.LIMIT_REACHED; }
                }
            }
            try (PreparedStatement debit = connection.prepareStatement("UPDATE balances SET money=money-? WHERE uuid=? AND money>=?")) {
                debit.setBigDecimal(1, operation.request().totalPrice());
                debit.setString(2, uuid);
                debit.setBigDecimal(3, operation.request().totalPrice());
                if (debit.executeUpdate() != 1) { rollback(connection); return Status.INSUFFICIENT_FUNDS; }
            }
            long orderId;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO order_listings (create_operation_id,creator_uuid,item_key,amount_total,amount_filled,amount_reserved,unit_price,status,expires_at) "
                        + "VALUES (?,?,?,?,0,0,?,'ACTIVE',TIMESTAMPADD(DAY,80,CURRENT_TIMESTAMP))", Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, operationId.toString()); insert.setString(2, uuid);
                insert.setString(3, operation.request().itemKey()); insert.setInt(4, operation.request().itemCount());
                insert.setDouble(5, terms.unitPrice().doubleValue());
                if (insert.executeUpdate() != 1) throw new SQLException("order listing was not inserted");
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("order listing id was not returned");
                    orderId = keys.getLong(1);
                }
            }
            try (PreparedStatement stat = connection.prepareStatement(
                    "INSERT INTO player_stats (uuid,orders_created) VALUES (?,1) ON DUPLICATE KEY UPDATE orders_created=orders_created+1")) {
                stat.setString(1, uuid); stat.executeUpdate();
            }
            audit(connection, operation, "ORDER_CREATE", operation.request().totalPrice(),
                "operation=" + operationId + ";order=" + orderId + ";escrow=true");
            try (PreparedStatement finish = connection.prepareStatement(
                    "UPDATE economy_market_operations SET phase=?,result_detail=?,gateway_terminal_ack=1,gateway_terminal_forgotten=1,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE operation_id=? AND phase=?")) {
                finish.setString(1, Phase.RELEASED.name()); finish.setString(2, "order_id=" + orderId);
                finish.setString(3, operationId.toString()); finish.setString(4, Phase.PREPARED.name());
                if (finish.executeUpdate() != 1) throw new SQLException("order create journal phase changed");
            }
            commitAttempted = true;
            connection.commit();
            return Status.APPLIED;
        } catch (SQLException failure) {
            if (commitAttempted) {
                close(connection); connection = null;
                try { Operation current = find(operationId); if (current != null && current.phase() == Phase.RELEASED) return Status.DUPLICATE; }
                catch (RuntimeException ignored) { }
                return Status.UNKNOWN;
            }
            if (transaction) rollback(connection);
            return Status.UNKNOWN;
        } finally { close(connection); }
    }

    /** Cancels one order and returns only unfilled, unreserved escrow atomically. */
    public Status cancelOrder(UUID operationId) {
        if (operationId == null) return Status.INVALID;
        Connection connection = null;
        boolean transaction = false;
        boolean commitAttempted = false;
        try {
            connection = connections.open();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            transaction = true;
            Operation operation = selectForUpdate(connection, operationId);
            if (operation == null) { rollback(connection); return Status.NOT_FOUND; }
            if (!isOrderCancel(operation.request().operationKind())) { rollback(connection); return Status.CONFLICT; }
            if (operation.phase() == Phase.RELEASED) { rollback(connection); return Status.DUPLICATE; }
            if (operation.phase() != Phase.PREPARED) { rollback(connection); return Status.CONFLICT; }
            long orderId = prefixedId(operation.request().itemKey(), "ORDER_CANCEL:");
            int remaining, reserved;
            BigDecimal unitPrice;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT amount_total,amount_filled,amount_reserved,unit_price FROM order_listings "
                        + "WHERE id=? AND creator_uuid=? AND status='ACTIVE' FOR UPDATE")) {
                query.setLong(1, orderId); query.setString(2, operation.request().playerId().toString());
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) { rollback(connection); return Status.NOT_FOUND; }
                    int total = row.getInt(1), filled = row.getInt(2); reserved = row.getInt(3);
                    unitPrice = BigDecimal.valueOf(row.getDouble(4)).setScale(2, RoundingMode.HALF_UP);
                    remaining = Math.max(0, total - filled - reserved);
                }
            }
            try (PreparedStatement cancel = connection.prepareStatement(
                    "UPDATE order_listings SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND creator_uuid=? AND status='ACTIVE'")) {
                cancel.setLong(1, orderId); cancel.setString(2, operation.request().playerId().toString());
                if (cancel.executeUpdate() != 1) { rollback(connection); return Status.CONFLICT; }
            }
            BigDecimal refund = unitPrice.multiply(BigDecimal.valueOf(remaining)).setScale(2, RoundingMode.HALF_UP);
            if (refund.signum() > 0) {
                try (PreparedStatement credit = connection.prepareStatement(
                        "INSERT INTO balances (uuid,money) VALUES (?,?) ON DUPLICATE KEY UPDATE money=money+VALUES(money)")) {
                    credit.setString(1, operation.request().playerId().toString()); credit.setBigDecimal(2, refund); credit.executeUpdate();
                }
            }
            auditSpecific(connection, operation, "ORDER_CANCEL", "ORDER", remaining, refund,
                "operation=" + operationId + ";order=" + orderId + ";reserved=" + reserved);
            try (PreparedStatement finish = connection.prepareStatement(
                    "UPDATE economy_market_operations SET phase=?,result_detail=?,gateway_terminal_ack=1,gateway_terminal_forgotten=1,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE operation_id=? AND phase=?")) {
                finish.setString(1, Phase.RELEASED.name());
                finish.setString(2, "order_id=" + orderId + ";refund=" + refund.toPlainString());
                finish.setString(3, operationId.toString()); finish.setString(4, Phase.PREPARED.name());
                if (finish.executeUpdate() != 1) throw new SQLException("order cancel journal phase changed");
            }
            commitAttempted = true;
            connection.commit();
            return Status.APPLIED;
        } catch (SQLException failure) {
            if (commitAttempted) {
                close(connection); connection = null;
                try { Operation current = find(operationId); if (current != null && current.phase() == Phase.RELEASED) return Status.DUPLICATE; }
                catch (RuntimeException ignored) { }
                return Status.UNKNOWN;
            }
            if (transaction) rollback(connection);
            return Status.UNKNOWN;
        } finally { close(connection); }
    }

    /** Bind the journal to the exact active backend connection returned by Canopy. */
    public Status markLeased(UUID operationId, UUID backendConnectionId, String backend,
                             long preRevision, String preDigest) {
        if (operationId == null || backendConnectionId == null || backend == null || backend.isBlank()
                || preRevision < 0 || !isSha256(preDigest)) return Status.INVALID;
        String normalized = preDigest.toLowerCase(java.util.Locale.ROOT);
        String sql = "UPDATE economy_market_operations SET phase=?,backend_connection_id=?,backend_name=?,pre_revision=?,pre_digest=?,updated_at=CURRENT_TIMESTAMP "
            + "WHERE operation_id=? AND phase=? AND backend_name=?";
        try (Connection connection = connections.open(); PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, Phase.LEASED.name());
            update.setString(2, backendConnectionId.toString());
            update.setString(3, backend);
            update.setLong(4, preRevision);
            update.setString(5, normalized);
            update.setString(6, operationId.toString());
            update.setString(7, Phase.PREPARED.name());
            update.setString(8, backend);
            if (update.executeUpdate() == 1) return Status.APPLIED;
            Operation current = find(operationId);
            if (current == null) return Status.NOT_FOUND;
            if (current.phase() == Phase.LEASED && backendConnectionId.equals(current.backendConnectionId())
                    && backend.equals(current.request().backend()) && current.preRevision() == preRevision
                    && normalized.equals(current.preDigest())) return Status.DUPLICATE;
            return Status.CONFLICT;
        } catch (SQLException failure) {
            try {
                Operation current = find(operationId);
                if (current != null && current.phase() == Phase.LEASED
                        && backendConnectionId.equals(current.backendConnectionId())
                        && backend.equals(current.request().backend()) && current.preRevision() == preRevision
                        && normalized.equals(current.preDigest())) return Status.DUPLICATE;
            } catch (RuntimeException ignored) { }
            return Status.UNKNOWN;
        }
    }

    /** Reserve money and any marketplace row lock before mutating inventory; these changes commit together. */
    public Status reserveShopPurchase(UUID operationId) {
        if (operationId == null) return Status.INVALID;
        Connection connection = null;
        boolean transaction = false;
        boolean commitAttempted = false;
        try {
            connection = connections.open();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            transaction = true;
            Operation operation = selectForUpdate(connection, operationId);
            if (operation == null) { rollback(connection); return Status.NOT_FOUND; }
            if (!isJournaledItemKind(operation.request().operationKind())) { rollback(connection); return Status.CONFLICT; }
            if (operation.phase() == Phase.FUNDS_RESERVED) { rollback(connection); return Status.DUPLICATE; }
            if (operation.phase() != Phase.LEASED) { rollback(connection); return Status.CONFLICT; }
            if (isShopSell(operation) && operation.request().totalPrice().signum() <= 0) {
                rollback(connection); return Status.INVALID;
            }
            if (isAuctionList(operation) && operation.request().totalPrice().signum() <= 0
                    || isAuctionCancel(operation) && operation.request().totalPrice().signum() != 0) {
                rollback(connection); return Status.INVALID;
            }
            if (isOrderFill(operation) && operation.request().totalPrice().signum() <= 0
                    || (isOrderClaim(operation) || isMailboxClaim(operation))
                        && operation.request().totalPrice().signum() != 0) {
                rollback(connection); return Status.INVALID;
            }

            AuctionListing auction = null;
            if (isAuctionBuy(operation)) {
                auction = selectAuctionListing(connection, operation);
                if (auction == null || auction.sellerUuid().equalsIgnoreCase(operation.request().playerId().toString())) {
                    rollback(connection); return Status.CONFLICT;
                }
            } else if (isAuctionCancel(operation)) {
                auction = selectAuctionCancelListing(connection, operation);
                if (auction == null) { rollback(connection); return Status.CONFLICT; }
            }

            long reservedListingId = 0;
            if (isAuctionList(operation)) {
                if (operation.request().marketLimit() < 1) { rollback(connection); return Status.INVALID; }
                if (!lockSellerListingCapacity(connection, operation.request().playerId(), operation.request().marketLimit())) {
                    rollback(connection); return Status.LIMIT_REACHED;
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO auction_listings (seller_uuid,buyer_uuid,lock_operation_id,item_blob,item_key,price,status,expires_at) "
                            + "VALUES (?,NULL,?,?,?,?,'PREPARING',TIMESTAMPADD(DAY,7,CURRENT_TIMESTAMP))",
                        Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, operation.request().playerId().toString());
                    insert.setString(2, operationId.toString());
                    insert.setBytes(3, operation.request().itemPayload());
                    insert.setString(4, operation.request().itemKey());
                    insert.setDouble(5, operation.request().totalPrice().doubleValue());
                    if (insert.executeUpdate() != 1) throw new SQLException("auction listing capacity reservation was not applied");
                    try (ResultSet keys = insert.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("auction listing reservation id was not returned");
                        reservedListingId = keys.getLong(1);
                    }
                }
            }

            Status protectedRow = Status.APPLIED;
            if (isOrderFill(operation)) protectedRow = reserveOrderFill(connection, operation);
            else if (isOrderClaim(operation)) protectedRow = reserveOrderDeliveryClaim(connection, operation);
            else if (isMailboxClaim(operation)) protectedRow = reserveMailboxClaim(connection, operation);
            if (protectedRow != Status.APPLIED) { rollback(connection); return protectedRow; }

            if (!isAuctionList(operation) && !isAuctionCancel(operation) && !isShopSell(operation)
                    && !isOrderFill(operation) && !isOrderClaim(operation) && !isMailboxClaim(operation)) {
                try (PreparedStatement debit = connection.prepareStatement(
                        "UPDATE balances SET money=money-? WHERE uuid=? AND money>=?")) {
                    debit.setBigDecimal(1, operation.request().totalPrice());
                    debit.setString(2, operation.request().playerId().toString());
                    debit.setBigDecimal(3, operation.request().totalPrice());
                    if (debit.executeUpdate() != 1) { rollback(connection); return Status.INSUFFICIENT_FUNDS; }
                }
            }
            if (auction != null && isAuctionBuy(operation)) {
                try (PreparedStatement lock = connection.prepareStatement(
                        "UPDATE auction_listings SET status='BUYING',buyer_uuid=?,lock_operation_id=?,updated_at=CURRENT_TIMESTAMP "
                            + "WHERE id=? AND status='ACTIVE' AND lock_operation_id IS NULL")) {
                    lock.setString(1, operation.request().playerId().toString());
                    lock.setString(2, operation.request().operationId().toString());
                    lock.setLong(3, auction.listingId());
                    if (lock.executeUpdate() != 1) throw new SQLException("auction listing lock changed");
                }
            } else if (auction != null && isAuctionCancel(operation)) {
                try (PreparedStatement lock = connection.prepareStatement(
                        "UPDATE auction_listings SET status='CANCELLING',buyer_uuid=NULL,lock_operation_id=?,updated_at=CURRENT_TIMESTAMP "
                            + "WHERE id=? AND seller_uuid=? AND status='ACTIVE' AND lock_operation_id IS NULL")) {
                    lock.setString(1, operationId.toString()); lock.setLong(2, auction.listingId());
                    lock.setString(3, operation.request().playerId().toString());
                    if (lock.executeUpdate() != 1) throw new SQLException("auction cancellation lock changed");
                }
            }
            String reservedEvent = isOrderFill(operation) ? "ORDER_FILL_RESERVED"
                : isOrderClaim(operation) ? "ORDER_CLAIM_RESERVED"
                : isMailboxClaim(operation) ? "MAILBOX_CLAIM_RESERVED"
                : isShopSell(operation) ? "SELL_RESERVED"
                : isAuctionList(operation) ? "AH_LIST_RESERVED"
                : isAuctionCancel(operation) ? "AH_CANCEL_RESERVED"
                : isAuctionBuy(operation) ? "AH_BUY_RESERVED"
                : isPvp(operation) ? "PVP_BUY_RESERVED" : "SHOP_BUY_RESERVED";
            audit(connection, operation, reservedEvent, operation.request().totalPrice(),
                isAuctionList(operation) ? "phase=item_lease;listing=" + reservedListingId : "phase=reserved");
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE economy_market_operations SET phase=?,result_detail=COALESCE(?,result_detail),updated_at=CURRENT_TIMESTAMP "
                        + "WHERE operation_id=? AND phase=?")) {
                update.setString(1, Phase.FUNDS_RESERVED.name());
                update.setString(2, isAuctionList(operation) ? "listing_id=" + reservedListingId : null);
                update.setString(3, operationId.toString());
                update.setString(4, Phase.LEASED.name());
                if (update.executeUpdate() != 1) throw new SQLException("market reserve phase changed");
            }
            commitAttempted = true;
            connection.commit();
            return Status.APPLIED;
        } catch (SQLException failure) {
            if (commitAttempted) return Status.UNKNOWN;
            if (transaction) rollback(connection);
            return Status.UNKNOWN;
        } finally {
            close(connection);
        }
    }

    private static Status reserveOrderFill(Connection connection, Operation operation) throws SQLException {
        List<SellAuditLine> lines;
        try { lines = decodeShopSellPayload(operation.request()); }
        catch (IllegalArgumentException invalid) { return Status.INVALID; }
        if (lines.size() != 1) return Status.INVALID;
        SellAuditLine line = lines.get(0);
        long orderId = orderFillId(operation.request().itemKey());
        String creator;
        BigDecimal unitPrice;
        int available;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT item_key,amount_total,amount_filled,amount_reserved,unit_price,creator_uuid "
                    + "FROM order_listings WHERE id=? AND status='ACTIVE' "
                    + "AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) FOR UPDATE")) {
            query.setLong(1, orderId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return Status.NOT_FOUND;
                String itemKey = rows.getString(1);
                int total = rows.getInt(2), filled = rows.getInt(3), reserved = rows.getInt(4);
                unitPrice = BigDecimal.valueOf(rows.getDouble(5)).setScale(2, RoundingMode.HALF_UP);
                creator = rows.getString(6);
                if (itemKey == null || !itemKey.equalsIgnoreCase(line.itemKey()) || creator == null
                        || creator.equalsIgnoreCase(operation.request().playerId().toString())
                        || creator.equalsIgnoreCase(SYSTEM_SELLER_UUID)
                        || unitPrice.compareTo(line.unitPrice()) != 0
                        || unitPrice.multiply(BigDecimal.valueOf(line.amount())).compareTo(operation.request().totalPrice()) != 0)
                    return Status.CONFLICT;
                available = total - filled - reserved;
                if (available < line.amount()) return Status.LIMIT_REACHED;
            }
        }
        try (PreparedStatement reserve = connection.prepareStatement(
                "UPDATE order_listings SET amount_reserved=amount_reserved+?,updated_at=CURRENT_TIMESTAMP "
                    + "WHERE id=? AND status='ACTIVE' AND amount_total-amount_filled-amount_reserved>=?")) {
            reserve.setInt(1, line.amount()); reserve.setLong(2, orderId); reserve.setInt(3, line.amount());
            if (reserve.executeUpdate() != 1) return Status.CONFLICT;
        }
        try (PreparedStatement delivery = connection.prepareStatement(
                "INSERT INTO order_deliveries (operation_id,lock_operation_id,order_id,fulfiller_uuid,amount,payout,item_blob,claimed) "
                    + "VALUES (?,?,?,?,?,?,?,0)")) {
            delivery.setString(1, operation.request().operationId().toString());
            delivery.setString(2, operation.request().operationId().toString());
            delivery.setLong(3, orderId);
            delivery.setString(4, operation.request().playerId().toString());
            delivery.setInt(5, line.amount());
            delivery.setDouble(6, operation.request().totalPrice().doubleValue());
            delivery.setBytes(7, line.itemPayload());
            if (delivery.executeUpdate() != 1) throw new SQLException("order fill delivery reservation was not inserted");
        }
        return Status.APPLIED;
    }

    private static Status reserveOrderDeliveryClaim(Connection connection, Operation operation) throws SQLException {
        long deliveryId = prefixedId(operation.request().itemKey(), "ORDER_DELIVERY:");
        byte[] itemPayload;
        int amount;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT d.item_blob,d.amount FROM order_deliveries d JOIN order_listings o ON o.id=d.order_id "
                    + "WHERE d.id=? AND o.creator_uuid=? AND d.claimed=0 AND d.lock_operation_id IS NULL FOR UPDATE")) {
            query.setLong(1, deliveryId);
            query.setString(2, operation.request().playerId().toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return Status.NOT_FOUND;
                itemPayload = rows.getBytes(1); amount = rows.getInt(2);
            }
        }
        if (itemPayload == null || amount < operation.request().itemCount()
                || !Arrays.equals(itemPayload, operation.request().itemPayload())) return Status.CONFLICT;
        try (PreparedStatement lock = connection.prepareStatement(
                "UPDATE order_deliveries SET lock_operation_id=? WHERE id=? AND claimed=0 AND lock_operation_id IS NULL")) {
            lock.setString(1, operation.request().operationId().toString()); lock.setLong(2, deliveryId);
            return lock.executeUpdate() == 1 ? Status.APPLIED : Status.CONFLICT;
        }
    }

    private static Status reserveMailboxClaim(Connection connection, Operation operation) throws SQLException {
        long mailboxId = prefixedId(operation.request().itemKey(), "MAILBOX:");
        byte[] itemPayload;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT item_blob FROM player_mailbox WHERE id=? AND uuid=? AND lock_operation_id IS NULL FOR UPDATE")) {
            query.setLong(1, mailboxId); query.setString(2, operation.request().playerId().toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return Status.NOT_FOUND;
                itemPayload = rows.getBytes(1);
            }
        }
        if (itemPayload == null || !Arrays.equals(itemPayload, operation.request().itemPayload())) return Status.CONFLICT;
        try (PreparedStatement lock = connection.prepareStatement(
                "UPDATE player_mailbox SET lock_operation_id=? WHERE id=? AND uuid=? AND lock_operation_id IS NULL")) {
            lock.setString(1, operation.request().operationId().toString());
            lock.setLong(2, mailboxId); lock.setString(3, operation.request().playerId().toString());
            return lock.executeUpdate() == 1 ? Status.APPLIED : Status.CONFLICT;
        }
    }

    /** Record a durably acknowledged post-mutation Canopy checkpoint. */
    public Status markItemCheckpointed(UUID operationId, long revision, String digest) {
        if (operationId == null || revision < 0 || !isSha256(digest)) return Status.INVALID;
        String normalized = digest.toLowerCase(java.util.Locale.ROOT);
        String sql = "UPDATE economy_market_operations SET phase=?,post_revision=?,post_digest=?,updated_at=CURRENT_TIMESTAMP "
            + "WHERE operation_id=? AND phase=?";
        try (Connection connection = connections.open(); PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, Phase.ITEM_CHECKPOINTED.name());
            update.setLong(2, revision);
            update.setString(3, normalized);
            update.setString(4, operationId.toString());
            update.setString(5, Phase.FUNDS_RESERVED.name());
            if (update.executeUpdate() == 1) return Status.APPLIED;
            Operation current = find(operationId);
            if (current == null) return Status.NOT_FOUND;
            return current.phase() == Phase.ITEM_CHECKPOINTED && current.postRevision() == revision
                    && normalized.equals(current.postDigest()) ? Status.DUPLICATE : Status.CONFLICT;
        } catch (SQLException failure) {
            try {
                Operation current = find(operationId);
                if (current != null && current.phase() == Phase.ITEM_CHECKPOINTED
                        && current.postRevision() == revision && normalized.equals(current.postDigest())) return Status.DUPLICATE;
            } catch (RuntimeException ignored) { }
            return Status.UNKNOWN;
        }
    }

    /**
     * Atomically finalize the item operation, balances/listing, statistics, audit receipt and journal state.
     * Replaying after an ambiguous commit reads SETTLED and does not repeat the debit, payout or item grant.
     */
    public Status settleShopPurchase(UUID operationId, String serverName) {
        if (operationId == null || serverName == null || serverName.isBlank() || serverName.length() > 32) return Status.INVALID;
        Connection connection = null;
        boolean transaction = false;
        boolean commitAttempted = false;
        try {
            connection = connections.open();
            connection.setAutoCommit(false);
            transaction = true;
            Operation operation = selectForUpdate(connection, operationId);
            if (operation == null) { rollback(connection); return Status.NOT_FOUND; }
            if (!isJournaledItemKind(operation.request().operationKind())) { rollback(connection); return Status.CONFLICT; }
            if (!serverName.equals(operation.request().backend())) { rollback(connection); return Status.CONFLICT; }
            if (operation.phase() == Phase.SETTLED || operation.phase() == Phase.RELEASED) { rollback(connection); return Status.DUPLICATE; }
            if (operation.phase() != Phase.ITEM_CHECKPOINTED) { rollback(connection); return Status.CONFLICT; }

            String terminalDetail = null;
            if (isAuctionList(operation)) {
                AuctionListing reservation = selectPreparedAuctionListing(connection, operation);
                if (reservation == null) { rollback(connection); return Status.CONFLICT; }
                try (PreparedStatement activate = connection.prepareStatement(
                        "UPDATE auction_listings SET status='ACTIVE',lock_operation_id=NULL,updated_at=CURRENT_TIMESTAMP "
                            + "WHERE id=? AND seller_uuid=? AND status='PREPARING' AND lock_operation_id=?")) {
                    activate.setLong(1, reservation.listingId());
                    activate.setString(2, operation.request().playerId().toString());
                    activate.setString(3, operationId.toString());
                    if (activate.executeUpdate() != 1) throw new SQLException("auction listing reservation changed before activation");
                }
                audit(connection, operation, "AH_LIST", operation.request().totalPrice(),
                    "operation=" + operationId + ";listing=" + reservation.listingId() + ";ask=" + operation.request().totalPrice().toPlainString());
                terminalDetail = "listing_id=" + reservation.listingId();
            } else if (isAuctionCancel(operation)) {
                AuctionListing auction = selectLockedAuctionCancel(connection, operation);
                if (auction == null) { rollback(connection); return Status.CONFLICT; }
                try (PreparedStatement cancel = connection.prepareStatement(
                        "UPDATE auction_listings SET status='CANCELLED',lock_operation_id=NULL,updated_at=CURRENT_TIMESTAMP "
                            + "WHERE id=? AND seller_uuid=? AND status='CANCELLING' AND lock_operation_id=?")) {
                    cancel.setLong(1, auction.listingId());
                    cancel.setString(2, operation.request().playerId().toString());
                    cancel.setString(3, operationId.toString());
                    if (cancel.executeUpdate() != 1) throw new SQLException("auction cancellation phase changed");
                }
                audit(connection, operation, "AH_CANCEL", BigDecimal.ZERO,
                    "operation=" + operationId + ";listing=" + auction.listingId());
                terminalDetail = "listing_id=" + auction.listingId();
            } else if (isAuctionBuy(operation)) {
                AuctionListing auction = selectLockedAuctionListing(connection, operation);
                if (auction == null) { rollback(connection); return Status.CONFLICT; }
                try (PreparedStatement sold = connection.prepareStatement(
                        "UPDATE auction_listings SET status='SOLD',lock_operation_id=NULL,updated_at=CURRENT_TIMESTAMP "
                            + "WHERE id=? AND status='BUYING' AND buyer_uuid=? AND lock_operation_id=?")) {
                    sold.setLong(1, auction.listingId());
                    sold.setString(2, operation.request().playerId().toString());
                    sold.setString(3, operationId.toString());
                    if (sold.executeUpdate() != 1) throw new SQLException("auction sale phase changed");
                }
                if (!SYSTEM_SELLER_UUID.equalsIgnoreCase(auction.sellerUuid())) {
                    try (PreparedStatement payout = connection.prepareStatement(
                            "INSERT INTO auction_payouts (recipient_uuid,listing_id,amount,claimed) VALUES (?,?,?,1)")) {
                        payout.setString(1, auction.sellerUuid()); payout.setLong(2, auction.listingId());
                        payout.setDouble(3, operation.request().totalPrice().doubleValue()); payout.executeUpdate();
                    }
                    try (PreparedStatement credit = connection.prepareStatement(
                            "INSERT INTO balances (uuid,money) VALUES (?,?) ON DUPLICATE KEY UPDATE money=money+VALUES(money)")) {
                        credit.setString(1, auction.sellerUuid()); credit.setBigDecimal(2, operation.request().totalPrice()); credit.executeUpdate();
                    }
                }
                audit(connection, operation, "AH_BUY", operation.request().totalPrice(),
                    "operation=" + operationId + ";listing=" + auction.listingId() + ";seller=" + auction.sellerUuid());
            } else if (isOrderFill(operation)) {
                terminalDetail = settleOrderFill(connection, operation);
            } else if (isOrderClaim(operation)) {
                settleOrderDeliveryClaim(connection, operation);
            } else if (isMailboxClaim(operation)) {
                settleMailboxClaim(connection, operation);
            } else if (isShopSell(operation)) {
                try (PreparedStatement credit = connection.prepareStatement(
                        "INSERT INTO balances (uuid,money) VALUES (?,?) ON DUPLICATE KEY UPDATE money=money+VALUES(money)")) {
                    credit.setString(1, operation.request().playerId().toString());
                    credit.setBigDecimal(2, operation.request().totalPrice());
                    credit.executeUpdate();
                }
                try (PreparedStatement stat = connection.prepareStatement(
                        "INSERT INTO player_stats (uuid,sell_earned) VALUES (?,?) ON DUPLICATE KEY UPDATE sell_earned=sell_earned+VALUES(sell_earned)")) {
                    stat.setString(1, operation.request().playerId().toString());
                    stat.setBigDecimal(2, operation.request().totalPrice());
                    stat.executeUpdate();
                }
                auditShopSellBreakdown(connection, operation);
            } else {
                try (PreparedStatement stat = connection.prepareStatement(
                        "INSERT INTO player_stats (uuid,shop_spent) VALUES (?,?) ON DUPLICATE KEY UPDATE shop_spent=shop_spent+VALUES(shop_spent)")) {
                    stat.setString(1, operation.request().playerId().toString());
                    stat.setBigDecimal(2, operation.request().totalPrice());
                    stat.executeUpdate();
                }
                audit(connection, operation, isPvp(operation) ? "PVP_BUY" : "SHOP_BUY", operation.request().totalPrice(),
                    "operation=" + operationId + ";backend=" + serverName);
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE economy_market_operations SET phase=?,result_detail=COALESCE(?,result_detail),updated_at=CURRENT_TIMESTAMP "
                        + "WHERE operation_id=? AND phase=?")) {
                update.setString(1, Phase.SETTLED.name());
                update.setString(2, terminalDetail);
                update.setString(3, operationId.toString());
                update.setString(4, Phase.ITEM_CHECKPOINTED.name());
                if (update.executeUpdate() != 1) throw new SQLException("market settlement phase changed");
            }
            commitAttempted = true;
            connection.commit();
            return Status.APPLIED;
        } catch (SQLException failure) {
            if (commitAttempted) return Status.UNKNOWN;
            if (transaction) rollback(connection);
            return Status.UNKNOWN;
        } finally {
            close(connection);
        }
    }

    private static String settleOrderFill(Connection connection, Operation operation) throws SQLException {
        List<SellAuditLine> lines;
        try { lines = decodeShopSellPayload(operation.request()); }
        catch (IllegalArgumentException invalid) { throw new SQLException("Invalid order fill payload", invalid); }
        if (lines.size() != 1) throw new SQLException("Order fill must contain one stack");
        SellAuditLine line = lines.get(0);
        long orderId = orderFillId(operation.request().itemKey());
        try (PreparedStatement delivery = connection.prepareStatement(
                "SELECT order_id,amount,item_blob FROM order_deliveries WHERE operation_id=? AND lock_operation_id=? AND claimed=0 FOR UPDATE")) {
            delivery.setString(1, operation.request().operationId().toString());
            delivery.setString(2, operation.request().operationId().toString());
            try (ResultSet rows = delivery.executeQuery()) {
                if (!rows.next() || rows.getLong(1) != orderId || rows.getInt(2) != line.amount()
                        || !Arrays.equals(rows.getBytes(3), line.itemPayload()))
                    throw new SQLException("Order fill delivery reservation changed");
            }
        }
        String creator;
        int amountTotal, amountFilled, amountReserved;
        String orderStatus;
        try (PreparedStatement order = connection.prepareStatement(
                "SELECT amount_total,amount_filled,amount_reserved,unit_price,creator_uuid,status "
                    + "FROM order_listings WHERE id=? FOR UPDATE")) {
            order.setLong(1, orderId);
            try (ResultSet rows = order.executeQuery()) {
                if (!rows.next() || rows.getInt(3) < line.amount()) throw new SQLException("Order fill capacity reservation changed");
                amountTotal = rows.getInt(1); amountFilled = rows.getInt(2); amountReserved = rows.getInt(3);
                BigDecimal price = BigDecimal.valueOf(rows.getDouble(4)).setScale(2, RoundingMode.HALF_UP);
                creator = rows.getString(5);
                orderStatus = rows.getString(6);
                if (creator == null || creator.equalsIgnoreCase(operation.request().playerId().toString())
                        || creator.equalsIgnoreCase(SYSTEM_SELLER_UUID) || !Set.of("ACTIVE", "CANCELLED").contains(orderStatus)
                        || price.compareTo(line.unitPrice()) != 0
                        || price.multiply(BigDecimal.valueOf(line.amount())).compareTo(operation.request().totalPrice()) != 0)
                    throw new SQLException("Order fill no longer matches its escrow");
            }
        }
        int settledFilled = Math.addExact(amountFilled, line.amount());
        int settledReserved = amountReserved - line.amount();
        String settledStatus = "ACTIVE".equals(orderStatus) && settledFilled >= amountTotal ? "FILLED" : orderStatus;
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE order_listings SET amount_reserved=?,amount_filled=?,status=?,updated_at=CURRENT_TIMESTAMP "
                    + "WHERE id=? AND amount_reserved=? AND amount_filled=? AND status=?")) {
            update.setInt(1, settledReserved); update.setInt(2, settledFilled); update.setString(3, settledStatus);
            update.setLong(4, orderId); update.setInt(5, amountReserved); update.setInt(6, amountFilled); update.setString(7, orderStatus);
            if (update.executeUpdate() != 1) throw new SQLException("Order fill capacity settlement changed");
        }
        try (PreparedStatement release = connection.prepareStatement(
                "UPDATE order_deliveries SET lock_operation_id=NULL WHERE operation_id=? AND lock_operation_id=? AND claimed=0")) {
            release.setString(1, operation.request().operationId().toString());
            release.setString(2, operation.request().operationId().toString());
            if (release.executeUpdate() != 1) throw new SQLException("Order delivery did not become claimable");
        }
        try (PreparedStatement credit = connection.prepareStatement(
                "INSERT INTO balances (uuid,money) VALUES (?,?) ON DUPLICATE KEY UPDATE money=money+VALUES(money)")) {
            credit.setString(1, operation.request().playerId().toString());
            credit.setBigDecimal(2, operation.request().totalPrice());
            credit.executeUpdate();
        }
        try (PreparedStatement stat = connection.prepareStatement(
                "INSERT INTO player_stats (uuid,sell_earned,orders_fulfilled) VALUES (?,?,1) "
                    + "ON DUPLICATE KEY UPDATE sell_earned=sell_earned+VALUES(sell_earned),orders_fulfilled=orders_fulfilled+1")) {
            stat.setString(1, operation.request().playerId().toString());
            stat.setBigDecimal(2, operation.request().totalPrice());
            stat.executeUpdate();
        }
        auditShopSellBreakdown(connection, operation);
        return "order_id=" + orderId;
    }

    private static void settleOrderDeliveryClaim(Connection connection, Operation operation) throws SQLException {
        long deliveryId = prefixedId(operation.request().itemKey(), "ORDER_DELIVERY:");
        int currentAmount;
        BigDecimal currentPayout;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT amount,payout FROM order_deliveries d JOIN order_listings o ON o.id=d.order_id "
                    + "WHERE d.id=? AND o.creator_uuid=? AND d.claimed=0 AND d.lock_operation_id=? FOR UPDATE")) {
            query.setLong(1, deliveryId);
            query.setString(2, operation.request().playerId().toString());
            query.setString(3, operation.request().operationId().toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Order delivery claim reservation changed");
                currentAmount = rows.getInt(1);
                currentPayout = BigDecimal.valueOf(rows.getDouble(2)).setScale(2, RoundingMode.HALF_UP);
            }
        }
        int remaining = currentAmount - operation.request().itemCount();
        if (remaining < 0) throw new SQLException("Order delivery claim amount exceeds the reserved delivery");
        BigDecimal unitPayout = currentPayout.divide(BigDecimal.valueOf(currentAmount), 2, RoundingMode.HALF_UP);
        BigDecimal claimPayout = remaining == 0 ? currentPayout
            : unitPayout.multiply(BigDecimal.valueOf(operation.request().itemCount()));
        BigDecimal remainingPayout = remaining == 0 ? BigDecimal.ZERO : unitPayout.multiply(BigDecimal.valueOf(remaining));
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE order_deliveries d JOIN order_listings o ON o.id=d.order_id "
                    + "SET d.amount=?,d.payout=?,d.claimed=?,d.claimed_at=?,d.lock_operation_id=NULL "
                    + "WHERE d.id=? AND o.creator_uuid=? AND d.claimed=0 AND d.lock_operation_id=? "
                    + "AND d.amount=? AND d.item_blob=?")) {
            update.setInt(1, remaining);
            update.setBigDecimal(2, remainingPayout);
            update.setInt(3, remaining == 0 ? 1 : 0);
            if (remaining == 0) update.setTimestamp(4, new java.sql.Timestamp(System.currentTimeMillis()));
            else update.setNull(4, java.sql.Types.TIMESTAMP);
            update.setLong(5, deliveryId);
            update.setString(6, operation.request().playerId().toString());
            update.setString(7, operation.request().operationId().toString());
            update.setInt(8, currentAmount);
            update.setBytes(9, operation.request().itemPayload());
            if (update.executeUpdate() != 1) throw new SQLException("Order delivery claim reservation changed");
        }
        auditSpecific(connection, operation, "ORDER_DELIVERY_CLAIMED", prefixedItemKey(operation.request().itemKey(), "ORDER_DELIVERY:"),
            operation.request().itemCount(), BigDecimal.ZERO, "delivery=" + deliveryId + ";payout_claimed=" + claimPayout.toPlainString()
                + ";units_remaining=" + remaining + ";payout_remaining=" + remainingPayout.toPlainString());
    }

    private static void settleMailboxClaim(Connection connection, Operation operation) throws SQLException {
        long mailboxId = prefixedId(operation.request().itemKey(), "MAILBOX:");
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM player_mailbox WHERE id=? AND uuid=? AND lock_operation_id=? AND item_blob=?")) {
            delete.setLong(1, mailboxId);
            delete.setString(2, operation.request().playerId().toString());
            delete.setString(3, operation.request().operationId().toString());
            delete.setBytes(4, operation.request().itemPayload());
            if (delete.executeUpdate() != 1) throw new SQLException("Mailbox claim reservation changed");
        }
        auditSpecific(connection, operation, "MAILBOX_CLAIM", prefixedItemKey(operation.request().itemKey(), "MAILBOX:"),
            operation.request().itemCount(), BigDecimal.ZERO, "mailbox=" + mailboxId);
    }

    /**
     * Refund a reservation only when the caller's serialized operation flow proves that inventory mutation never
     * started. The caller must persist this terminal refund before asking Canopy to release its barrier.
     */
    public Status abortUntouched(UUID operationId, boolean noMutationPerformed, String reason) {
        if (operationId == null || !noMutationPerformed || !safeReason(reason)) return Status.INVALID;
        Connection connection = null;
        boolean transaction = false;
        boolean commitAttempted = false;
        try {
            connection = connections.open();
            connection.setAutoCommit(false);
            transaction = true;
            Operation operation = selectForUpdate(connection, operationId);
            if (operation == null) { rollback(connection); return Status.NOT_FOUND; }
            if (operation.phase() == Phase.ABORTED || operation.phase() == Phase.REFUNDED) {
                rollback(connection);
                return Status.DUPLICATE;
            }
            Phase effective = operation.phase() == Phase.REVIEW ? operation.reviewFrom() : operation.phase();
            if (effective != Phase.PREPARED && effective != Phase.LEASED && effective != Phase.FUNDS_RESERVED) {
                rollback(connection); return Status.CONFLICT;
            }
            if (effective == Phase.FUNDS_RESERVED) {
                boolean customAbort = false;
                if (isOrderFill(operation)) { abortOrderFill(connection, operation, reason); customAbort = true; }
                else if (isOrderClaim(operation)) { unlockOrderDeliveryClaim(connection, operation, reason); customAbort = true; }
                else if (isMailboxClaim(operation)) { unlockMailboxClaim(connection, operation, reason); customAbort = true; }
                if (isAuctionBuy(operation)) {
                    AuctionListing auction = selectLockedAuctionListing(connection, operation);
                    if (auction == null) { rollback(connection); return Status.CONFLICT; }
                    try (PreparedStatement unlock = connection.prepareStatement(
                            "UPDATE auction_listings SET status='ACTIVE',buyer_uuid=NULL,lock_operation_id=NULL,updated_at=CURRENT_TIMESTAMP "
                                + "WHERE id=? AND status='BUYING' AND buyer_uuid=? AND lock_operation_id=?")) {
                        unlock.setLong(1, auction.listingId()); unlock.setString(2, operation.request().playerId().toString());
                        unlock.setString(3, operationId.toString());
                        if (unlock.executeUpdate() != 1) throw new SQLException("auction reservation changed before refund");
                    }
                }
                if (isAuctionCancel(operation)) {
                    try (PreparedStatement unlock = connection.prepareStatement(
                            "UPDATE auction_listings SET status='ACTIVE',lock_operation_id=NULL,updated_at=CURRENT_TIMESTAMP "
                                + "WHERE id=? AND seller_uuid=? AND status='CANCELLING' AND lock_operation_id=?")) {
                        unlock.setLong(1, auctionListingId(operation.request().itemKey()));
                        unlock.setString(2, operation.request().playerId().toString());
                        unlock.setString(3, operationId.toString());
                        if (unlock.executeUpdate() != 1) throw new SQLException("auction cancellation changed before abort");
                    }
                }
                if (isAuctionList(operation)) {
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM auction_listings WHERE seller_uuid=? AND status='PREPARING' AND lock_operation_id=?")) {
                        delete.setString(1, operation.request().playerId().toString());
                        delete.setString(2, operationId.toString());
                        if (delete.executeUpdate() != 1) throw new SQLException("auction listing capacity reservation changed before refund");
                    }
                }
                if (!isAuctionList(operation) && !isAuctionCancel(operation) && !isShopSell(operation)
                        && !isOrderFill(operation) && !isOrderClaim(operation) && !isMailboxClaim(operation)) {
                    try (PreparedStatement credit = connection.prepareStatement(
                            "INSERT INTO balances (uuid,money) VALUES (?,?) ON DUPLICATE KEY UPDATE money=money+VALUES(money)")) {
                        credit.setString(1, operation.request().playerId().toString());
                        credit.setBigDecimal(2, operation.request().totalPrice());
                        credit.executeUpdate();
                    }
                }
                String refundEvent = isOrderFill(operation) ? "ORDER_FILL_ABORTED"
                    : isOrderClaim(operation) ? "ORDER_CLAIM_ABORTED"
                    : isMailboxClaim(operation) ? "MAILBOX_CLAIM_ABORTED"
                    : isShopSell(operation) ? "SELL_ABORTED"
                    : isAuctionList(operation) ? "AH_LIST_ABORTED"
                    : isAuctionCancel(operation) ? "AH_CANCEL_ABORTED"
                    : isAuctionBuy(operation) ? "AH_BUY_REFUND"
                    : isPvp(operation) ? "PVP_BUY_REFUND" : "SHOP_BUY_REFUND";
                if (!customAbort) audit(connection, operation, refundEvent,
                    isAuctionList(operation) ? BigDecimal.ZERO : operation.request().totalPrice(),
                    "operation=" + operationId + ";reason=" + reason);
            } else {
                String rejectedEvent = isOrderFill(operation) ? "ORDER_FILL_REJECTED"
                    : isOrderClaim(operation) ? "ORDER_CLAIM_REJECTED"
                    : isMailboxClaim(operation) ? "MAILBOX_CLAIM_REJECTED"
                    : isOrderCreate(operation.request().operationKind()) ? "ORDER_CREATE_REJECTED"
                    : isOrderCancel(operation.request().operationKind()) ? "ORDER_CANCEL_REJECTED"
                    : isShopSell(operation) ? "SELL_REJECTED"
                    : isAuctionList(operation) ? "AH_LIST_ABORTED"
                    : isAuctionCancel(operation) ? "AH_CANCEL_ABORTED"
                    : isAuctionBuy(operation) ? "AH_BUY_REJECTED"
                    : isPvp(operation) ? "PVP_BUY_REJECTED" : "SHOP_BUY_REJECTED";
                audit(connection, operation, rejectedEvent, BigDecimal.ZERO,
                    "operation=" + operationId + ";reason=" + reason);
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE economy_market_operations SET phase=?,result_detail=?, "
                        + "gateway_terminal_ack=CASE WHEN operation_kind IN ('ORDER_CREATE','ORDER_CANCEL') THEN 1 ELSE gateway_terminal_ack END, "
                        + "gateway_terminal_forgotten=CASE WHEN operation_kind IN ('ORDER_CREATE','ORDER_CANCEL') THEN 1 ELSE gateway_terminal_forgotten END, "
                        + "updated_at=CURRENT_TIMESTAMP WHERE operation_id=? AND phase=?")) {
                update.setString(1, Phase.REFUNDED.name());
                update.setString(2, reason);
                update.setString(3, operationId.toString());
                update.setString(4, operation.phase().name());
                if (update.executeUpdate() != 1) throw new SQLException("market abort phase changed");
            }
            commitAttempted = true;
            connection.commit();
            return Status.APPLIED;
        } catch (SQLException failure) {
            if (commitAttempted) return Status.UNKNOWN;
            if (transaction) rollback(connection);
            return Status.UNKNOWN;
        } finally {
            close(connection);
        }
    }

    private static void abortOrderFill(Connection connection, Operation operation, String reason) throws SQLException {
        long orderId = orderFillId(operation.request().itemKey());
        int amount;
        try (PreparedStatement delivery = connection.prepareStatement(
                "SELECT order_id,amount FROM order_deliveries WHERE operation_id=? AND lock_operation_id=? AND claimed=0 FOR UPDATE")) {
            delivery.setString(1, operation.request().operationId().toString());
            delivery.setString(2, operation.request().operationId().toString());
            try (ResultSet rows = delivery.executeQuery()) {
                if (!rows.next() || rows.getLong(1) != orderId) throw new SQLException("Order fill reservation is missing");
                amount = rows.getInt(2);
            }
        }
        String creator;
        String status;
        boolean expired;
        BigDecimal unitPrice;
        try (PreparedStatement order = connection.prepareStatement(
                "SELECT amount_reserved,unit_price,creator_uuid,status,(expires_at IS NOT NULL AND expires_at<=CURRENT_TIMESTAMP) "
                    + "FROM order_listings WHERE id=? FOR UPDATE")) {
            order.setLong(1, orderId);
            try (ResultSet rows = order.executeQuery()) {
                if (!rows.next() || rows.getInt(1) < amount) throw new SQLException("Order escrow reservation is missing");
                unitPrice = BigDecimal.valueOf(rows.getDouble(2)).setScale(2, RoundingMode.HALF_UP);
                creator = rows.getString(3);
                status = rows.getString(4);
                expired = rows.getBoolean(5);
            }
        }
        try (PreparedStatement release = connection.prepareStatement(
                "UPDATE order_listings SET amount_reserved=amount_reserved-?, "
                    + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND amount_reserved>=?")) {
            release.setInt(1, amount); release.setLong(2, orderId); release.setInt(3, amount);
            if (release.executeUpdate() != 1) throw new SQLException("Order escrow reservation could not be released");
        }
        try (PreparedStatement retire = connection.prepareStatement(
                "UPDATE order_deliveries SET claimed=2,claimed_at=CURRENT_TIMESTAMP,lock_operation_id=NULL,item_blob=NULL,payout=0 "
                    + "WHERE operation_id=? AND lock_operation_id=? AND claimed=0")) {
            retire.setString(1, operation.request().operationId().toString());
            retire.setString(2, operation.request().operationId().toString());
            if (retire.executeUpdate() != 1) throw new SQLException("Order fill receipt could not be retired");
        }
        SellAuditLine line = null;
        try {
            List<SellAuditLine> lines = decodeShopSellPayload(operation.request());
            if (lines.size() == 1) line = lines.get(0);
        } catch (IllegalArgumentException ignored) { }
        auditSpecific(connection, operation, "ORDER_FILL_ABORTED", line == null ? "UNKNOWN" : line.itemKey(),
            amount, BigDecimal.ZERO, "operation=" + operation.request().operationId() + ";order=" + orderId + ";reason=" + reason);
        if ("CANCELLED".equals(status) || expired) {
            if (creator == null) throw new SQLException("Order creator is missing during escrow refund");
            BigDecimal refund = unitPrice.multiply(BigDecimal.valueOf(amount)).setScale(2, RoundingMode.HALF_UP);
            try (PreparedStatement credit = connection.prepareStatement(
                    "INSERT INTO balances (uuid,money) VALUES (?,?) ON DUPLICATE KEY UPDATE money=money+VALUES(money)")) {
                credit.setString(1, creator); credit.setBigDecimal(2, refund); credit.executeUpdate();
            }
            auditForPlayer(connection, operation, UUID.fromString(creator), orderOwnerName(connection, creator),
                "ORDER_FILL_ESCROW_REFUND", "ORDER", amount, refund,
                "operation=" + operation.request().operationId() + ";order=" + orderId + ";reason=" + reason);
        }
    }

    private static void unlockOrderDeliveryClaim(Connection connection, Operation operation, String reason) throws SQLException {
        long deliveryId = prefixedId(operation.request().itemKey(), "ORDER_DELIVERY:");
        try (PreparedStatement unlock = connection.prepareStatement(
                "UPDATE order_deliveries d JOIN order_listings o ON o.id=d.order_id "
                    + "SET d.lock_operation_id=NULL WHERE d.id=? AND o.creator_uuid=? AND d.claimed=0 AND d.lock_operation_id=?")) {
            unlock.setLong(1, deliveryId);
            unlock.setString(2, operation.request().playerId().toString());
            unlock.setString(3, operation.request().operationId().toString());
            if (unlock.executeUpdate() != 1) throw new SQLException("Order delivery claim reservation could not be released");
        }
        auditSpecific(connection, operation, "ORDER_CLAIM_ABORTED", prefixedItemKey(operation.request().itemKey(), "ORDER_DELIVERY:"),
            operation.request().itemCount(), BigDecimal.ZERO,
            "operation=" + operation.request().operationId() + ";reason=" + reason);
    }

    private static void unlockMailboxClaim(Connection connection, Operation operation, String reason) throws SQLException {
        long mailboxId = prefixedId(operation.request().itemKey(), "MAILBOX:");
        try (PreparedStatement unlock = connection.prepareStatement(
                "UPDATE player_mailbox SET lock_operation_id=NULL WHERE id=? AND uuid=? AND lock_operation_id=?")) {
            unlock.setLong(1, mailboxId);
            unlock.setString(2, operation.request().playerId().toString());
            unlock.setString(3, operation.request().operationId().toString());
            if (unlock.executeUpdate() != 1) throw new SQLException("Mailbox claim reservation could not be released");
        }
        auditSpecific(connection, operation, "MAILBOX_CLAIM_ABORTED", prefixedItemKey(operation.request().itemKey(), "MAILBOX:"),
            operation.request().itemCount(), BigDecimal.ZERO,
            "operation=" + operation.request().operationId() + ";reason=" + reason);
    }

    /** Complete the terminal abort only after Canopy acknowledges release of the untouched lease. */
    public Status completeAbort(UUID operationId) {
        return transition(operationId, Phase.REFUNDED, Phase.ABORTED);
    }

    /** Keep uncertain operations visible and nonterminal; never release money or inventory here. */
    public Status markReview(UUID operationId, String reason) {
        if (operationId == null || !safeReason(reason)) return Status.INVALID;
        // MariaDB/MySQL evaluates single-table SET assignments from left to right. Capture the old phase
        // before replacing it with REVIEW so restart recovery can make a decision from durable evidence.
        String sql = "UPDATE economy_market_operations SET review_from_phase=phase,phase=?,result_detail=?,updated_at=CURRENT_TIMESTAMP "
            + "WHERE operation_id=? AND phase NOT IN (?,?,?)";
        try (Connection connection = connections.open(); PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, Phase.REVIEW.name());
            update.setString(2, reason);
            update.setString(3, operationId.toString());
            update.setString(4, Phase.RELEASED.name());
            update.setString(5, Phase.ABORTED.name());
            update.setString(6, Phase.REVIEW.name());
            if (update.executeUpdate() == 1) return Status.APPLIED;
            Operation current = find(operationId);
            if (current == null) return Status.NOT_FOUND;
            return current.phase() == Phase.REVIEW && reason.equals(current.detail()) ? Status.DUPLICATE : Status.CONFLICT;
        } catch (SQLException failure) {
            try {
                Operation current = find(operationId);
                if (current != null && current.phase() == Phase.REVIEW && reason.equals(current.detail())) return Status.DUPLICATE;
            } catch (RuntimeException ignored) { }
            return Status.UNKNOWN;
        }
    }

    /** Restore a REVIEW row to a phase justified by the durable gateway snapshot. */
    public Status resumeReviewWithPostCheckpoint(UUID operationId, long revision, String digest) {
        if (operationId == null || revision < 0 || !isSha256(digest)) return Status.INVALID;
        String normalized = digest.toLowerCase(java.util.Locale.ROOT);
        try (Connection connection = connections.open(); PreparedStatement update = connection.prepareStatement(
                "UPDATE economy_market_operations SET phase=?,post_revision=?,post_digest=?,review_from_phase=NULL,result_detail=NULL,updated_at=CURRENT_TIMESTAMP "
                    + "WHERE operation_id=? AND phase=? AND review_from_phase IN (?,?)")) {
            update.setString(1, Phase.ITEM_CHECKPOINTED.name());
            update.setLong(2, revision); update.setString(3, normalized); update.setString(4, operationId.toString());
            update.setString(5, Phase.REVIEW.name()); update.setString(6, Phase.FUNDS_RESERVED.name());
            update.setString(7, Phase.ITEM_CHECKPOINTED.name());
            if (update.executeUpdate() == 1) return Status.APPLIED;
            Operation current = find(operationId);
            if (current == null) return Status.NOT_FOUND;
            return current.phase() == Phase.ITEM_CHECKPOINTED && current.postRevision() == revision
                    && normalized.equals(current.postDigest()) ? Status.DUPLICATE : Status.CONFLICT;
        } catch (SQLException failure) { return Status.UNKNOWN; }
    }

    /** Mark the journal released only after the Canopy finish receipt is confirmed. */
    public Status markReleased(UUID operationId) {
        return transition(operationId, Phase.SETTLED, Phase.RELEASED);
    }

    public Operation find(UUID operationId) {
        if (operationId == null) return null;
        try (Connection connection = connections.open(); PreparedStatement query = connection.prepareStatement(
                "SELECT " + SELECT_COLUMNS + " FROM economy_market_operations WHERE operation_id=?")) {
            query.setString(1, operationId.toString());
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? read(rows) : null; }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read market operation", failure);
        }
    }

    public Operation findUnresolvedForPlayer(UUID playerId) {
        if (playerId == null) return null;
        String sql = "SELECT " + SELECT_COLUMNS + " FROM economy_market_operations WHERE player_uuid=? AND phase NOT IN (?,?) "
            + "ORDER BY updated_at,operation_id LIMIT 1";
        try (Connection connection = connections.open(); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, playerId.toString());
            query.setString(2, Phase.RELEASED.name());
            query.setString(3, Phase.ABORTED.name());
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? read(rows) : null; }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read unresolved player market operation", failure);
        }
    }

    public Operation findPendingGatewayTerminal(UUID playerId) {
        if (playerId == null) return null;
        String sql = "SELECT " + SELECT_COLUMNS + " FROM economy_market_operations WHERE player_uuid=? "
            + "AND phase IN (?,?) AND gateway_terminal_ack=0 ORDER BY updated_at,operation_id LIMIT 1";
        try (Connection connection = connections.open(); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, playerId.toString()); query.setString(2, Phase.RELEASED.name()); query.setString(3, Phase.ABORTED.name());
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? read(rows) : null; }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read pending Canopy terminal receipt", failure);
        }
    }

    /** PNC has durably acknowledged the terminal operation, but Gateway still owes receipt cleanup. */
    public Operation findPendingGatewayForget(UUID playerId) {
        if (playerId == null) return null;
        String sql = "SELECT " + SELECT_COLUMNS + " FROM economy_market_operations WHERE player_uuid=? "
            + "AND phase IN (?,?) AND gateway_terminal_ack=1 AND gateway_terminal_forgotten=0 ORDER BY updated_at,operation_id LIMIT 1";
        try (Connection connection = connections.open(); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, playerId.toString()); query.setString(2, Phase.RELEASED.name()); query.setString(3, Phase.ABORTED.name());
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? read(rows) : null; }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read pending Canopy receipt cleanup", failure);
        }
    }

    public Status acknowledgeGatewayTerminal(UUID operationId) {
        if (operationId == null) return Status.INVALID;
        String sql = "UPDATE economy_market_operations SET gateway_terminal_ack=1,updated_at=CURRENT_TIMESTAMP "
            + "WHERE operation_id=? AND phase IN (?,?) AND gateway_terminal_ack=0";
        try (Connection connection = connections.open(); PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, operationId.toString()); update.setString(2, Phase.RELEASED.name()); update.setString(3, Phase.ABORTED.name());
            if (update.executeUpdate() == 1) return Status.APPLIED;
            Operation current = find(operationId);
            if (current == null) return Status.NOT_FOUND;
            return current.gatewayTerminalAck() ? Status.DUPLICATE : Status.CONFLICT;
        } catch (SQLException failure) {
            try {
                Operation current = find(operationId);
                if (current != null && current.gatewayTerminalAck()) return Status.DUPLICATE;
            } catch (RuntimeException ignored) { }
            return Status.UNKNOWN;
        }
    }

    /** Mark Gateway receipt cleanup complete only after its idempotent forget response. */
    public Status markGatewayTerminalForgotten(UUID operationId) {
        if (operationId == null) return Status.INVALID;
        String sql = "UPDATE economy_market_operations SET gateway_terminal_forgotten=1,updated_at=CURRENT_TIMESTAMP "
            + "WHERE operation_id=? AND phase IN (?,?) AND gateway_terminal_ack=1 AND gateway_terminal_forgotten=0";
        try (Connection connection = connections.open(); PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, operationId.toString()); update.setString(2, Phase.RELEASED.name()); update.setString(3, Phase.ABORTED.name());
            if (update.executeUpdate() == 1) return Status.APPLIED;
            Operation current = find(operationId);
            if (current == null) return Status.NOT_FOUND;
            return current.gatewayTerminalForgotten() ? Status.DUPLICATE : Status.CONFLICT;
        } catch (SQLException failure) {
            try {
                Operation current = find(operationId);
                if (current != null && current.gatewayTerminalForgotten()) return Status.DUPLICATE;
            } catch (RuntimeException ignored) { }
            return Status.UNKNOWN;
        }
    }

    public List<Operation> unresolved(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit");
        String sql = "SELECT " + SELECT_COLUMNS + " FROM economy_market_operations WHERE phase NOT IN (?,?) "
            + "ORDER BY updated_at,operation_id LIMIT ?";
        try (Connection connection = connections.open(); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, Phase.RELEASED.name());
            query.setString(2, Phase.ABORTED.name());
            query.setInt(3, limit);
            try (ResultSet rows = query.executeQuery()) {
                List<Operation> result = new ArrayList<>();
                while (rows.next()) result.add(read(rows));
                return List.copyOf(result);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not list unresolved market operations", failure);
        }
    }

    /** Network-wide serialization guard: another backend's unfinished operation pins this player's market actions. */
    public boolean hasOtherUnresolvedForPlayer(UUID playerId, UUID currentOperationId) {
        if (playerId == null || currentOperationId == null) throw new IllegalArgumentException("operation identity");
        String sql = "SELECT 1 FROM economy_market_operations WHERE player_uuid=? AND operation_id<>? AND phase NOT IN (?,?) LIMIT 1";
        try (Connection connection = connections.open(); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, playerId.toString());
            query.setString(2, currentOperationId.toString());
            query.setString(3, Phase.RELEASED.name());
            query.setString(4, Phase.ABORTED.name());
            try (ResultSet rows = query.executeQuery()) { return rows.next(); }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not check player market-operation fence", failure);
        }
    }

    private Status transition(UUID id, Phase expected, Phase next) {
        if (id == null) return Status.INVALID;
        String sql = "UPDATE economy_market_operations SET phase=?,updated_at=CURRENT_TIMESTAMP WHERE operation_id=? AND phase=?";
        try (Connection connection = connections.open(); PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, next.name()); update.setString(2, id.toString()); update.setString(3, expected.name());
            if (update.executeUpdate() == 1) return Status.APPLIED;
            Operation current = find(id);
            if (current == null) return Status.NOT_FOUND;
            return current.phase() == next ? Status.DUPLICATE : Status.CONFLICT;
        } catch (SQLException failure) {
            try {
                Operation current = find(id);
                if (current != null && current.phase() == next) return Status.DUPLICATE;
            } catch (RuntimeException ignored) { }
            return Status.UNKNOWN;
        }
    }

    private static Operation selectForUpdate(Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT " + SELECT_COLUMNS + " FROM economy_market_operations WHERE operation_id=? FOR UPDATE")) {
            query.setString(1, operationId.toString());
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? read(rows) : null; }
        }
    }

    private static Operation read(ResultSet rows) throws SQLException {
        UUID operation = UUID.fromString(rows.getString(1));
        UUID player = UUID.fromString(rows.getString(2));
        UUID connection = rows.getString(12) == null ? null : UUID.fromString(rows.getString(12));
        int marketLimit = rows.getInt(21);
        Request request = new Request(operation, player, rows.getString(3), rows.getString(4), rows.getString(5),
            rows.getInt(6), rows.getBigDecimal(7), rows.getString(8), rows.getBytes(9), marketLimit);
        long preRevision = rows.getLong(13);
        if (rows.wasNull()) preRevision = -1;
        String preDigest = rows.getString(14);
        long postRevision = rows.getLong(15);
        if (rows.wasNull()) postRevision = -1;
        String postDigest = rows.getString(16);
        return new Operation(request, Phase.valueOf(rows.getString(11)), connection,
            preRevision, preDigest, postRevision, postDigest, rows.getString(17),
            rows.getString(18) == null ? null : Phase.valueOf(rows.getString(18)), rows.getBoolean(19), rows.getBoolean(20), marketLimit);
    }

    private static void auditShopSellBreakdown(Connection connection, Operation operation) throws SQLException {
        List<SellAuditLine> lines;
        try { lines = decodeShopSellPayload(operation.request()); }
        catch (IllegalArgumentException failure) { throw new SQLException("Invalid journaled sell audit payload", failure); }
        try (PreparedStatement audit = connection.prepareStatement(
                "INSERT INTO transaction_audit_log (player_uuid,player_name,event_type,item_key,amount,unit_price,total_price,extra_info,server_name) "
                    + "VALUES (?,?,?,?,?,?,?,?,?)")) {
            for (SellAuditLine line : lines) {
                BigDecimal lineTotal = line.unitPrice().multiply(BigDecimal.valueOf(line.amount()));
                audit.setString(1, operation.request().playerId().toString());
                audit.setString(2, operation.request().playerName());
                audit.setString(3, isOrderFill(operation) ? "ORDER_FILL" : "SELL");
                audit.setString(4, line.itemKey());
                audit.setInt(5, line.amount());
                audit.setDouble(6, line.unitPrice().doubleValue());
                audit.setDouble(7, lineTotal.doubleValue());
                String detail = "operation=" + operation.request().operationId() + ";mode="
                    + (isOrderFill(operation) ? "order" : "shop") + ";slot=" + line.slot();
                if (isOrderFill(operation)) detail += ";order=" + orderFillId(operation.request().itemKey());
                audit.setString(8, detail);
                audit.setString(9, operation.request().backend());
                if (audit.executeUpdate() != 1) throw new SQLException("sell audit insert was not applied");
            }
        }
    }

    static List<SellAuditLine> decodeShopSellPayload(Request request) {
        if (request == null || !isInventorySalePayloadKind(request.operationKind())) throw new IllegalArgumentException("wrong operation kind");
        List<SellAuditLine> lines = new ArrayList<>();
        int totalCount = 0;
        BigDecimal rawTotal = BigDecimal.ZERO;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(request.itemPayload()))) {
            if (input.readInt() != SHOP_SELL_PAYLOAD_MAGIC) throw new IOException("bad sell payload header");
            int lineCount = input.readInt();
            if (lineCount < 1 || lineCount > 36) throw new IOException("bad sell line count");
            for (int i = 0; i < lineCount; i++) {
                int slot = input.readInt();
                String itemKey = input.readUTF();
                int amount = input.readInt();
                BigDecimal unitPrice = new BigDecimal(input.readUTF());
                int itemBytes = input.readInt();
                if (slot < 0 || slot >= 36 || !itemKey.matches("[A-Z][A-Z0-9_]{0,95}")
                        || amount < 1 || amount > 32767 || unitPrice.signum() <= 0
                        || unitPrice.precision() > 18 || itemBytes < 1 || itemBytes > 256 * 1024) {
                    throw new IOException("invalid sell line");
                }
                byte[] serializedItem = input.readNBytes(itemBytes);
                if (serializedItem.length != itemBytes) throw new IOException("short sell item payload");
                totalCount = Math.addExact(totalCount, amount);
                rawTotal = rawTotal.add(unitPrice.multiply(BigDecimal.valueOf(amount)));
                lines.add(new SellAuditLine(slot, itemKey, amount, unitPrice, serializedItem));
            }
            if (input.available() != 0) throw new IOException("trailing sell payload data");
        } catch (IOException | ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid journaled sell audit payload", failure);
        }
        BigDecimal roundedTotal = rawTotal.setScale(2, RoundingMode.CEILING);
        if (totalCount != request.itemCount() || roundedTotal.compareTo(request.totalPrice()) != 0)
            throw new IllegalArgumentException("Sell payload does not match its journal request");
        return List.copyOf(lines);
    }

    static OrderCreateTerms readOrderCreateTerms(Request request) {
        if (request == null || !isOrderCreate(request.operationKind())
                || !request.itemKey().matches("[A-Z][A-Z0-9_]{0,95}")) throw new IllegalArgumentException("invalid order request");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(request.itemPayload()))) {
            if (input.readInt() != ORDER_CREATE_PAYLOAD_MAGIC) throw new IOException("bad order create payload header");
            BigDecimal unitPrice = new BigDecimal(input.readUTF()).setScale(2, RoundingMode.UNNECESSARY);
            int maxOpenOrders = input.readInt();
            if (input.available() != 0 || unitPrice.signum() <= 0 || unitPrice.precision() > 18
                    || maxOpenOrders < 1 || maxOpenOrders > 1000
                    || unitPrice.multiply(BigDecimal.valueOf(request.itemCount())).compareTo(request.totalPrice()) != 0)
                throw new IOException("invalid order create terms");
            return new OrderCreateTerms(unitPrice, maxOpenOrders);
        } catch (IOException | ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid order create payload", invalid);
        }
    }

    static record OrderCreateTerms(BigDecimal unitPrice, int maxOpenOrders) { }

    static record SellAuditLine(int slot, String itemKey, int amount, BigDecimal unitPrice, byte[] itemPayload) {
        SellAuditLine { itemPayload = itemPayload.clone(); }
        @Override public byte[] itemPayload() { return itemPayload.clone(); }
    }

    private static void audit(Connection connection, Operation operation, String event, BigDecimal total, String detail)
            throws SQLException {
        BigDecimal unit = total.divide(BigDecimal.valueOf(operation.request().itemCount()), 2, RoundingMode.HALF_UP);
        try (PreparedStatement audit = connection.prepareStatement(
                "INSERT INTO transaction_audit_log (player_uuid,player_name,event_type,item_key,amount,unit_price,total_price,extra_info,server_name) "
                    + "VALUES (?,?,?,?,?,?,?,?,?)")) {
            audit.setString(1, operation.request().playerId().toString());
            audit.setString(2, operation.request().playerName());
            audit.setString(3, event);
            audit.setString(4, operation.request().itemKey());
            audit.setInt(5, operation.request().itemCount());
            audit.setDouble(6, unit.doubleValue());
            audit.setDouble(7, total.doubleValue());
            audit.setString(8, detail);
            audit.setString(9, operation.request().backend());
            if (audit.executeUpdate() != 1) throw new SQLException("market audit insert was not applied");
        }
    }

    private static void auditSpecific(Connection connection, Operation operation, String event, String itemKey,
                                      int amount, BigDecimal total, String detail) throws SQLException {
        auditForPlayer(connection, operation, operation.request().playerId(), operation.request().playerName(),
            event, itemKey, amount, total, detail);
    }

    private static void auditForPlayer(Connection connection, Operation operation, UUID playerId, String playerName,
                                       String event, String itemKey, int amount, BigDecimal total, String detail)
            throws SQLException {
        BigDecimal unit = amount > 0 ? total.divide(BigDecimal.valueOf(amount), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        try (PreparedStatement audit = connection.prepareStatement(
                "INSERT INTO transaction_audit_log (player_uuid,player_name,event_type,item_key,amount,unit_price,total_price,extra_info,server_name) "
                    + "VALUES (?,?,?,?,?,?,?,?,?)")) {
            audit.setString(1, playerId.toString());
            audit.setString(2, playerName);
            audit.setString(3, event);
            audit.setString(4, itemKey);
            audit.setInt(5, amount);
            audit.setDouble(6, unit.doubleValue());
            audit.setDouble(7, total.doubleValue());
            audit.setString(8, detail);
            audit.setString(9, operation.request().backend());
            if (audit.executeUpdate() != 1) throw new SQLException("operation audit insert was not applied");
        }
    }

    private static String orderOwnerName(Connection connection, String creatorUuid) throws SQLException {
        try (PreparedStatement name = connection.prepareStatement("SELECT username FROM players WHERE uuid=?")) {
            name.setString(1, creatorUuid);
            try (ResultSet rows = name.executeQuery()) {
                return rows.next() && rows.getString(1) != null ? rows.getString(1) : "Unknown";
            }
        }
    }

    private static String requestDigest(Request request) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF(request.operationId().toString()); out.writeUTF(request.playerId().toString());
                out.writeUTF(request.playerName()); out.writeUTF(request.operationKind()); out.writeUTF(request.itemKey());
                out.writeInt(request.itemCount()); out.writeUTF(request.totalPrice().toPlainString()); out.writeUTF(request.backend());
                out.writeInt(request.marketLimit());
                byte[] item = request.itemPayload(); out.writeInt(item.length); out.write(item);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static BigDecimal cents(BigDecimal value) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException("totalPrice");
        BigDecimal normalized = value.setScale(2, RoundingMode.UNNECESSARY);
        if (normalized.precision() > 18) throw new IllegalArgumentException("totalPrice");
        return normalized;
    }

    private static String requireText(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(field);
        return value;
    }

    private static String requireIdentifier(String value, int max, String field) {
        String checked = requireText(value, max, field);
        if (!checked.matches("[A-Z][A-Z0-9_]*")) throw new IllegalArgumentException(field);
        return checked;
    }

    private static boolean isSha256(String value) { return value != null && value.matches("[a-fA-F0-9]{64}"); }
    public static boolean isPurchaseKind(String value) {
        return "SHOP_PURCHASE".equals(value) || "PVP_PURCHASE".equals(value)
            || "AUCTION_BUY".equals(value) || "AUCTION_LIST".equals(value) || "AUCTION_CANCEL".equals(value);
    }
    public static boolean isShopSell(String value) { return "SHOP_SELL".equals(value); }
    private static boolean isShopSell(Operation operation) { return isShopSell(operation.request().operationKind()); }
    public static boolean isOrderFill(String value) { return "ORDER_FILL".equals(value); }
    private static boolean isOrderFill(Operation operation) { return isOrderFill(operation.request().operationKind()); }
    public static boolean isOrderClaim(String value) { return "ORDER_CLAIM".equals(value); }
    private static boolean isOrderClaim(Operation operation) { return isOrderClaim(operation.request().operationKind()); }
    public static boolean isMailboxClaim(String value) { return "MAILBOX_CLAIM".equals(value); }
    private static boolean isMailboxClaim(Operation operation) { return isMailboxClaim(operation.request().operationKind()); }
    public static boolean isOrderCreate(String value) { return "ORDER_CREATE".equals(value); }
    public static boolean isOrderCancel(String value) { return "ORDER_CANCEL".equals(value); }
    public static boolean isInventorySalePayloadKind(String value) { return isShopSell(value) || isOrderFill(value); }
    public static boolean isJournaledItemKind(String value) {
        return isPurchaseKind(value) || isShopSell(value) || isOrderFill(value) || isOrderClaim(value) || isMailboxClaim(value);
    }
    private static boolean isPvp(Operation operation) { return "PVP_PURCHASE".equals(operation.request().operationKind()); }
    private static boolean isAuctionBuy(Operation operation) { return "AUCTION_BUY".equals(operation.request().operationKind()); }
    private static boolean isAuctionList(Operation operation) { return "AUCTION_LIST".equals(operation.request().operationKind()); }
    private static boolean isAuctionCancel(Operation operation) { return "AUCTION_CANCEL".equals(operation.request().operationKind()); }

    /** A per-account row lock serializes listing-cap checks across PNC instances. */
    private static boolean lockSellerListingCapacity(Connection connection, UUID seller, int limit) throws SQLException {
        String uuid = seller.toString();
        try (PreparedStatement ensure = connection.prepareStatement("INSERT IGNORE INTO balances (uuid,money) VALUES (?,0.00)")) {
            ensure.setString(1, uuid);
            ensure.executeUpdate();
        }
        try (PreparedStatement lock = connection.prepareStatement("SELECT uuid FROM balances WHERE uuid=? FOR UPDATE")) {
            lock.setString(1, uuid);
            try (ResultSet row = lock.executeQuery()) { if (!row.next()) throw new SQLException("seller capacity lock row is missing"); }
        }
        try (PreparedStatement count = connection.prepareStatement(
                "SELECT COUNT(*) FROM auction_listings WHERE seller_uuid=? AND ((status='ACTIVE' "
                    + "AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)) OR status IN ('BUYING','PREPARING','CANCELLING'))")) {
            count.setString(1, uuid);
            try (ResultSet row = count.executeQuery()) {
                if (!row.next()) throw new SQLException("seller listing count is missing");
                return row.getLong(1) < limit;
            }
        }
    }

    private record AuctionListing(long listingId, String sellerUuid) { }

    private static AuctionListing selectAuctionListing(Connection connection, Operation operation) throws SQLException {
        long listingId = auctionListingId(operation.request().itemKey());
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT seller_uuid,item_blob,price FROM auction_listings WHERE id=? AND status='ACTIVE' "
                    + "AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) FOR UPDATE")) {
            query.setLong(1, listingId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return null;
                String seller = rows.getString(1);
                byte[] item = rows.getBytes(2);
                BigDecimal price = BigDecimal.valueOf(rows.getDouble(3)).setScale(2, RoundingMode.HALF_UP);
                if (seller == null || item == null || price.compareTo(operation.request().totalPrice()) != 0
                        || !java.util.Arrays.equals(item, operation.request().itemPayload())) return null;
                return new AuctionListing(listingId, seller);
            }
        }
    }

    private static AuctionListing selectAuctionCancelListing(Connection connection, Operation operation) throws SQLException {
        long listingId = auctionListingId(operation.request().itemKey());
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT seller_uuid,item_blob FROM auction_listings WHERE id=? AND seller_uuid=? AND status='ACTIVE' "
                    + "AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) FOR UPDATE")) {
            query.setLong(1, listingId);
            query.setString(2, operation.request().playerId().toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return null;
                String seller = rows.getString(1);
                byte[] item = rows.getBytes(2);
                if (seller == null || item == null || !java.util.Arrays.equals(item, operation.request().itemPayload())) return null;
                return new AuctionListing(listingId, seller);
            }
        }
    }

    private static AuctionListing selectLockedAuctionListing(Connection connection, Operation operation) throws SQLException {
        long listingId = auctionListingId(operation.request().itemKey());
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT seller_uuid,item_blob,price FROM auction_listings WHERE id=? AND status='BUYING' "
                    + "AND buyer_uuid=? AND lock_operation_id=? FOR UPDATE")) {
            query.setLong(1, listingId); query.setString(2, operation.request().playerId().toString());
            query.setString(3, operation.request().operationId().toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return null;
                String seller = rows.getString(1); byte[] item = rows.getBytes(2);
                BigDecimal price = BigDecimal.valueOf(rows.getDouble(3)).setScale(2, RoundingMode.HALF_UP);
                if (seller == null || item == null || price.compareTo(operation.request().totalPrice()) != 0
                        || !java.util.Arrays.equals(item, operation.request().itemPayload())) return null;
                return new AuctionListing(listingId, seller);
            }
        }
    }

    private static AuctionListing selectLockedAuctionCancel(Connection connection, Operation operation) throws SQLException {
        long listingId = auctionListingId(operation.request().itemKey());
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT seller_uuid,item_blob FROM auction_listings WHERE id=? AND seller_uuid=? AND status='CANCELLING' "
                    + "AND lock_operation_id=? FOR UPDATE")) {
            query.setLong(1, listingId);
            query.setString(2, operation.request().playerId().toString());
            query.setString(3, operation.request().operationId().toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return null;
                String seller = rows.getString(1);
                byte[] item = rows.getBytes(2);
                if (seller == null || item == null || !java.util.Arrays.equals(item, operation.request().itemPayload())) return null;
                return new AuctionListing(listingId, seller);
            }
        }
    }

    private static AuctionListing selectPreparedAuctionListing(Connection connection, Operation operation) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id,seller_uuid,item_blob,price FROM auction_listings WHERE seller_uuid=? AND status='PREPARING' "
                    + "AND lock_operation_id=? FOR UPDATE")) {
            query.setString(1, operation.request().playerId().toString());
            query.setString(2, operation.request().operationId().toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return null;
                long id = rows.getLong(1);
                String seller = rows.getString(2);
                byte[] item = rows.getBytes(3);
                BigDecimal price = BigDecimal.valueOf(rows.getDouble(4)).setScale(2, RoundingMode.HALF_UP);
                if (id <= 0 || seller == null || item == null || price.compareTo(operation.request().totalPrice()) != 0
                        || !java.util.Arrays.equals(item, operation.request().itemPayload())) return null;
                if (rows.next()) throw new SQLException("duplicate auction listing reservations for operation");
                return new AuctionListing(id, seller);
            }
        }
    }

    private static long auctionListingId(String itemKey) throws SQLException {
        if (itemKey == null || !itemKey.startsWith("AUCTION_LISTING:")) throw new SQLException("auction listing reference is invalid");
        try {
            String suffix = itemKey.substring("AUCTION_LISTING:".length());
            int detail = suffix.indexOf(':');
            long id = Long.parseLong(detail < 0 ? suffix : suffix.substring(0, detail));
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException invalid) { throw new SQLException("auction listing reference is invalid", invalid); }
    }
    private static long orderFillId(String itemKey) throws SQLException {
        return prefixedId(itemKey, "ORDER_FILL:");
    }
    private static long prefixedId(String itemKey, String prefix) throws SQLException {
        if (itemKey == null || !itemKey.startsWith(prefix)) throw new SQLException("operation row reference is invalid");
        try {
            String suffix = itemKey.substring(prefix.length());
            int separator = suffix.indexOf(':');
            long id = Long.parseLong(separator < 0 ? suffix : suffix.substring(0, separator));
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException invalid) { throw new SQLException("operation row reference is invalid", invalid); }
    }
    private static String prefixedItemKey(String itemKey, String prefix) throws SQLException {
        prefixedId(itemKey, prefix);
        String suffix = itemKey.substring(prefix.length());
        int separator = suffix.indexOf(':');
        if (separator < 0 || separator == suffix.length() - 1) throw new SQLException("operation item reference is invalid");
        String item = suffix.substring(separator + 1);
        if (!item.matches("[A-Z][A-Z0-9_]{0,95}")) throw new SQLException("operation item reference is invalid");
        return item;
    }
    private static boolean safeReason(String value) { return value != null && value.matches("[a-z0-9_]{1,64}"); }

    private static void rollback(Connection connection) {
        if (connection == null) return;
        try { connection.rollback(); } catch (SQLException ignored) { }
    }

    private static void close(Connection connection) {
        if (connection == null) return;
        try { connection.close(); } catch (SQLException ignored) { }
    }
}
