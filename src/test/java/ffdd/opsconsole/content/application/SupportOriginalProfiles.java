package ffdd.opsconsole.content.application;

import java.sql.ResultSetMetaData;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Protects exact pre-existing rows while a runtime fixture temporarily suspends allocation. */
final class SupportOriginalProfiles {
    private static final String SELECT_ALL = "SELECT * FROM nx_support_agent_profile ORDER BY id";
    private static final int UPDATE_BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final Map<Long, Map<String, String>> original;

    private SupportOriginalProfiles(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                    Map<Long, Map<String, String>> original) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.original = original;
    }

    static SupportOriginalProfiles suspend(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        return new TransactionTemplate(transactions).execute(status -> {
            var before = read(jdbc, true);
            require(!before.isEmpty(), "Original profile baseline must not be empty");
            var guard = new SupportOriginalProfiles(jdbc, transactions, before);
            var ids = before.entrySet().stream().filter(entry -> originallyEnabled(entry.getValue()))
                    .map(Map.Entry::getKey).toList();
            guard.changeEnabled(ids, false);
            guard.verify(read(jdbc, false), true, false);
            return guard;
        });
    }

    void restoreAndVerify() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var current = read(jdbc, true);
            // A repeated cleanup may already be restored. Any other original-field change is refused.
            verify(current, false, true);
            var ids = original.entrySet().stream()
                    .filter(entry -> originallyEnabled(entry.getValue())
                            && "0".equals(current.get(entry.getKey()).get("enabled")))
                    .map(Map.Entry::getKey).toList();
            changeEnabled(ids, true);
            verify(read(jdbc, false), false, false);
        });
    }

    private void changeEnabled(List<Long> ids, boolean enable) {
        for (int offset = 0; offset < ids.size(); offset += UPDATE_BATCH_SIZE) {
            var batch = ids.subList(offset, Math.min(ids.size(), offset + UPDATE_BATCH_SIZE));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            String sql = "UPDATE nx_support_agent_profile SET enabled=" + (enable ? 1 : 0)
                    + ", updated_at=updated_at WHERE id IN (" + placeholders + ") AND enabled="
                    + (enable ? 0 : 1) + " AND is_deleted=0";
            require(jdbc.update(sql, batch.toArray()) == batch.size(),
                    "Exact original profile update count mismatch");
        }
    }

    private void verify(Map<Long, Map<String, String>> actual, boolean suspended, boolean allowSuspended) {
        for (var entry : original.entrySet()) {
            var before = entry.getValue();
            var after = actual.get(entry.getKey());
            require(after != null && before.keySet().equals(after.keySet()),
                    "Original profile missing or field inventory changed: " + entry.getKey());
            for (var field : before.entrySet()) {
                String expected = suspended && originallyEnabled(before) && field.getKey().equals("enabled")
                        ? "0" : field.getValue();
                String value = after.get(field.getKey());
                boolean expectedTemporaryState = allowSuspended && originallyEnabled(before)
                        && field.getKey().equals("enabled") && "0".equals(value);
                require(expectedTemporaryState || java.util.Objects.equals(expected, value),
                        "Original profile field changed: " + entry.getKey() + "/" + field.getKey());
            }
        }
    }

    private static Map<Long, Map<String, String>> read(JdbcTemplate jdbc, boolean lock) {
        List<Map<String, String>> rows = jdbc.query(SELECT_ALL + (lock ? " FOR UPDATE" : ""), (rs, index) -> {
            ResultSetMetaData metadata = rs.getMetaData();
            Map<String, String> values = new LinkedHashMap<>();
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                values.put(metadata.getColumnLabel(column), rs.getString(column));
            }
            return values;
        });
        Map<Long, Map<String, String>> result = new LinkedHashMap<>();
        Set<Long> adminIds = new HashSet<>();
        for (var row : rows) {
            require(row.keySet().containsAll(Set.of("id", "admin_id", "enabled", "is_deleted", "updated_at")),
                    "Original profile identity or protected fields missing");
            long id = positiveId(row.get("id"));
            long adminId = positiveId(row.get("admin_id"));
            require(adminIds.add(adminId) && !result.containsKey(id), "Duplicate original profile identity");
            result.put(id, Collections.unmodifiableMap(new LinkedHashMap<>(row)));
        }
        return Collections.unmodifiableMap(result);
    }

    private static long positiveId(String value) {
        require(value != null && value.matches("[1-9][0-9]*"), "Exact positive profile identity required");
        return Long.parseLong(value);
    }

    private static boolean originallyEnabled(Map<String, String> row) {
        return "1".equals(row.get("enabled")) && "0".equals(row.get("is_deleted"));
    }

    static void cleanup(Runnable... actions) {
        Throwable first = null;
        for (var action : actions) {
            try {
                action.run();
            } catch (RuntimeException | Error failure) {
                if (first == null) first = failure;
                else if (first != failure) first.addSuppressed(failure);
            }
        }
        if (first instanceof RuntimeException failure) throw failure;
        if (first instanceof Error failure) throw failure;
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
