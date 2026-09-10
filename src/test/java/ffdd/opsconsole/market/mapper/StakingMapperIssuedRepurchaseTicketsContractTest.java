package ffdd.opsconsole.market.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class StakingMapperIssuedRepurchaseTicketsContractTest {

    @Test
    void issuedTicketTotalUsesTheHistoricalTicketRowsAndKeepsForfeitedIssuance() throws Exception {
        Method method = StakingMapper.class.getMethod("issuedRepurchaseTicketsSince", LocalDateTime.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value()).replaceAll("\\s+", " ");

        assertThat(sql)
                .contains("COALESCE(SUM(quantity), 0)")
                .contains("FROM nx_g7_repurchase_ticket")
                .contains("is_deleted = 0")
                .contains("issued_at >= #{since}")
                .doesNotContain("ticket_per_order")
                .doesNotContain("status =")
                .doesNotContain("DATE_FORMAT(NOW()");
    }
}
