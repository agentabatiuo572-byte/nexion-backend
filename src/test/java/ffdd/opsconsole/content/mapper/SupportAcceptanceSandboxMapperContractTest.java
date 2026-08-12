package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class SupportAcceptanceSandboxMapperContractTest {
    @Test
    void sandboxWritesAreIndependentAndSourceBound() throws Exception {
        String create = sql(SupportAcceptanceSandboxMapper.class.getMethod(
                "createTicket", String.class, String.class, Long.class, String.class, String.class, java.time.LocalDateTime.class), Insert.class);
        String receipt = sql(SupportAcceptanceSandboxMapper.class.getMethod(
                "readCas", String.class, String.class, Long.class, Long.class, String.class, Long.class, String.class, java.time.LocalDateTime.class), Update.class);
        assertThat(create).contains("nx_support_acceptance_sandbox_ticket", "'mock'", "'SANDBOX'")
                .doesNotContain("nx_support_ticket");
        assertThat(receipt).contains("c.status=#{status}", "c.version=#{version}")
                .doesNotContain("nx_conversation_message_receipt");
        String adminList = sql(SupportAcceptanceSandboxMapper.class.getMethod(
                "adminConversations", String.class), Select.class);
        assertThat(adminList).contains("run_id=#{runId}", "source='mock'", "source_environment='SANDBOX'");
    }

    @Test
    void readUsesOneHeaderCasThenIdempotentlyUpsertsEveryVisibleAgentReceipt() throws Exception {
        String header = sql(SupportAcceptanceSandboxMapper.class.getMethod(
                "readHeaderCas", String.class, String.class, Long.class, String.class, Long.class, java.time.LocalDateTime.class), Update.class);
        String receipts = sql(SupportAcceptanceSandboxMapper.class.getMethod(
                "readCas", String.class, String.class, Long.class, Long.class, String.class, Long.class, String.class, java.time.LocalDateTime.class), Update.class);
        assertThat(header).contains("unread_count=0", "status=#{status}", "version=#{version}");
        assertThat(receipts).contains("m.id<=#{lastSeen}", "ON DUPLICATE KEY UPDATE", "sender_type='agent'");
    }

    @Test
    void receiptAuthorityLookupAndWriteAreExactRunAccountConversationAgentScoped() throws Exception {
        String authority = sql(SupportAcceptanceSandboxMapper.class.getMethod(
                "agentMessageExists", String.class, Long.class, String.class, Long.class), Select.class);
        String receipts = sql(SupportAcceptanceSandboxMapper.class.getMethod(
                "readCas", String.class, String.class, Long.class, Long.class, String.class, Long.class, String.class, java.time.LocalDateTime.class), Update.class);
        assertThat(authority).contains("id=#{lastSeen}", "conversation_no=#{id}", "account_id=#{accountId}", "run_id=#{runId}",
                "sender_type='agent'", "source='mock'", "source_environment='SANDBOX'");
        assertThat(receipts).contains("target.id=#{lastSeen}", "target.conversation_no=#{id}", "target.account_id=#{accountId}",
                "target.run_id=#{runId}", "target.sender_type='agent'", "target.source='mock'", "target.source_environment='SANDBOX'");
    }

    @Test
    void agentReplyAdvancesUnreadCounterInsideTheSameSandboxHeaderCas() throws Exception {
        String reply = sql(SupportAcceptanceSandboxMapper.class.getMethod(
                "agentReplyCas", String.class, String.class, Long.class, String.class, Long.class, String.class, java.time.LocalDateTime.class), Update.class);
        assertThat(reply).contains("unread_count=unread_count+1", "status=#{status}", "version=#{version}");
    }

    @Test
    void commandIdentityIsScopedToRunAccountAndCommandKeyNotBusinessResource() throws Exception {
        String insert = sql(SupportAcceptanceSandboxMapper.class.getMethod("commandInsert", String.class, String.class, Long.class,
                String.class, String.class, String.class, String.class, String.class, String.class, String.class, java.time.LocalDateTime.class), Insert.class);
        assertThat(insert).contains("command_key,run_id,account_id", "business_key");
    }

    @Test
    void opsReadbackLocatesCommandByRunAndKeyWithoutAProductionTable() throws Exception {
        String sql = sql(SupportAcceptanceSandboxMapper.class.getMethod("adminCommand", String.class, String.class), Select.class);
        assertThat(sql).contains("command_key=#{key}", "run_id=#{runId}", "nx_support_acceptance_sandbox_idempotency")
                .doesNotContain("nx_support_ticket", "nx_conversation");
    }

    @Test
    void migrationConvertsTheOldBusinessUniqueKeyToACompositeRunCommandIdentity() throws Exception {
        String ddl = Files.readString(Path.of("scripts/migrations/20260812_support_acceptance_sandbox.sql"));
        assertThat(ddl).contains("PRIMARY KEY (run_id, account_id, command_key)",
                "KEY idx_support_acceptance_command_business",
                "information_schema.TABLE_CONSTRAINTS", "PREPARE support_acceptance_ddl");
        assertThat(ddl).doesNotContain("ALTER TABLE nx_support_acceptance_sandbox_idempotency\n  DROP PRIMARY KEY");
    }

    @Test
    void sandboxTablesUseTheProductionCollationAndUpgradeExistingBaselinesReplaySafely() throws Exception {
        String ddl = Files.readString(Path.of("scripts/migrations/20260812_support_acceptance_sandbox.sql"));
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        for (String table : new String[]{"run", "ticket", "ticket_message", "conversation",
                "conversation_message", "receipt", "idempotency"}) {
            String name = "nx_support_acceptance_sandbox_" + table;
            assertThat(ddl).contains("ALTER TABLE " + name
                    + " CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        assertThat(ddl).doesNotContain("COLLATE=utf8mb4_unicode_ci");
        assertThat(schema.substring(schema.indexOf("CREATE TABLE IF NOT EXISTS nx_support_acceptance_sandbox_run")))
                .doesNotContain("COLLATE=utf8mb4_unicode_ci");
    }

    private String sql(Method method, Class<? extends java.lang.annotation.Annotation> annotation) {
        if (annotation == Insert.class) return String.join(" ", method.getAnnotation(Insert.class).value());
        if (annotation == Select.class) return String.join(" ", method.getAnnotation(Select.class).value());
        return String.join(" ", method.getAnnotation(Update.class).value());
    }
}
