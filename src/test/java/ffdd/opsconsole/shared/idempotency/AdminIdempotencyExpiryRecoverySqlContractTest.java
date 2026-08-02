package ffdd.opsconsole.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AdminIdempotencyExpiryRecoverySqlContractTest {
    private static final Path MAPPER = Path.of(
            "src/main/java/ffdd/opsconsole/shared/idempotency/mapper/AdminIdempotencyRecordMapper.java");
    private static final Path MIGRATION = Path.of(
            "scripts/migrations/20260801_admin_idempotency_expiry_recovery.sql");

    @Test
    void batchRecoveryClaimsBoundedRowsWithoutBlockingRequestFinalization() throws Exception {
        String mapper = normalized(MAPPER);
        String executor = normalized(Path.of(
                "src/main/java/ffdd/opsconsole/shared/idempotency/AdminIdempotencyTransactionExecutor.java"));

        assertThat(mapper)
                .contains("FORCE INDEX (idx_admin_idem_expiry_claim)")
                .contains("ORDER BY expires_at ASC, id ASC")
                .contains("LIMIT #{limit}")
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("AND status = 'PROCESSING'")
                .contains("AND expires_at <= NOW()")
                .contains("markLockedExpiredProcessingUnknown")
                .doesNotContain("UPDATE nx_admin_idempotency_record SET status = 'UNKNOWN' WHERE status = 'PROCESSING'");
        assertThat(executor)
                .contains("markCurrentExpiredProcessingUnknown")
                .contains("Isolation.READ_COMMITTED")
                .doesNotContain("LocalDateTime.now()");
    }

    @Test
    void startupMigrationAndFreshSchemaProvideTheRecoveryIndexExactlyOnce() throws Exception {
        String migration = normalized(MIGRATION);
        String startup = Files.readString(
                Path.of("scripts/apply_startup_schema_migrations.ps1"), StandardCharsets.UTF_8);
        String schema = normalized(Path.of("scripts/schema.sql"));

        assertThat(migration)
                .contains("idx_admin_idem_status_expires_deleted")
                .contains("INFORMATION_SCHEMA.STATISTICS")
                .contains("GET_LOCK")
                .contains("RELEASE_LOCK")
                .contains("IDEMPOTENCY_EXPIRY_RECOVERY_LOCK_NOT_ACQUIRED")
                .contains("CHAR_LENGTH(@idempotency_expiry_recovery_lock_name)")
                .contains("LEFT(SHA2(COALESCE(DATABASE(), ''), 256), 32)")
                .contains("ALTER TABLE nx_admin_idempotency_record ADD INDEX");
        Path claimMigration = Path.of("scripts/migrations/20260801_admin_idempotency_expiry_claim_index.sql");
        assertThat(Files.readString(claimMigration, StandardCharsets.UTF_8))
                .contains("idx_admin_idem_expiry_claim")
                .contains("GET_LOCK")
                .contains("IDEMPOTENCY_EXPIRY_CLAIM_LOCK_NOT_ACQUIRED")
                .contains("ALTER TABLE nx_admin_idempotency_record ADD INDEX")
                .contains("(status, is_deleted, expires_at, id)");
        assertThat(schema).contains("KEY idx_admin_idem_expiry_claim (status, is_deleted, expires_at, id)");
        assertThat(occurrences(startup, "20260801_admin_idempotency_expiry_recovery.sql")).isEqualTo(1);
        assertThat(occurrences(startup, "20260801_admin_idempotency_expiry_claim_index.sql")).isEqualTo(1);
        assertThat(startup)
                .contains("idx_admin_idem_status_expires_deleted")
                .contains("idx_admin_idem_expiry_claim")
                .contains("$requiredIndexCount.Trim() -ne \"2\"")
                .contains("GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)");
        assertThat("nx:idemexp:".length() + 32).isLessThanOrEqualTo(64);
    }

    private String normalized(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }

    private int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
