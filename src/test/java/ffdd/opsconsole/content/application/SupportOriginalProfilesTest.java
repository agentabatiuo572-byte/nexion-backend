package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** SQL contract double; the existing Core/Bulk/Avatar suites exercise the same guard on real MySQL. */
class SupportOriginalProfilesTest {
    @Test void preservesEveryOriginalFieldWhenSuspendingAndRestoring() {
        var db = new Database();
        db.add(11, 101, true, false);
        db.add(12, 102, false, false);
        db.add(13, 103, true, true);
        var before = db.copy();

        var guard = SupportOriginalProfiles.suspend(db.jdbc, db.transactions);
        assertThat(db.rows.get(11L).get("enabled")).isEqualTo("0");
        assertThat(db.rows.get(11L).get("updated_at")).isEqualTo(before.get(11L).get("updated_at"));
        assertThat(db.rows.get(12L)).isEqualTo(before.get(12L));
        assertThat(db.rows.get(13L)).isEqualTo(before.get(13L));

        guard.restoreAndVerify();
        assertThat(db.rows).isEqualTo(before);
        assertThat(db.updatedIds).containsExactly(11L, 11L);
    }

    @Test void neverAdoptsNewRowsAndRepeatedRestorationDoesNotWriteAgain() {
        var db = new Database();
        db.add(11, 101, true, false);
        var guard = SupportOriginalProfiles.suspend(db.jdbc, db.transactions);
        db.add(12, 102, true, false);
        var newcomer = new LinkedHashMap<>(db.rows.get(12L));

        guard.restoreAndVerify();
        guard.restoreAndVerify();

        assertThat(db.updatedIds).containsExactly(11L, 11L);
        assertThat(db.rows.get(12L)).isEqualTo(newcomer);
    }

    @Test void refusesToRewriteAnUnexpectedOriginalTimestamp() {
        var db = new Database();
        db.add(11, 101, true, false);
        var guard = SupportOriginalProfiles.suspend(db.jdbc, db.transactions);
        db.rows.get(11L).put("updated_at", "2030-01-02 03:04:05");

        assertThatThrownBy(guard::restoreAndVerify).hasMessageContaining("11/updated_at");
        assertThat(db.rows.get(11L).get("updated_at")).isEqualTo("2030-01-02 03:04:05");
        assertThat(db.updatedIds).containsExactly(11L);
        verify(db.transactions).rollback(any());
    }

    @Test void detectsNullVersusEmptyAndChangesOnOriginallyDisabledRows() {
        var db = new Database();
        db.add(11, 101, true, false);
        db.add(12, 102, false, false);
        db.rows.get(12L).put("tags", null);
        var guard = SupportOriginalProfiles.suspend(db.jdbc, db.transactions);
        db.rows.get(12L).put("tags", "");

        assertThatThrownBy(guard::restoreAndVerify).hasMessageContaining("12/tags");
        assertThat(db.updatedIds).containsExactly(11L);
    }

    @Test void refusesMissingOriginalRowsBeforeRestorationWrites() {
        var db = new Database();
        db.add(11, 101, true, false);
        var guard = SupportOriginalProfiles.suspend(db.jdbc, db.transactions);
        db.rows.remove(11L);

        assertThatThrownBy(guard::restoreAndVerify).hasMessageContaining("missing or field inventory");
        assertThat(db.updatedIds).containsExactly(11L);
    }

    @Test void refusesReplacedAdminIdentityEvenWhenProfileIdIsUnchanged() {
        var db = new Database();
        db.add(11, 101, true, false);
        var guard = SupportOriginalProfiles.suspend(db.jdbc, db.transactions);
        db.rows.get(11L).put("admin_id", "999");

        assertThatThrownBy(guard::restoreAndVerify).hasMessageContaining("11/admin_id");
        assertThat(db.updatedIds).containsExactly(11L);
    }

    @Test void refusesDuplicateSnapshotIdentitiesBeforeAnyWrite() {
        var db = new Database();
        db.add(11, 101, true, false);
        db.add(12, 101, true, false);

        assertThatThrownBy(() -> SupportOriginalProfiles.suspend(db.jdbc, db.transactions))
                .hasMessageContaining("Duplicate original profile identity");
        assertThat(db.updatedIds).isEmpty();
        verify(db.transactions).rollback(any());
    }

    @Test void refusesEmptyBaselineBeforeAnyWrite() {
        var db = new Database();

        assertThatThrownBy(() -> SupportOriginalProfiles.suspend(db.jdbc, db.transactions))
                .hasMessageContaining("must not be empty");
        assertThat(db.updatedIds).isEmpty();
    }

