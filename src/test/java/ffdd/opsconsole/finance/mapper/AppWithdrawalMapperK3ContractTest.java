package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppWithdrawalMapperK3ContractTest {
    @Test
    void canonicalFactsInclude24hSumAgeAndAddressReputation() throws Exception {
        Method method = AppWithdrawalMapper.class.getMethod(
                "withdrawalRiskFacts", Long.class, String.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value());

        assertThat(sql).contains("SUM(w.amount)")
                .contains("TIMESTAMPDIFF(DAY")
                .contains("ADDRESS_REPUTATION_LOW")
                .contains("SHA2(#{targetAddress},256)")
                .contains("nx_admin_risk_score_model k4m")
                .contains("k4m.state='active'")
                .contains("k4.as_of>=DATE_SUB(NOW(),INTERVAL 1 DAY)")
                .contains("CASE WHEN COALESCE(u.sandbox,0)=1 AND k4.user_no IS NULL THEN 0 END")
                .contains("CASE WHEN COALESCE(u.sandbox,0)=1 AND k4.user_no IS NULL")
                .contains("CONCAT('k4-v',k4m.model_version)")
                .contains("THEN NOW() END")
                .doesNotContain("CASE WHEN COALESCE(u.sandbox,0)=0");
    }

    @Test
    void insertPersistsServerCanonicalRouteAndFreezeProvenance() throws Exception {
        Method method = AppWithdrawalMapper.class.getMethod(
                "insertWithdrawal", AppWithdrawalMapper.WithdrawalWrite.class);
        String sql = String.join(" ", method.getAnnotation(Insert.class).value());

        assertThat(sql).contains("#{status}")
                .contains("#{failureReason}")
                .contains("#{previousStatus}")
                .doesNotContain("'REVIEW_PENDING'");
    }
}
