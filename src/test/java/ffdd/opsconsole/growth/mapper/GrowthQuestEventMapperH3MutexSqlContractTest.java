package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;

class GrowthQuestEventMapperH3MutexSqlContractTest {

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
