package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.platform.mapper.AuditOperationTicketMapper;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.format.annotation.DateTimeFormat;
import org.apache.ibatis.annotations.Update;

class A2SecurityMigrationContractTest {
    @Test
    void migrationRepairsMissingDomainIndexAndDocumentsImmediatePermissionCacheEviction() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations/20260717_a2_security_closure.sql"));

        assertThat(sql).contains(
                "information_schema.STATISTICS",
                "INDEX_NAME = 'idx_audit_operation_ticket_domain'",
                "CREATE INDEX idx_audit_operation_ticket_domain",
                "rbac:v2:admin:perms:*");
    }

    @Test
    void publicDateTimeFiltersDeclareIsoBinding() throws Exception {
        for (String field : new String[]{"startTime", "endTime"}) {
            DateTimeFormat format = AuditLogQueryRequest.class.getDeclaredField(field)
                    .getAnnotation(DateTimeFormat.class);
            assertThat(format).isNotNull();
            assertThat(format.iso()).isEqualTo(DateTimeFormat.ISO.DATE_TIME);
        }
    }

    @Test
    void runtimeTicketTableDdlContainsTheServerScopeColumnAndIndex() throws Exception {
        Update ddl = AuditOperationTicketMapper.class.getMethod("createTicketTable").getAnnotation(Update.class);
        String sql = String.join("\n", ddl.value());
        assertThat(sql).contains(
                "source_domain VARCHAR(8) NOT NULL DEFAULT 'A'",
                "idx_audit_operation_ticket_domain (source_domain, status, created_at)");
    }

    @Test
    void permissionCacheNamespaceIsVersionedPastThePreMigrationGrantSet() throws Exception {
        java.lang.reflect.Field prefix = AdminPermissionCache.class.getDeclaredField("KEY_PREFIX");
        prefix.setAccessible(true);
        assertThat(prefix.get(null)).isEqualTo("rbac:v2:admin:perms:");
    }
}
