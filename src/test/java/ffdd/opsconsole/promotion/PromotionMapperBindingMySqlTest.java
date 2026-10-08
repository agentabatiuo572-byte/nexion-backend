package ffdd.opsconsole.promotion;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named="GROWTH_PROMOTION_RUNTIME",matches="1")
class PromotionMapperBindingMySqlTest {
    @Test
    void preservesRawColumnsPrecisionAndSpringTransactionWithoutCachedLockReads() throws Exception {
        var harness=new PromotionRuntimeHarness();
        var raw=harness.db.requiredRow("SELECT ? nullable_value,CAST(? AS DECIMAL(18,6)) exact_amount,JSON_OBJECT('value',?) document,? label",
                null,new BigDecimal("0.000001"),"quoted ? value","'; DROP TABLE nx_promotion; -- ?");
        assertThat(raw).containsKey("nullable_value");
        assertThat(raw.get("nullable_value")).isNull();
        assertThat(raw.get("exact_amount")).isEqualTo(new BigDecimal("0.000001"));
        assertThat(raw.get("label")).isEqualTo("'; DROP TABLE nx_promotion; -- ?");
        assertThat(new ObjectMapper().readTree((String)raw.get("document")).get("value").asText()).isEqualTo("quoted ? value");
        var activity=harness.db.requiredRow("SELECT activity_id,revision FROM nx_promotion WHERE activity_id=(SELECT resource_id FROM nx_audit_log WHERE id=?)",harness.evidence);
        var id=activity.get("activity_id");
        var revision=((Number)activity.get("revision")).longValue();
        new TransactionTemplate(new DataSourceTransactionManager(harness.dataSource)).execute(status->{
            assertThat(harness.db.count("SELECT revision FROM nx_promotion WHERE activity_id=? FOR UPDATE",id)).isEqualTo(revision);
            assertThat(harness.db.write("UPDATE nx_promotion SET revision=revision+1 WHERE activity_id=?",id)).isEqualTo(1);
            assertThat(harness.db.count("SELECT revision FROM nx_promotion WHERE activity_id=? FOR UPDATE",id)).isEqualTo(revision+1);
            assertThat(harness.jdbc.queryForObject("SELECT revision FROM nx_promotion WHERE activity_id=?",Long.class,id)).isEqualTo(revision+1);
            status.setRollbackOnly();return null;
        });
        assertThat(harness.db.count("SELECT revision FROM nx_promotion WHERE activity_id=?",id)).isEqualTo(revision);
        assertThat(harness.db.write("UPDATE nx_promotion SET revision=revision WHERE activity_id=?",harness.run+"-missing")).isZero();
    }
}
