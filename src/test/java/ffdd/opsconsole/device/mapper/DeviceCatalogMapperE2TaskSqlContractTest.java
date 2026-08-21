package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class DeviceCatalogMapperE2TaskSqlContractTest {

    @Test
    void taskCountAndPageUseOnlyColumnsPresentInTheCanonicalTable() throws Exception {
        String countSql = selectSql("countTasks", String.class, String.class, String.class);
        String pageSql = selectSql("pageTasks", String.class, String.class, String.class, long.class, long.class);

        assertThat(countSql).contains("FROM nx_admin_device_task").contains("is_deleted = 0")
                .doesNotContain("active = 1");
        assertThat(pageSql).contains("FROM nx_admin_device_task").contains("is_deleted = 0")
                .doesNotContain("active = 1");
    }

    private String selectSql(String name, Class<?>... parameterTypes) throws Exception {
        Method method = DeviceCatalogMapper.class.getMethod(name, parameterTypes);
        return String.join("\n", method.getAnnotation(Select.class).value());
    }
}
