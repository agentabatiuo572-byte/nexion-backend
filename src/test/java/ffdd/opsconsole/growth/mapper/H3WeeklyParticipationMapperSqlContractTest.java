package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class H3WeeklyParticipationMapperSqlContractTest {

    @Test
    void thresholdRowIncludesNonNullIdentityWhenTheUnemittedTimestampIsNull() throws Exception {
        String threshold = normalized(H3WeeklyParticipationMapper.class
                .getMethod("lockThreshold", Long.class, String.class, String.class)
                .getAnnotation(Select.class).value());

        assertThat(threshold).contains("SELECT ID AS ROWID", "EMITTED_AT AS EMITTEDAT", "FOR UPDATE");
        assertThat(H3WeeklyParticipationMapper.ThresholdRow.class.getRecordComponents()).hasSize(2);
    }
    @Test
    void thresholdMutexUsesAWriteLockThenACurrentLockingRead() throws Exception {
        String ensure = normalized(H3WeeklyParticipationMapper.class
                .getMethod("ensureThreshold", Long.class, String.class, String.class)
                .getAnnotation(Insert.class).value());
        String observations = normalized(H3WeeklyParticipationMapper.class
                .getMethod("lockObservationIds", Long.class, String.class, String.class)
                .getAnnotation(Select.class).value());
        String migration = Files.readString(Path.of("scripts/migrations/20260907_h3_weekly_participation.sql"));

        assertThat(ensure).contains("INSERT INTO NX_GROWTH_WEEKLY_PARTICIPATION_THRESHOLD",
                        "ON DUPLICATE KEY UPDATE", "UPDATED_AT=NOW()")
                .doesNotContain("INSERT IGNORE");
        assertThat(observations).contains("SELECT ID", "FROM NX_GROWTH_WEEKLY_PARTICIPATION_OBSERVATION", "FOR UPDATE")
                .doesNotContain("COUNT(");
        assertThat(migration).contains("uk_h3_weekly_participation_subject",
                        "uk_h3_weekly_participation_threshold",
                        "nx_growth_weekly_participation_rollout",
                        "INSERT IGNORE INTO nx_growth_weekly_participation_rollout",
                        "UTC_TIMESTAMP() + INTERVAL 8 HOUR")
                .doesNotContain("VALUES (1,NOW(),NOW(),NOW())");
    }

    @Test
    void computeSourceRequiresAProductionCompletedTaskAndCreditedReceipt() throws Exception {
        String completion = normalized(H3WeeklyParticipationMapper.class
                .getMethod("verifiedProductionCompletedTask", Long.class, String.class)
                .getAnnotation(Select.class).value());

        assertThat(completion).contains("T.SOURCE_ENVIRONMENT='PRODUCTION'", "UPPER(T.STATUS)='COMPLETED'",
                        "T.PROOF_CONSUMED_AT IS NOT NULL", "T.COMPLETED_AT IS NOT NULL",
                        "R.SOURCE_ENVIRONMENT='PRODUCTION'", "UPPER(R.EARNING_STATUS)='CREDITED'");
    }

    @Test
    void storefrontObservationAcceptsAVisibleProductWithoutAStockOrPurchaseGate() throws Exception {
        String product = normalized(CanonicalStateMapper.class
                .getMethod("findVisibleStorefrontProduct", String.class)
                .getAnnotation(Select.class).value());

        assertThat(product).contains("P.IS_DELETED=0", "UPPER(P.STATUS) IN ('ACTIVE','ON_SALE')",
                        "COALESCE(P.STORE_VISIBLE,1)=1")
                .doesNotContain("STOCK", "INVENTORY", "PRICE_USDT", "PURCHASE_GATE");
    }

    private static String normalized(String[] sql) {
        return String.join(" ", sql).replaceAll("\\s+", " ").trim().toUpperCase(java.util.Locale.ROOT);
    }
}
