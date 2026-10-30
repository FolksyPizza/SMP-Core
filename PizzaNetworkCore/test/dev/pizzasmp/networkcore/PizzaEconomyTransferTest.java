// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.networkcore;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Standalone fake-JDBC proof for the canonical two-account payment transaction. */
public final class PizzaEconomyTransferTest {
    private static final BigDecimal MAX = new BigDecimal("9999999999999999.99");
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-000000000002");

    public static void main(String[] args) throws Exception {
        successDebitsCreditsAndAuditsTogether();
        insufficientFundsRollBacksCreatedRows();
        creditAndAuditFailuresRollBackBothBalances();
        targetOverflowIsRejected();
        invalidRequestsDoNotOpenAConnection();
        locksAreTakenInStableUuidOrder();
        commitFailureIsUnknownWhetherTheServerCommittedOrNot();
        System.out.println("PizzaEconomyTransferTest: all assertions passed");
    }

    private static void successDebitsCreditsAndAuditsTogether() {
        FakeJdbc db = new FakeJdbc(Map.of(ALICE.toString(), money("20.00"), BOB.toString(), money("1.00")));
        PizzaEconomy economy = new PizzaEconomy(db::open);

        PizzaEconomy.TransferResult result = economy.transfer(ALICE, "Alice", BOB, "Bob", 3.50, "main");

        check(result.outcome() == PizzaEconomy.TransferOutcome.SUCCESS, "successful transfer outcome");
        checkMoney(db, ALICE, "16.50", "source debited");
        checkMoney(db, BOB, "4.50", "target credited");
        check(db.auditRows.size() == 1, "PAY audit committed with the balances");
        List<Object> audit = db.auditRows.get(0);
        check(audit.get(0).equals(ALICE.toString()), "audit source UUID");
        check(audit.get(1).equals("Alice"), "audit source name");
        check(audit.get(2).equals("PAY"), "audit event type");
        check(audit.get(3).equals("Bob"), "audit target name");
        check(audit.get(4).equals(1), "audit amount field");
        check(((Double) audit.get(5)) == 3.50 && ((Double) audit.get(6)) == 3.50, "audit money fields");
        check(audit.get(7).equals("to=" + BOB) && audit.get(8).equals("main"), "audit target and server");
        check(db.commits == 1 && db.rollbacks == 0, "one acknowledged commit");
    }

    private static void insufficientFundsRollBacksCreatedRows() {
        FakeJdbc db = new FakeJdbc(Map.of(ALICE.toString(), money("2.00")));
        PizzaEconomy economy = new PizzaEconomy(db::open);

        PizzaEconomy.TransferResult result = economy.transfer(ALICE, "Alice", BOB, "Bob", 2.01, "main");

        check(result.outcome() == PizzaEconomy.TransferOutcome.INSUFFICIENT, "insufficient outcome");
        checkMoney(db, ALICE, "2.00", "insufficient source unchanged");
        check(!db.balances.containsKey(BOB.toString()), "new zero target row rolled back");
        check(db.auditRows.isEmpty(), "insufficient transfer has no audit row");
        check(db.commits == 0 && db.rollbacks == 1, "insufficient transfer rolls back");
    }

