package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppSupportCursorPaginationContractTest {
    @Test
    void ticketAndConversationCursorQueriesUseImmutableIdsInsteadOfMutableActivityOrder() throws Exception {
        String tickets = String.join(" ", SupportTicketMapper.class.getMethod("pageTickets",
                        String.class, String.class, String.class, String.class, Long.class, Long.class,
                        String.class, Long.class, Boolean.class, long.class, long.class)
                .getAnnotation(Select.class).value());
        String conversations = String.join(" ", ConversationMapper.class.getMethod("pageConversations",
                        String.class, String.class, String.class, String.class, Long.class, Boolean.class,
                        Long.class, Boolean.class, long.class, long.class)
                .getAnnotation(Select.class).value());

        assertThat(tickets).contains("t.id &lt; #{beforeId}", "ORDER BY t.id DESC", "stableCursor");
        assertThat(conversations).contains("c.id &lt; #{beforeId}", "ORDER BY c.id DESC", "stableCursor");
    }
}
