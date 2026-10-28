package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppSupportCursorPaginationContractTest {
    @Test
    void conversationCursorQueriesUseImmutableIdsInsteadOfMutableActivityOrder() throws Exception {
        String conversations = String.join(" ", ConversationMapper.class.getMethod("pageConversations",
                        String.class, String.class, String.class, String.class, Long.class, Boolean.class,
                        Long.class, Boolean.class, long.class, long.class, Boolean.class)
                .getAnnotation(Select.class).value());

        assertThat(conversations).contains("c.id &lt; #{beforeId}", "ORDER BY c.id DESC", "stableCursor");
    }
}
