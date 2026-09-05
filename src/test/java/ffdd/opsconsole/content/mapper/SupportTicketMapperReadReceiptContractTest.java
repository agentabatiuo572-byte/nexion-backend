package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Update;

class SupportTicketMapperReadReceiptContractTest {
    @Test
    void readCasIsOwnedAndCannotEraseANewerAgentReply() throws Exception {
        Method method = SupportTicketMapper.class.getMethod(
                "markUserRead", String.class, Long.class, String.class, Long.class, LocalDateTime.class);
        String sql = String.join(" ", method.getAnnotation(Update.class).value());

        assertThat(sql).contains("user_unread_count=0", "user_id=#{userId}",
                "status=#{expectedStatus}", "version=#{expectedVersion}", "version=version+1");
    }
}
