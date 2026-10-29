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
    }
}
