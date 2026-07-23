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
                .contains("COALESCE(k4o.override_score,k4.model_score) k4RiskScore");
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
