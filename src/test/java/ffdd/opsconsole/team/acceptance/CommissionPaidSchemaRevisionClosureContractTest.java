package ffdd.opsconsole.team.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CommissionPaidSchemaRevisionClosureContractTest {

    @Test
    void activeRevisionCarriesMoneyAndNetworkFieldsTogether() throws Exception {
        String sql = Files.readString(Path.of(
                "scripts/migrations/20260829_commission_paid_schema_revision_closure.sql"));

        assertThat(sql)
                .contains("current_revision=312")
                .contains("'kind' property_name,'enum' property_type,1 required_field")
                .contains("'currency','enum',1")
                .contains("'amount','number',1")
                .contains("'commission_event_id','id',0")
                .contains("'source_user_id','id',0")
                .contains("'layer','number',0")
                .contains("'order_no','id',0")
                .contains("registry_revision=312")
                .contains("GREATEST(current_revision,312)");
    }
}
