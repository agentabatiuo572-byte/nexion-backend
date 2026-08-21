package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class AppGrowthWheelSandboxMapperSqlContractTest {
    @Test
    void everySandboxStatementCarriesRunAndUserScopeAndMockTags() {
        String sql = Arrays.stream(AppGrowthWheelSandboxMapper.class.getMethods())
                .flatMap(method -> Arrays.stream(new String[] {
                        annotation(method, Select.class), annotation(method, Insert.class), annotation(method, Update.class)
                }))
                .filter(value -> value != null)
                .map(value -> value.toLowerCase())
                .reduce("", (left, right) -> left + " " + right);

        assertThat(sql).contains("run_id", "user_id", "source", "sandbox");
        assertThat(sql).doesNotContain("nx_user_wallet", "nx_wallet_ledger", "nx_growth_wheel_spin",
                "nx_growth_spin_ticket", "nx_outbox");
    }

    @Test
    void schemaProbeResolvesCheckConstraintOwnershipThroughTableConstraints() throws Exception {
        Select select = AppGrowthWheelSandboxMapper.class
                .getMethod("sandboxSchemaTableCount")
                .getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).toLowerCase().replaceAll("\\s+", " ");

        assertThat(sql)
                .contains("information_schema.check_constraints cc")
                .contains("information_schema.table_constraints tc")
                .contains("tc.table_name")
                .contains("seq_in_index=1")
                .doesNotContain("from information_schema.check_constraints where");
    }

    private static <A extends java.lang.annotation.Annotation> String annotation(Method method, Class<A> type) {
        A value = method.getAnnotation(type);
        if (value instanceof Select select) return String.join(" ", select.value());
        if (value instanceof Insert insert) return String.join(" ", insert.value());
        if (value instanceof Update update) return String.join(" ", update.value());
        return null;
    }
}
