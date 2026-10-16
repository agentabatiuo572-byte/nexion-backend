package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.growth.mapper.WeeklyQuestSnapshotMapper.Definition;
import ffdd.opsconsole.growth.mapper.WeeklyQuestSnapshotMapper.Header;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Real MySQL RR/current-read boundaries, not a mock of database visibility. */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WeeklyQuestSnapshotMySqlVisibilityTest extends WeeklyQuestSnapshotMySqlFixture {
    private static final String KEY = "WEEK:2026-W42";
    private static final String CODE = "weekly_t2_browse_store";
    private static final String EVENT = "H3_STOREFRONT_THREE_PRODUCTS_VIEWED";
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 12, 0, 0);

    @Test
    void lockWaiterWithAnEmptyOldReadViewSeesCommittedHeaderBindingsAndItems() throws Exception {
        mapper.ensureMutex();
        var pool = Executors.newFixedThreadPool(2);
        var holdingMutex = new CountDownLatch(1);
        var emptyRead = new CountDownLatch(1);
        var releasePublisher = new CountDownLatch(1);
        try {
            var publisher = pool.submit(() -> transaction.execute(status -> {
                assertThat(mapper.lockMutex()).isEqualTo("H3_CONFIG");
                holdingMutex.countDown();
                await(releasePublisher);
                insertPublication();
                return null;
            }));
            await(holdingMutex);
            var waiter = pool.submit(() -> transaction.execute(status -> {
                assertThat(mapper.find(KEY)).isNull(); // Establish the losing transaction's RR read view.
                emptyRead.countDown();
                assertThat(mapper.lockMutex()).isEqualTo("H3_CONFIG");
                assertThat(mapper.findAfterLock(KEY)).isNotNull();
                assertOldViewStillEmpty(); // A FOR UPDATE header does not refresh ordinary consistent reads.
                assertThat(mapper.bindings(KEY, EVENT)).singleElement()
                        .satisfies(binding -> assertThat(binding.questCode()).isEqualTo(CODE));
                assertThat(mapper.state(KEY, 3778L, "en")).singleElement()
                        .satisfies(row -> assertThat(row).containsEntry("questCode", CODE));
                return null;
            }));
            await(emptyRead);
            // Observe an actual InnoDB lock wait, rather than assuming a sleep caused contention.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            int waits;
            do {
                waits = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                      JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                     WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='nx_admin_operation_mutex'
                    """, Integer.class);
                if (waits == 0) Thread.sleep(10);
            } while (waits == 0 && System.nanoTime() < deadline);
            assertThat(waits).as("real waiter on the private fixture H3 mutex").isPositive();
            releasePublisher.countDown();
            publisher.get(15, TimeUnit.SECONDS);
            waiter.get(15, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_weekly_definition", Integer.class)).isEqualTo(1);
        } finally {
            releasePublisher.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void projectorStyleOuterReadViewCanResolveMissionAfterSeparatePublicationCommits() {
        var pool = Executors.newSingleThreadExecutor();
        try {
            transaction.execute(status -> {
                // Projector reads live bindings before its REQUIRES_NEW snapshot lookup.
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_quest_event_binding", Integer.class)).isZero();
                try {
                    pool.submit(() -> transaction.execute(inner -> { insertPublication(); return null; }))
                            .get(15, TimeUnit.SECONDS);
                } catch (Exception failure) { throw new AssertionError(failure); }
                assertOldViewStillEmpty();
                var mission = mapper.mission(KEY, CODE, START.plusDays(4));
                assertThat(mission).isNotNull();
                assertThat(mission.missionId()).isEqualTo(1L);
                assertThat(mission.instanceKey()).isEqualTo(KEY);
                assertThat(mapper.mission(KEY, CODE, START.plusWeeks(1))).isNull();
                return null;
            });
        } finally {
            pool.shutdownNow();
            try { assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
    }

    private void assertOldViewStillEmpty() {
        assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_weekly_definition", Integer.class)).isZero();
    }

    @Test
    void legacyCompatibilitySelectsOnlyRealPrePublicationEarnedProgressOutsideFrozenItems() {
        transaction.execute(status -> { insertPublication(); return null; });
        jdbc.update("UPDATE nx_growth_weekly_definition SET created_at='2026-10-16 08:00:00'");
        for (int id = 1; id <= 6; id++) {
            jdbc.update("INSERT INTO nx_mission(id,mission_code,mission_name,mission_type,status,is_deleted) VALUES(?,?,?,'WEEKLY_T2',0,0)",
                    id, id == 1 ? CODE : "legacy_" + id, "Legacy " + id);
        }
        // Existing frozen item is excluded, even when already earned.
        progress(3778L, 1, "CLAIMABLE", "2026-10-16 07:00:00");
        progress(3778L, 2, "CLAIMABLE", "2026-10-16 07:00:00");
        progress(3778L, 3, "CLAIMED", "2026-10-16 07:00:00");
        progress(3778L, 4, "PENDING", "2026-10-16 07:00:00");
        progress(3778L, 5, "CLAIMABLE", "2026-10-16 09:00:00");
        progress(9988L, 6, "CLAIMABLE", "2026-10-16 07:00:00");
        assertThat(mapper.legacyProgressCodes(KEY, 3778L)).containsExactlyInAnyOrder("legacy_2", "legacy_3");
        assertThat(mapper.legacyClaimMission(KEY, 3778L, "legacy_2")).isEqualTo(2L);
        assertThat(mapper.legacyClaimMission(KEY, 3778L, CODE)).isNull(); // Frozen item uses the normal reward path.
        assertThat(mapper.legacyClaimMission(KEY, 3778L, "legacy_3")).isNull(); // Already claimed.
        assertThat(mapper.legacyClaimMission(KEY, 3778L, "legacy_4")).isNull(); // Pending is not earned.
        assertThat(mapper.legacyClaimMission(KEY, 3778L, "legacy_5")).isNull(); // Created after publication.
        assertThat(mapper.legacyClaimMission(KEY, 3778L, "legacy_6")).isNull(); // Another user's progress.
        jdbc.update("UPDATE nx_user_mission SET instance_key='WEEK:2026-W41' WHERE user_id=3778 AND mission_id=3");
        progress(3778L, 3, "CLAIMABLE", "2026-10-16 09:00:00");
        assertThat(mapper.legacyProgressCodes(KEY, 3778L)).contains("legacy_3"); // Real older history remains visible.
        assertThat(mapper.legacyClaimMission(KEY, 3778L, "legacy_3")).isNull(); // Old history cannot authorize a later row.
        jdbc.update("UPDATE nx_user_mission SET mission_status='ANOMALY' WHERE user_id=3778 AND mission_id=1");
        assertThat(mapper.state(KEY, 3778L, "en")).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("status", "PENDING"));
        assertThat(jdbc.queryForObject("SELECT mission_status FROM nx_user_mission WHERE user_id=3778 AND mission_id=1", String.class))
                .isEqualTo("ANOMALY"); // Projection never rewrites the persisted progress.
        jdbc.update("UPDATE nx_user_mission SET is_deleted=1 WHERE user_id=3778 AND mission_id=2");
        assertThat(mapper.legacyProgressCodes(KEY, 3778L)).containsExactly("legacy_3");
        assertThat(mapper.legacyClaimMission(KEY, 3778L, "legacy_2")).isNull();
        // This is the compatibility selector, not proof of wallet claim/CAS execution.
    }

    private void progress(long user, int mission, String state, String created) {
        jdbc.update("INSERT INTO nx_user_mission(user_id,mission_id,instance_key,mission_status,created_at) VALUES(?,?,?,?,?)",
                user, mission, KEY, state, created);
    }

    private void insertPublication() {
        mapper.insertHeader(new Header(KEY, START, START.plusWeeks(1), new BigDecimal("2"), 3, 1));
        var definition = new Definition(1L, CODE, "Original browse task", "WEEKLY_T2", "STORE", "/pages/tabs/store",
                10, 11L, "browse-binding", "SYSTEM", EVENT, "user_id");
        mapper.insertItem(KEY, definition, 0);
        mapper.insertBinding(KEY, definition);
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
