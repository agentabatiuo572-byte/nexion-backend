package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class ConversationMapperSqlTest {
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
}
