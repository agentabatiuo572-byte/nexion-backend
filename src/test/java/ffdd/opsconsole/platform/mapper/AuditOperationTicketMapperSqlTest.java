package ffdd.opsconsole.platform.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AuditOperationTicketMapperSqlTest {
    @Test
    void decisionLookupLocksThePendingTicketUntilReplayAndTerminalUpdateCommit() throws Exception {
        String sql = String.join("\n", AuditOperationTicketMapper.class
                .getMethod("selectActiveByOperationIdForUpdate", String.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("WHERE operation_id = #{operationId}")
                .contains("AND is_deleted = 0")
                .contains("FOR UPDATE");
    }
}