    private static void creditAndAuditFailuresRollBackBothBalances() {
        FakeJdbc debitRejected = new FakeJdbc(Map.of(ALICE.toString(), money("20.00"), BOB.toString(), money("1.00")));
        debitRejected.rejectDebit = true;
        PizzaEconomy economy = new PizzaEconomy(debitRejected::open);
        PizzaEconomy.TransferResult rejectedResult = economy.transfer(ALICE, "Alice", BOB, "Bob", 3.00, "main");
        check(rejectedResult.outcome() == PizzaEconomy.TransferOutcome.FAILED, "unexpected debit rejection outcome");
        checkMoney(debitRejected, ALICE, "20.00", "debit rejection preserves source");
        checkMoney(debitRejected, BOB, "1.00", "debit rejection preserves target");
        check(debitRejected.auditRows.isEmpty() && debitRejected.rollbacks == 1, "debit rejection rolls back");

        FakeJdbc debitFailure = new FakeJdbc(Map.of(ALICE.toString(), money("20.00"), BOB.toString(), money("1.00")));
        debitFailure.failDebit = true;
        economy = new PizzaEconomy(debitFailure::open);
        PizzaEconomy.TransferResult debitResult = economy.transfer(ALICE, "Alice", BOB, "Bob", 3.00, "main");
        check(debitResult.outcome() == PizzaEconomy.TransferOutcome.FAILED, "debit SQL failure outcome");
        checkMoney(debitFailure, ALICE, "20.00", "debit SQL failure preserves source");
        checkMoney(debitFailure, BOB, "1.00", "debit SQL failure preserves target");
        check(debitFailure.auditRows.isEmpty() && debitFailure.rollbacks == 1, "debit SQL failure rolls back");

        FakeJdbc creditFailure = new FakeJdbc(Map.of(ALICE.toString(), money("20.00"), BOB.toString(), money("1.00")));
        creditFailure.failCredit = true;
        economy = new PizzaEconomy(creditFailure::open);
        PizzaEconomy.TransferResult creditResult = economy.transfer(ALICE, "Alice", BOB, "Bob", 3.00, "main");
        check(creditResult.outcome() == PizzaEconomy.TransferOutcome.FAILED, "credit failure outcome");
        checkMoney(creditFailure, ALICE, "20.00", "credit failure restores source");
        checkMoney(creditFailure, BOB, "1.00", "credit failure preserves target");
        check(creditFailure.auditRows.isEmpty() && creditFailure.rollbacks == 1, "credit failure rolls back before audit");

        FakeJdbc auditFailure = new FakeJdbc(Map.of(ALICE.toString(), money("20.00"), BOB.toString(), money("1.00")));
        auditFailure.failAudit = true;
        economy = new PizzaEconomy(auditFailure::open);
        PizzaEconomy.TransferResult auditResult = economy.transfer(ALICE, "Alice", BOB, "Bob", 3.00, "main");
        check(auditResult.outcome() == PizzaEconomy.TransferOutcome.FAILED, "audit failure outcome");
        checkMoney(auditFailure, ALICE, "20.00", "audit failure restores source");
        checkMoney(auditFailure, BOB, "1.00", "audit failure restores target");
        check(auditFailure.auditRows.isEmpty() && auditFailure.rollbacks == 1, "audit failure rolls back balances");
    }

    private static void targetOverflowIsRejected() {
        FakeJdbc db = new FakeJdbc(Map.of(ALICE.toString(), money("20.00"), BOB.toString(), MAX));
        PizzaEconomy economy = new PizzaEconomy(db::open);

        PizzaEconomy.TransferResult result = economy.transfer(ALICE, "Alice", BOB, "Bob", 0.01, "main");

        check(result.outcome() == PizzaEconomy.TransferOutcome.FAILED, "target overflow outcome");
        checkMoney(db, ALICE, "20.00", "overflow preserves source");
        checkMoney(db, BOB, MAX.toPlainString(), "overflow preserves target");
        check(db.auditRows.isEmpty() && db.rollbacks == 1, "overflow has no audit and rolls back");
    }

    private static void invalidRequestsDoNotOpenAConnection() {
        for (double amount : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 1.001, 1.0e20}) {
            FakeJdbc db = new FakeJdbc(Map.of());
            PizzaEconomy economy = new PizzaEconomy(db::open);
            PizzaEconomy.TransferResult result = economy.transfer(ALICE, "Alice", BOB, "Bob", amount, "main");
            check(result.outcome() == PizzaEconomy.TransferOutcome.FAILED, "invalid amount rejected: " + amount);
            check(db.opens == 0, "invalid amount does not open JDBC: " + amount);
        }

