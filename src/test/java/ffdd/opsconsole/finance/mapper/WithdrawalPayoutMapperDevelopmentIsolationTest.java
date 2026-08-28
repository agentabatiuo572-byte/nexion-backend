package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class WithdrawalPayoutMapperDevelopmentIsolationTest {

    @Test
    void providerQueueExcludesDevelopmentAccounts() throws Exception {
        String sql = selectSql("claimable");

        assertThat(normalize(sql))
                .contains("EXISTS ( SELECT 1 FROM nx_user u WHERE u.id=nx_withdrawal_order.user_id")
                .contains("u.sandbox=0");
    }

    @Test
    void developmentQueueOnlySelectsDevelopmentAccounts() throws Exception {
        String sql = selectSql("claimableDevelopment");

        assertThat(normalize(sql))
                .contains("EXISTS ( SELECT 1 FROM nx_user u WHERE u.id=nx_withdrawal_order.user_id")
                .contains("u.sandbox=1")
                .contains("status='REVIEW_PASSED'");
    }

    private String selectSql(String method) throws Exception {
        return String.join("\n", WithdrawalPayoutMapper.class
                .getMethod(method, java.time.LocalDateTime.class, int.class)
                .getAnnotation(Select.class)
                .value());
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
