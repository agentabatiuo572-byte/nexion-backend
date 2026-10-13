package ffdd.opsconsole.user.application;

import ffdd.opsconsole.user.mapper.UserOpsMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Arrays;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UserRegistrationWalletBirthContractTest {
    @Test void productionRegistrationInsertCarriesItsNonceAndDuplicateNeverChangesFundIdentity() {
        var overload = Arrays.stream(UserOpsMapper.class.getMethods())
            .filter(m -> m.getName().equals("ensureNewRegisteredUserWallet") && m.getParameterCount() == 3).findFirst();
        assertThat(overload).as("production wallet insert must distinguish an orphan upsert from a new birth").isPresent();
        String sql = String.join(" ", overload.orElseThrow().getAnnotation(Insert.class).value());
        assertThat(sql).contains("fund_incarnation", "#{incarnation}");
        String update = sql.substring(sql.indexOf("ON DUPLICATE KEY UPDATE"));
        assertThat(update).contains("is_deleted = 0", "sandbox = VALUES(sandbox)", "updated_at = NOW()")
            .doesNotContain("fund_incarnation", "fund_revision", "usdt_available", "nex_available");
    }

    @Test void legacyAndNewWalletStatementsRegisterTogetherInRealMybatis() {
        Configuration configuration = new Configuration();
        configuration.addMapper(UserOpsMapper.class);
        assertThat(configuration.hasStatement(UserOpsMapper.class.getName() + ".ensureRegisteredUserWallet")).isTrue();
        assertThat(configuration.hasStatement(UserOpsMapper.class.getName() + ".ensureNewRegisteredUserWallet")).isTrue();
        MybatisConfiguration actual = new MybatisConfiguration();
        actual.addMapper(UserOpsMapper.class);
        Map<String, Object> parameters = Map.of("userId", 7L, "sandbox", 0, "incarnation", "12345678-1234-1234-1234-123456789abc");
        assertThat(actual.getMappedStatement(UserOpsMapper.class.getName() + ".ensureNewRegisteredUserWallet")
                .getBoundSql(parameters).getSql()).contains("fund_incarnation");
        assertThat(actual.getMappedStatement(UserOpsMapper.class.getName() + ".ensureRegisteredUserWallet")
                .getBoundSql(parameters).getSql()).doesNotContain("fund_incarnation");
    }
}
