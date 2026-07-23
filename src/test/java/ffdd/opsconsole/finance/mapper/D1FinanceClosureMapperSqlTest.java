package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Collection;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.junit.jupiter.api.Test;

class D1FinanceClosureMapperSqlTest {

    @Test
    void automaticRiskLockUpsertsNeverOverwriteAnActiveManualLock() throws Exception {
        assertManualLockWins("upsertAutoBinLocks");
        assertManualLockWins("upsertAutoIpLocks");
        assertManualLockWins("upsertAutoDeviceLocks");
    }

    @Test
    void depositReadsRequireCanonicalD4BusinessIdentityAndExposeLegacyChargebackAsAbnormal() throws Exception {
        Method aggregate = DepositOrderMapper.class.getMethod("aggregateToday");
        String aggregateSql = String.join("\n", aggregate.getAnnotation(Select.class).value());
        Method count = DepositOrderMapper.class.getMethod(
                "countFlows", Collection.class, Long.class, String.class);
        String countSql = String.join("\n", count.getAnnotation(Select.class).value());
        Method page = DepositOrderMapper.class.getMethod(
                "pageFlows", Collection.class, Long.class, String.class, int.class, int.class);
        String pageSql = String.join("\n", page.getAnnotation(Select.class).value());

        assertThat(aggregateSql)
                .contains("d.deposit_no=l.biz_no")
                .contains("l.biz_type IN ('CHAIN_TOPUP','DEPOSIT','TOPUP')")
                .contains("d.asset=l.asset")
                .contains("d.amount=l.amount");
        assertThat(countSql)
                .contains("l.biz_no=d.deposit_no")
                .contains("THEN 'ABNORMAL' ELSE d.status END AS status");
        assertThat(pageSql)
                .contains("l.biz_no=d.deposit_no")
                .contains("D4充值分录绑定不一致")
                .contains("CHARGEBACK_REFUNDED")
                .contains("WHEN status = 'CHARGEBACK_RECOVERED' THEN '拒付已追回'")
                .contains("WHEN status = 'CHARGEBACK_PARTIAL' THEN '拒付部分追回'")
                .contains("WHEN status = 'CHARGEBACK_REVIEW' THEN '拒付复核中'")
                .doesNotContain("THEN COALESCE(failure_reason, '异常')");
    }

    @Test
    void annotatedDepositQueriesAreValidMybatisXmlScripts() {
        Configuration configuration = new Configuration();
        XMLLanguageDriver driver = new XMLLanguageDriver();

        for (Method method : DepositOrderMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            if (select != null) {
                driver.createSqlSource(configuration, String.join("\n", select.value()), Object.class);
            }
        }
    }

    private static void assertManualLockWins(String methodName) throws Exception {
        Method method = D1FinanceClosureMapper.class.getMethod(methodName, int.class, int.class);
        String sql = String.join("\n", method.getAnnotation(Insert.class).value());

        assertThat(sql)
                .contains("source='MANUAL' AND status='ACTIVE' AND locked_until > NOW()")
                .contains("source=IF(")
                .contains("reason=IF(")
                .contains("locked_until=IF(")
                .contains("created_by=IF(");
    }
}
