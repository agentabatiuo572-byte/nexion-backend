package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;

class GrowthQuestEventMapperH3MutexSqlContractTest {

    @Test
    void missionRowsAndContractsExposeTheSameDatabaseIdentity() throws Exception {
        String rows = String.join(" ", GrowthQuestEventMapper.class.getMethod("missionRows", String.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        String contracts = String.join(" ", GrowthQuestEventMapper.class.getMethod("taskContracts")
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        assertThat(rows)
                .contains("SELECT id AS id")
                .contains("FROM nx_mission m")
                .contains("b.quest_code=m.mission_code")
                .doesNotContain("id - 1");
        assertThat(contracts).contains("SELECT id AS taskId");
    }

    @Test
    void h3MutexEnsureAcquiresOneExclusiveRowLockWithoutInsertIgnoreUpgradeDeadlock() throws Exception {
        Method ensure = GrowthQuestEventMapper.class.getMethod("ensureH3ConfigMutex");
        String sql = String.join(" ", ensure.getAnnotation(Insert.class).value())
                .replaceAll("\\s+", " ")
                .trim()
                .toUpperCase();

        assertThat(sql)
                .contains("INSERT INTO NX_ADMIN_OPERATION_MUTEX")
                .contains("ON DUPLICATE KEY UPDATE")
                .doesNotContain("INSERT IGNORE");
    }
}
