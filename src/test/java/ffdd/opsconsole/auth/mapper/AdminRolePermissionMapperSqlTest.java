package ffdd.opsconsole.auth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AdminRolePermissionMapperSqlTest {

    @Test
    void mapperMethodsMustNotOverloadMyBatisStatementIds() {
        Map<String, List<Method>> byName = Arrays.stream(AdminRolePermissionMapper.class.getDeclaredMethods())
                .collect(Collectors.groupingBy(Method::getName));

        List<String> duplicateNames = byName.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(Map.Entry::getKey)
                .toList();

        assertThat(duplicateNames)
                .as("MyBatis keys statements by namespace + method name, so overloads shadow SQL mappings")
                .isEmpty();
    }
}
