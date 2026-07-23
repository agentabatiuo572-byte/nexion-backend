package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class ConversationMapperSqlTest {
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
        String transfer = updateSql("markTransferred", String.class, String.class, String.class, LocalDateTime.class);
        String accept = updateSql("acceptConversation", String.class, String.class, String.class, LocalDateTime.class);
        String returned = updateSql("returnConversation", String.class, String.class, String.class, LocalDateTime.class);
        String wait = updateSql("markTransferWait", String.class, String.class, LocalDateTime.class);
        String reply = updateSql("replyConversation", String.class, String.class, String.class, LocalDateTime.class);
        String status = updateSql("updateConversationStatus", String.class, String.class, String.class, LocalDateTime.class);
        String fallback = updateSql("fallbackConversation", String.class, String.class, String.class, LocalDateTime.class);

        assertThat(transfer).contains("status='OPEN'");
        assertThat(accept).contains("status='TRANSFERRED'");
        assertThat(returned).contains("status='TRANSFERRED'");
        assertThat(wait).contains("status='TRANSFERRED'").contains("status='PENDING'");
        assertThat(reply).contains("status=#{expectedStatus}");
        assertThat(status).contains("status=#{expectedStatus}");
        assertThat(fallback).contains("status='TRANSFERRED'");
    }

    @Test
    void receiptWatermarkUsesExplicitMessageIdAndJdbcOperators() throws Exception {
        String sql = String.join("\n", ConversationMessageMapper.class
                .getMethod(
                        "markAgentMessagesReadThrough",
                        String.class,
                        Long.class,
                        String.class,
                        LocalDateTime.class)
                .getAnnotation(Insert.class)
                .value());

        assertThat(sql)
                .contains("conversation_no=#{conversationNo}")
                .contains("sender_type='agent'")
                .contains("#{lastSeenMessageId} >= id")
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
                .getMethod("markConvertedToTicket", String.class, String.class, LocalDateTime.class)
                .getAnnotation(Update.class)
                .value());

        assertThat(sql)
                .contains("status='CLOSED'")
                .contains("status<>'CLOSED'")
                .contains("last_message=#{message}");
    }

    private String updateSql(String method, Class<?>... parameterTypes) throws Exception {
        return String.join("\n", ConversationMapper.class
                .getMethod(method, parameterTypes)
                .getAnnotation(Update.class)
                .value());
    }
}