    @Test void failedLaterBatchRollsBackEarlierTemporaryChanges() {
        var db = new Database();
        for (long id = 1; id <= 501; id++) db.add(id, id + 1000, true, false);
        var before = db.copy();
        db.failOnUpdate = 2;

        assertThatThrownBy(() -> SupportOriginalProfiles.suspend(db.jdbc, db.transactions))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(db.rows).isEqualTo(before);
        verify(db.transactions).rollback(any());
        verify(db.transactions, never()).commit(any());
    }

    @Test void anUnexpectedAffectedRowCountRollsBackSuspension() {
        var db = new Database();
        db.add(11, 101, true, false);
        var before = db.copy();
        db.wrongUpdateCount = true;

        assertThatThrownBy(() -> SupportOriginalProfiles.suspend(db.jdbc, db.transactions))
                .hasMessageContaining("update count mismatch");
        assertThat(db.rows).isEqualTo(before);
        verify(db.transactions).rollback(any());
    }

    @Test void aFailingTestBodyStillRestoresOriginalFieldsInFinally() {
        var db = new Database();
        db.add(11, 101, true, false);
        var before = db.copy();
        var guard = SupportOriginalProfiles.suspend(db.jdbc, db.transactions);
        var failure = new IllegalStateException("simulated business test failure");

        assertThatThrownBy(() -> {
            try { throw failure; }
            finally { guard.restoreAndVerify(); }
        }).isSameAs(failure);
        assertThat(db.rows).isEqualTo(before);
    }

    @Test void cleanupContinuesAfterFailuresAndKeepsTheOriginalFailure() {
        var first = new AssertionError("actor cleanup failure");
        var second = new IllegalStateException("profile cleanup failure");
        var order = new ArrayList<String>();
        var contextCleared = new AtomicBoolean();

        assertThatThrownBy(() -> SupportOriginalProfiles.cleanup(
                () -> { order.add("actor"); throw first; },
                () -> order.add("remaining-actor"),
                () -> { order.add("profile"); throw second; },
                () -> contextCleared.set(true)))
                .isSameAs(first).hasSuppressedException(second);
        assertThat(order).containsExactly("actor", "remaining-actor", "profile");
        assertThat(contextCleared).isTrue();
    }

    private static final class Database {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        final Map<Long, Map<String, String>> rows = new LinkedHashMap<>();
        final List<Long> updatedIds = new ArrayList<>();
        Map<Long, Map<String, String>> transactionBefore;
        int updates;
        int failOnUpdate;
        boolean wrongUpdateCount;

        Database() {
            when(transactions.getTransaction(any())).thenAnswer(call -> {
                transactionBefore = copy();
                return new SimpleTransactionStatus();
            });
            doAnswer(call -> { rows.clear(); rows.putAll(transactionBefore); return null; })
                    .when(transactions).rollback(any());
            when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Map<String, String>>>any()))
                    .thenAnswer(call -> new ArrayList<>(copy().values()));
            when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(call -> {
                if (++updates == failOnUpdate) throw new DataAccessResourceFailureException("injected batch failure");
                String sql = call.getArgument(0);
                assertThat(sql).contains("WHERE id IN (").contains("AND is_deleted=0");
                boolean enable = sql.contains("SET enabled=1");
                int affected = 0;
                for (Object argument : (Object[]) call.getRawArguments()[1]) {
                    long id = ((Number) argument).longValue();
                    var row = rows.get(id);
                    if (row != null && "0".equals(row.get("is_deleted"))
                            && (enable ? "0" : "1").equals(row.get("enabled"))) {
                        row.put("enabled", enable ? "1" : "0");
                        // Model the actual DDL's ON UPDATE effect, unless the SQL explicitly preserves time.
                        if (!sql.contains("updated_at=updated_at")) row.put("updated_at", "2035-06-07 08:09:10");
                        updatedIds.add(id);
                        affected++;
                    }
                }
                return wrongUpdateCount ? 0 : affected;
            });
        }

        void add(long id, long admin, boolean enabled, boolean deleted) {
            var row = new LinkedHashMap<String, String>();
            row.put("id", String.valueOf(id));row.put("admin_id", String.valueOf(admin));
            row.put("seat_type", "DEDICATED");row.put("position", "DEDICATED");
            row.put("service_types", "support,advisor");row.put("tags", "");
            row.put("max_concurrent", "0");row.put("enabled", enabled ? "1" : "0");
            row.put("transferable", "1");row.put("busy", "0");row.put("version", "1");
            row.put("created_at", "2001-02-03 04:05:06");row.put("updated_at", "2002-03-04 05:06:07");
            row.put("is_deleted", deleted ? "1" : "0");
            rows.put(id, row);
        }

        Map<Long, Map<String, String>> copy() {
            Map<Long, Map<String, String>> result = new LinkedHashMap<>();
            rows.forEach((id, values) -> result.put(id, new LinkedHashMap<>(values)));
            return result;
        }
    }
}
