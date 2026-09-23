package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class EventOutboxMapperWeeklyWaitContractTest {
    @Test
    void expiredWeeklyBindingWaitIsRequeuedForTerminalSkipWithoutActiveMission() throws Exception {
        String sql = String.join(" ", EventOutboxMapper.class
                .getMethod("requeuePublishedPendingBinding", String.class, String.class,
                        String.class, String.class, String.class)
                .getAnnotation(Update.class).value()).replaceAll("\\s+", " ");
        assertThat(sql).contains("d.status = #{pendingBindingStatus}",
                "m.id IS NOT NULL OR (o.is_server_authoritative=1",
                "DATE_FORMAT(o.event_ts,'%x-W%v')<>",
                "DATE_FORMAT(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00'),'%x-W%v')");
    }
}
