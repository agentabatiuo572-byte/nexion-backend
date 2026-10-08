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
    @Test void walletOriginalPaymentRequiresSuccessfulHistoryAndAnyStoredLedgerLinkMustMatch() {
        var configuration=new Configuration();configuration.addMapper(SupportPaymentFactMapper.class);
        String sql=configuration.getMappedStatement(SupportPaymentFactMapper.class.getName()+".orders")
            .getBoundSql(Map.of("customerIds",List.of(7L))).getSql();
        assertThat(sql).contains("p.payment_status IN ('PAID','CONFIRMED','SUCCESS','REFUNDED')",
            "(p.wallet_ledger_id IS NULL OR p.wallet_ledger_id=l.id)");
        assertThat(sql.split("p.payment_status IN",-1)).hasSize(3);
        assertThat(sql.split("p.wallet_ledger_id IS NULL",-1)).hasSize(3);
    }
    @Test void unmatchedRowsRetainTheActualLedgerIdentityAndCorrectDepositOrPurchaseKind() {
        var configuration=new Configuration();configuration.addMapper(SupportPaymentFactMapper.class);
        String sql=configuration.getMappedStatement(SupportPaymentFactMapper.class.getName()+".unmatched")
            .getBoundSql(Map.of("customerIds",List.of(7L))).getSql();
        assertThat(sql).contains("l.id ledgerId","CASE WHEN l.direction='IN' THEN 'DEPOSIT' ELSE 'DEVICE_PURCHASE' END kind");
    }
}
