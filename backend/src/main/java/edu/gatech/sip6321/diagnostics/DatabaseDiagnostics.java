package edu.gatech.sip6321.diagnostics;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionSystemException;

/** Allowlisted diagnostics only: never pass business values, messages, or Throwables to logging. */
public final class DatabaseDiagnostics {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseDiagnostics.class);
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    public enum Operation {
        ACCOUNT_CREATE("account.create"),
        ACCOUNT_LOOKUP("account.lookup");

        private final String label;

        Operation(String label) {
            this.label = label;
        }
    }

    @FunctionalInterface
    public interface ConnectionSupplier {
        Connection get() throws SQLException;
    }

    private DatabaseDiagnostics() {}

    /** Context is synchronous, never inherited by pool threads, and always restored/removed. */
    public static <T> T signup(Supplier<T> action) {
        Context previous = CURRENT.get();
        CURRENT.set(new Context(UUID.randomUUID().toString(), "none", 0));
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    /** Wrap the proxy call, not the transactional method body, to include begin/commit failures. */
    public static <T> T operation(Operation operation, int attempt, Supplier<T> action) {
        Context previous = CURRENT.get();
        Context current = new Context(correlation(previous), operation.label, attempt);
        CURRENT.set(current);
        long start = System.nanoTime();
        log(current, operation.label, "none", "start", "unknown", start, null);
        try {
            T result = action.get();
            log(current, operation.label, "none", "success", current.acquired(), start, null);
            return result;
        } catch (RuntimeException exception) {
            log(current, operation.label, "none", "failure", current.acquired(), start, exception);
            throw exception; // Preserve the original object, classification and retry behavior.
        } finally {
            restore(previous);
        }
    }

    /** Reports a pooled connection handed to the caller, not merely a physical pool connection. */
    public static Connection acquire(ConnectionSupplier action) throws SQLException {
        Context current = CURRENT.get();
        Context context = current == null ? new Context(correlation(null), "none", 0) : current;
        long start = System.nanoTime();
        context.acquisitionObserved = true;
        log(context, "connection.acquire", context.operation, "start", "false", start, null);
        try {
            Connection connection = action.get();
            if (connection != null) {
                context.connectionAcquired = true;
            }
            log(
                    context,
                    "connection.acquire",
                    context.operation,
                    "success",
                    Boolean.toString(connection != null),
                    start,
                    null);
            return connection;
        } catch (SQLException | RuntimeException exception) {
            log(
                    context,
                    "connection.acquire",
                    context.operation,
                    "failure",
                    "false",
                    start,
                    exception);
            throw exception;
        }
    }

    private static String correlation(Context context) {
        return context == null ? UUID.randomUUID().toString() : context.correlationId;
    }

    private static void restore(Context previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    private static void log(
            Context context,
            String operation,
            String parentOperation,
            String event,
            String acquired,
            long start,
            Throwable failure) {
        SqlFailure sql = sqlFailure(failure);
        var entry = failure == null ? LOGGER.atInfo() : LOGGER.atWarn();
        entry.log(
                "db.diagnostic correlationId={} operation={} parentOperation={} attempt={} event={}"
                        + " connectionAcquired={} elapsedMs={} exception={} sqlException={} sqlState={}",
                context.correlationId,
                operation,
                parentOperation,
                context.attempt,
                event,
                acquired,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                failure == null ? "none" : type(failure),
                sql.type(),
                sql.state());
    }

    private record SqlFailure(String type, String state) {}

    private static SqlFailure sqlFailure(Throwable failure) {
        if (failure == null) {
            return new SqlFailure("none", "none");
        }
        var pending = new ArrayDeque<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        pending.add(failure);
        String sqlType = "unknown";
        // Bound traversal, including malformed/cyclic driver chains. No messages or toString().
        while (!pending.isEmpty() && seen.size() < 64) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) {
                continue;
            }
            if (current instanceof SQLException sql) {
                sqlType = type(sql);
                String state = sql.getSQLState();
                if (state != null && state.matches("[A-Z0-9]{5}")) {
                    return new SqlFailure(sqlType, state);
                }
                add(pending, sql.getNextException());
            }
            if (current instanceof TransactionSystemException transaction) {
                add(pending, transaction.getOriginalException());
            }
            add(pending, current.getCause());
        }
        return new SqlFailure(sqlType, "unknown");
    }

    private static void add(ArrayDeque<Throwable> pending, Throwable failure) {
        if (failure != null) {
            pending.addLast(failure);
        }
    }

    private static String type(Throwable failure) {
        String name = failure.getClass().getSimpleName();
        return name.matches("[A-Za-z0-9_$]{1,120}") ? name : "unknown";
    }

    private static final class Context {
        private final String correlationId;
        private final String operation;
        private final int attempt;
        private boolean acquisitionObserved;
        private boolean connectionAcquired;

        private Context(String correlationId, String operation, int attempt) {
            this.correlationId = correlationId;
            this.operation = operation;
            this.attempt = attempt;
        }

        private String acquired() {
            return acquisitionObserved ? Boolean.toString(connectionAcquired) : "unknown";
        }
    }
}
