package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class ConversationMapperSqlTest {
    @Test void everyHistoryReadResolvesLegacyActorLabelsWithoutChangingIdentityOrVisibility() {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(ConversationMessageMapper.class);
        for (String method : java.util.List.of("listByConversationNo", "listUserVisibleByConversationNo",
                "selectRecentVisible", "listRecentUserVisibleByConversationNoBefore")) {
            var parameters = new java.util.HashMap<String,Object>();
            parameters.put("conversationNo", "CV-BULK");
            parameters.put("limit", 20);
            parameters.put("beforeMessageId", 90L);
            parameters.put("currentRead", true);
            var bound = configuration.getMappedStatement(ConversationMessageMapper.class.getName() + "." + method)
                    .getBoundSql(parameters);
            assertThat(bound.getSql()).contains(ConversationMessageMapper.SENDER_NAME)
                    .contains("sender_id AS senderId", "sender_type AS senderType", "is_deleted=0")
                    .doesNotContain("JOIN nx_admin display_admin");
            assertThat(bound.getParameterMappings()).extracting(org.apache.ibatis.mapping.ParameterMapping::getProperty)
                    .contains("conversationNo");
            if (!"listByConversationNo".equals(method)) assertThat(bound.getSql()).contains("sender_type IN ('user','agent')");
            if (method.contains("Recent")) assertThat(bound.getSql()).contains("ORDER BY id DESC LIMIT ?");
            if ("selectRecentVisible".equals(method)) assertThat(bound.getSql()).contains("FOR SHARE");
        }
        assertThat(ConversationMessageMapper.SENDER_NAME)
                // Legacy "admin:<id>" labels and labels that equal the sender's login name both resolve to the display name.
                .contains("sender_type='agent' AND (sender_name=CONCAT('admin:',sender_id) ",
                        "login_admin.id=sender_id AND login_admin.username=sender_name")
                .contains("display_admin.id=sender_id AND display_admin.is_deleted=0")
                .contains("ELSE sender_name END", "'客服'");
    }

    @Test
    void stateCommandsAcquireHeaderThenPendingTransferLocks() throws Exception {
        String headerSql = String.join("\n", ConversationMapper.class
                .getMethod("lockConversationHeader", String.class)
                .getAnnotation(Select.class)
                .value());
        String transferSql = String.join("\n", ConversationMapper.class
                .getMethod("lockPendingTransfers", String.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(headerSql)
                .contains("FROM nx_conversation")
                .contains("FOR UPDATE");
        assertThat(transferSql)
                .contains("FROM nx_conversation_transfer")
                .contains("status='PENDING'")
                .contains("FOR UPDATE");
    }

    @Test
    void allMutableConversationHeadersUseExpectedStateCas() throws Exception {
        String transfer = updateSql("markTransferred", String.class, String.class, String.class, Long.class, LocalDateTime.class);
        String accept = updateSql("acceptConversation", String.class, String.class, String.class, Long.class, LocalDateTime.class);
        String returned = updateSql("returnConversation", String.class, String.class, String.class, Long.class, LocalDateTime.class);
        String wait = updateSql("markTransferWait", String.class, String.class, Long.class, LocalDateTime.class);
        String reply = updateSql("replyConversation", String.class, String.class, String.class, Long.class, LocalDateTime.class);
        String status = updateSql("updateConversationStatus", String.class, String.class, String.class, Long.class, LocalDateTime.class);
        String fallback = updateSql("fallbackConversation", String.class, String.class, String.class, Long.class, LocalDateTime.class);

        assertThat(transfer).contains("status='OPEN'", "version=#{expectedVersion}");
        assertThat(accept).contains("status='TRANSFERRED'", "version=#{expectedVersion}");
        assertThat(returned).contains("status='TRANSFERRED'", "version=#{expectedVersion}");
        assertThat(wait).contains("status='TRANSFERRED'", "status='PENDING'", "version=#{expectedVersion}");
        assertThat(reply).contains("status=#{expectedStatus}", "version=#{expectedVersion}");
        assertThat(status).contains("status=#{expectedStatus}", "version=#{expectedVersion}");
        assertThat(fallback).contains("status='TRANSFERRED'", "version=#{expectedVersion}");
    }

    @Test
    void receiptWatermarkUsesExplicitMessageIdAndJdbcOperators() throws Exception {
        String sql = String.join("\n", ConversationMessageMapper.class
                .getMethod(
                        "markAgentMessagesReadThrough",
                        String.class,
                        Long.class,
                        String.class,
                        LocalDateTime.class,
                        String.class,
                        Long.class)
                .getAnnotation(Insert.class)
                .value());

        assertThat(sql)
                .contains("conversation_no=#{conversationNo}")
                .contains("sender_type='agent'")
                .contains("#{lastSeenMessageId} >= msg.id")
                .contains("conversation.status=UPPER(#{expectedStatus})")
                .contains("conversation.version=#{expectedVersion}")
                .doesNotContain("ORDER BY")
                .doesNotContain("LIMIT 1")
                .doesNotContain("&lt;")
                .doesNotContain("&gt;");
    }

    @Test
    void overdueTransferQueryUsesJdbcOperators() throws Exception {
        String sql = String.join("\n", ConversationMapper.class
                .getMethod("overdueTransferredConversations", LocalDateTime.class, int.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("t.status='PENDING'")
                .contains("c.status='TRANSFERRED'")
                .contains("t.transferred_at <= #{cutoff}")
                .contains("COALESCE(t.to_type,'') <> 'standby'")
                .doesNotContain("&lt;")
                .doesNotContain("&gt;");
    }

    @Test
    void fallbackClaimUpdateUsesJdbcOperator() throws Exception {
        String sql = String.join("\n", ConversationMapper.class
                .getMethod(
                        "markTransferFallback",
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        LocalDateTime.class)
                .getAnnotation(Update.class)
                .value());

        assertThat(sql)
                .contains("status='PENDING'")
                .contains("COALESCE(to_type,'') <> 'standby'")
                .contains("fallback_at IS NULL")
                .doesNotContain("&lt;")
                .doesNotContain("&gt;");
    }

    @Test
    void ticketConversionIsAnAtomicTerminalClaim() throws Exception {
        String sql = String.join("\n", ConversationMapper.class
                .getMethod("markConvertedToTicket", String.class, String.class, Long.class, LocalDateTime.class)
                .getAnnotation(Update.class)
                .value());

        assertThat(sql)
                .contains("status='CLOSED'")
                .contains("status<>'CLOSED'")
                .contains("version=#{expectedVersion}")
                .contains("last_message=#{message}");
    }

    private String updateSql(String method, Class<?>... parameterTypes) throws Exception {
        return String.join("\n", ConversationMapper.class
                .getMethod(method, parameterTypes)
                .getAnnotation(Update.class)
                .value());
    }
}