        FakeJdbc samePlayerDb = new FakeJdbc(Map.of());
        PizzaEconomy samePlayerEconomy = new PizzaEconomy(samePlayerDb::open);
        PizzaEconomy.TransferResult samePlayer = samePlayerEconomy.transfer(ALICE, "Alice", ALICE, "Alice", 1.00, "main");
        check(samePlayer.outcome() == PizzaEconomy.TransferOutcome.FAILED && samePlayerDb.opens == 0,
                "self-transfer rejected before JDBC");
    }

    private static void locksAreTakenInStableUuidOrder() {
        UUID high = UUID.fromString("f0000000-0000-4000-8000-000000000001");
        UUID low = UUID.fromString("00000000-0000-4000-8000-000000000002");
        FakeJdbc db = new FakeJdbc(Map.of(high.toString(), money("5.00"), low.toString(), money("5.00")));
        PizzaEconomy economy = new PizzaEconomy(db::open);

        PizzaEconomy.TransferResult result = economy.transfer(high, "High", low, "Low", 1.00, "main");

        check(result.outcome() == PizzaEconomy.TransferOutcome.SUCCESS, "reverse-order transfer succeeds");
        List<String> expected = List.of(low.toString(), high.toString());
        check(db.insertOrder.equals(expected), "balance row inserts follow UUID order");
        check(db.lockOrder.equals(expected), "FOR UPDATE locks follow UUID order");
    }

    private static void commitFailureIsUnknownWhetherTheServerCommittedOrNot() {
        for (boolean appliedBeforeAcknowledgmentFailure : new boolean[] {false, true}) {
            FakeJdbc db = new FakeJdbc(Map.of(ALICE.toString(), money("20.00"), BOB.toString(), money("1.00")));
            db.commitThrows = true;
            db.applyCommitBeforeThrow = appliedBeforeAcknowledgmentFailure;
            PizzaEconomy economy = new PizzaEconomy(db::open);

            PizzaEconomy.TransferResult result = economy.transfer(ALICE, "Alice", BOB, "Bob", 3.00, "main");

            check(result.outcome() == PizzaEconomy.TransferOutcome.UNKNOWN, "commit exception is ambiguous");
            check(db.commits == 1 && db.opens == 1, "ambiguous commit is never retried");
            check(db.rollbacks == 0, "no rollback is claimed after commit was attempted");
            if (appliedBeforeAcknowledgmentFailure) {
                checkMoney(db, ALICE, "17.00", "commit may have applied debit");
                checkMoney(db, BOB, "4.00", "commit may have applied credit");
                check(db.auditRows.size() == 1, "commit may have applied audit");
            } else {
                checkMoney(db, ALICE, "20.00", "commit may have left debit unapplied");
                checkMoney(db, BOB, "1.00", "commit may have left credit unapplied");
                check(db.auditRows.isEmpty(), "commit may have left audit unapplied");
            }
        }
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }

    private static void checkMoney(FakeJdbc db, UUID uuid, String expected, String message) {
        check(db.balances.get(uuid.toString()).compareTo(new BigDecimal(expected)) == 0, message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeJdbc {
        final Map<String, BigDecimal> balances = new HashMap<>();
        final List<List<Object>> auditRows = new ArrayList<>();
        final List<String> insertOrder = new ArrayList<>();
        final List<String> lockOrder = new ArrayList<>();
        int opens;
        int commits;
        int rollbacks;
        boolean rejectDebit;
        boolean failDebit;
        boolean failCredit;
        boolean failAudit;
        boolean commitThrows;
        boolean applyCommitBeforeThrow;
        private Map<String, BigDecimal> stagedBalances;
        private List<List<Object>> stagedAuditRows;

        FakeJdbc(Map<String, BigDecimal> startingBalances) {
            this.balances.putAll(startingBalances);
        }

        Connection open() {
            this.opens++;
            this.stagedBalances = new HashMap<>(this.balances);
            this.stagedAuditRows = new ArrayList<>();
            InvocationHandler handler = (proxy, method, args) -> connectionCall(method, args);
            return proxy(Connection.class, handler);
        }

        private Object connectionCall(Method method, Object[] args) throws Exception {
            return switch (method.getName()) {
                case "prepareStatement" -> statement((String) args[0]);
                case "setAutoCommit", "clearWarnings" -> null;
                case "commit" -> {
                    this.commits++;
                    if (this.commitThrows && !this.applyCommitBeforeThrow) throw new SQLException("acknowledgment lost");
                    applyStaged();
                    if (this.commitThrows) throw new SQLException("acknowledgment lost after commit");
                    yield null;
                }
                case "rollback" -> {
                    this.rollbacks++;
                    discardStaged();
                    yield null;
                }
                case "close" -> {
                    discardStaged();
                    yield null;
                }
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            };
        }

        private PreparedStatement statement(String sql) {
            Map<Integer, Object> parameters = new HashMap<>();
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "setString", "setInt", "setDouble", "setBigDecimal" -> {
                    parameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "executeUpdate" -> executeUpdate(sql, parameters);
                case "executeQuery" -> executeQuery(sql, parameters);
                case "close", "clearParameters" -> null;
                default -> defaultValue(method.getReturnType());
            };
            return proxy(PreparedStatement.class, handler);
        }

        private int executeUpdate(String sql, Map<Integer, Object> parameters) throws SQLException {
            if (sql.startsWith("INSERT IGNORE INTO balances")) {
                String uuid = (String) parameters.get(1);
                this.insertOrder.add(uuid);
                return this.stagedBalances.putIfAbsent(uuid, money("0.00")) == null ? 1 : 0;
            }
            if (sql.startsWith("UPDATE balances SET money = money -")) {
                if (this.rejectDebit) return 0;
                if (this.failDebit) throw new SQLException("debit update failed");
                BigDecimal amount = (BigDecimal) parameters.get(1);
                String uuid = (String) parameters.get(2);
                BigDecimal current = this.stagedBalances.get(uuid);
                if (current == null || current.compareTo(amount) < 0) return 0;
                this.stagedBalances.put(uuid, current.subtract(amount));
                return 1;
            }
            if (sql.startsWith("UPDATE balances SET money = money +")) {
                if (this.failCredit) return 0;
                BigDecimal amount = (BigDecimal) parameters.get(1);
                String uuid = (String) parameters.get(2);
                BigDecimal maximum = (BigDecimal) parameters.get(3);
                BigDecimal current = this.stagedBalances.get(uuid);
                if (current == null || current.compareTo(maximum) > 0) return 0;
                this.stagedBalances.put(uuid, current.add(amount));
                return 1;
            }
            if (sql.startsWith("INSERT INTO transaction_audit_log")) {
                if (this.failAudit) throw new SQLException("audit insert failed");
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= 9; i++) row.add(parameters.get(i));
                this.stagedAuditRows.add(row);
                return 1;
            }
            throw new SQLException("unexpected SQL: " + sql);
        }

        private ResultSet executeQuery(String sql, Map<Integer, Object> parameters) throws SQLException {
            if (!sql.startsWith("SELECT money FROM balances WHERE uuid = ? FOR UPDATE")) {
                throw new SQLException("unexpected SQL: " + sql);
            }
            String uuid = (String) parameters.get(1);
            this.lockOrder.add(uuid);
            BigDecimal value = this.stagedBalances.get(uuid);
            InvocationHandler handler = new InvocationHandler() {
                private boolean first = true;
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    return switch (method.getName()) {
                        case "next" -> {
                            boolean result = this.first && value != null;
                            this.first = false;
                            yield result;
                        }
                        case "getBigDecimal" -> value;
                        case "close" -> null;
                        default -> defaultValue(method.getReturnType());
                    };
                }
            };
            return proxy(ResultSet.class, handler);
        }

        private void applyStaged() {
            this.balances.clear();
            this.balances.putAll(this.stagedBalances);
            this.auditRows.addAll(this.stagedAuditRows);
            this.stagedBalances = null;
            this.stagedAuditRows = null;
        }

        private void discardStaged() {
            this.stagedBalances = null;
            this.stagedAuditRows = null;
        }

        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            Object instance = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
            return type.cast(instance);
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive() || type == void.class) return null;
            if (type == boolean.class) return false;
            if (type == char.class) return '\0';
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0.0f;
            if (type == double.class) return 0.0d;
            return null;
        }
    }
}
