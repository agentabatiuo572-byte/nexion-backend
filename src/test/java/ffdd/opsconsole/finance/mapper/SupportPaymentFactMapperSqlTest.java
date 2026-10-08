package ffdd.opsconsole.finance.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SupportPaymentFactMapperSqlTest {
    @Test void everySourceCompilesToParameterizedReadOnlyBatchSelectWithoutSupportOwnership() {
        var configuration=new Configuration();configuration.addMapper(SupportPaymentFactMapper.class);
        for(var method:SupportPaymentFactMapper.class.getDeclaredMethods()) {
            var statement=configuration.getMappedStatement(SupportPaymentFactMapper.class.getName()+"."+method.getName());
            assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
            var sql=statement.getBoundSql(Map.of("customerIds",List.of(7L,8L)));
            assertThat(sql.getParameterMappings()).hasSize(2);
            assertThat(sql.getSql()).contains(" IN", "?").doesNotContain("nx_support_agent","nx_support_group","FOR UPDATE","${", "created_at succeededAt,'nx_order");
        }
    }
    @Test void xmlDriverPreservesActualTrialAndRefundSourceJoins() {
        var driver=new XMLLanguageDriver();var configuration=new Configuration();
        String trial=driver.createSqlSource(configuration,SupportPaymentFactSql.TRIALS,Map.class)
            .getBoundSql(Map.of("customerIds",List.of(7L))).getSql();
        assertThat(trial).contains("c.user_device_id=d.id","o.paid_at succeededAt","c.settled_at sourceConfirmationAt",
                "l.created_at ledgerRecordedAt","c.settlement_amount_usdt=o.amount_usdt","'TRIAL_CHARGE'")
            .doesNotContain("d.is_deleted=0","d.source_channel='ORDER'");
        String refund=driver.createSqlSource(configuration,SupportPaymentFactSql.REFUNDS,Map.class)
            .getBoundSql(Map.of("customerIds",List.of(7L))).getSql();
        assertThat(refund).contains("l.biz_no=CONCAT('E4-REFUND-',o.order_no)","l.created_at succeededAt","l.biz_type='ORDER_REFUND'")
            .doesNotContain("nx_wallet_bill","CHARGEBACK_RECOVERY");
    }
}
