package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BinaryCommissionSettlementMapperContractTest {
    @Test
    void paidVolumeProjectionIsRecursiveStrictAndRefundAware() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/team/mapper/BinaryCommissionSettlementMapper.java"));

        assertThat(source).contains("WITH RECURSIVE assigned_tree");
        assertThat(source).contains("UPPER(COALESCE(o.payment_status,'')) IN ('PAID','CONFIRMED','SUCCESS')");
        assertThat(source).contains("'REFUNDED','CHARGEBACK','CANCELLED','FAILED','EXPIRED','REVERSED'");
        assertThat(source).doesNotContain("OR o.paid_at IS NOT NULL");
        assertThat(source).contains("REVERSAL_REQUIRED", "SOURCE_ORDER_NOT_PAID");
        assertThat(source).contains("DATE_FORMAT(v.paid_at,'%Y-%m-01')");
        assertThat(source).contains("consumedMatchedInMonth");
        assertThat(source).doesNotContain("settlement_date&lt;#{settlementDate}");
        assertThat(source).contains("FOR UPDATE", "nx_binary_settlement_mutex");
    }
}
