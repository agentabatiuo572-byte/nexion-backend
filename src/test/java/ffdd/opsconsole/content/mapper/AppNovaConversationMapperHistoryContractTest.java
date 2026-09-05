package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppNovaConversationMapperHistoryContractTest {
    @Test
    void historyReadsTheNewestBoundedTurnWindowThenRestoresChronologicalOrder() throws Exception {
        Method method = AppNovaConversationMapper.class.getMethod(
                "turns", Long.class, String.class, String.class, int.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value()).replaceAll("\\s+", " ");

        assertThat(sql).contains(
                "FROM (",
                "#{beforeTurnId} IS NULL",
                "turn_id=#{beforeTurnId}",
                "ORDER BY id DESC",
                "LIMIT #{limit}",
                "ORDER BY recent.id ASC");
    }

    @Test
    void historyCanReportWhetherTheBoundedWindowOmittedOlderTurns() throws Exception {
        Method method = AppNovaConversationMapper.class.getMethod("countTurns", Long.class, String.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value()).replaceAll("\\s+", " ");

        assertThat(sql).contains("COUNT(*)", "WHERE user_id=#{userId} AND conversation_id=#{conversationId}");
    }
}
