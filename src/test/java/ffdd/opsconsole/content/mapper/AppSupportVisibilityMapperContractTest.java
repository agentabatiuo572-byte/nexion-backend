package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppSupportVisibilityMapperContractTest {
    @Test
    void ticketAppProjectionExcludesInternalAndSystemMessagesAtSqlBoundary() throws Exception {
        Method method = SupportTicketMessageMapper.class
                .getMethod("listUserVisibleByTicketNo", String.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value());
        assertThat(sql).contains("sender_type IN ('user','agent')");
        assertThat(sql).doesNotContain("internal");
    }

    @Test
    void conversationAppProjectionExcludesOperationalSystemTracesAtSqlBoundary() throws Exception {
        Method method = ConversationMessageMapper.class
                .getMethod("listUserVisibleByConversationNo", String.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value());
        assertThat(sql).contains("msg.sender_type IN ('user','agent')");
        assertThat(sql).doesNotContain("'system'");
    }

    @Test
    void repeatedReadDoesNotTouchAlreadyReadReceiptRows() throws Exception {
        Method method = ConversationMessageMapper.class.getMethod(
                "markAgentMessagesReadThrough", String.class, Long.class, String.class, java.time.LocalDateTime.class);
        String sql = String.join(" ", method.getAnnotation(Insert.class).value());
        assertThat(sql).contains("existing.receipt_status<>'read'");
    }
}
