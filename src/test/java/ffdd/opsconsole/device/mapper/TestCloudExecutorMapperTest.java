package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.util.Map;
import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.jupiter.api.Test;

class TestCloudExecutorMapperTest {
    @Test void baseMapperRegistrationPreservesExplicitScopedExecutionStatements() {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(TestCloudExecutorMapper.class);
        new MybatisSqlSessionFactoryBuilder().build(configuration);
        String namespace = TestCloudExecutorMapper.class.getName() + ".";
        var insert = configuration.getMappedStatement(namespace + "insert");
        assertThat(insert.getSqlCommandType()).isEqualTo(SqlCommandType.INSERT);
        var bound = insert.getBoundSql(Map.of());
        assertThat(bound.getSql()).contains("INSERT INTO nx_test_cloud_execution");
        assertThat(bound.getParameterMappings()).allSatisfy(parameter ->
                assertThat(parameter.getProperty()).startsWith("e."));
        assertThat(configuration.getMappedStatement(namespace + "eligibleBinding").getBoundSql(Map.of()).getSql())
                .contains("user_id=?", "instance_no=?", "source_environment='PRODUCTION'", "run_id=''");
        // There is no aggregate primary key for inherited by-ID CRUD statements.
        assertThat(configuration.hasStatement(namespace + "selectById")).isFalse();
        String fleet=configuration.getMappedStatement(namespace+"fleetCandidates").getBoundSql(Map.of()).getSql();
        assertThat(fleet).contains("o.payment_status='PAID'","o.subtotal_usdt>0","o.amount_usdt>=0","d.activated_at>=?",
                "NOT IN ('PHONE','MOBILE')","d.pending_deactivate=0","ORDER BY d.id LIMIT ?",
                "r.agent_version<>CONCAT('test-cloud:fleet-',d.id)","nx_test_cloud_execution");
        String live=configuration.getMappedStatement(namespace+"liveFleetBinding").getBoundSql(Map.of()).getSql();
        assertThat(live).contains("FOR UPDATE OF o,d","d.user_id=?","d.instance_no=?");
        assertThat(configuration.getMappedStatement(namespace+"register").getSqlCommandType()).isEqualTo(SqlCommandType.INSERT);
        assertThat(configuration.getMappedStatement(namespace+"historicalReceipt").getBoundSql(Map.of()).getSql())
                .contains("t.user_id=?","t.user_device_id=?").doesNotContain("u.status","ownership_status");
    }
}
