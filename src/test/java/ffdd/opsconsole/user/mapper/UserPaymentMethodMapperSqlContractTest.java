package ffdd.opsconsole.user.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class UserPaymentMethodMapperSqlContractTest {

    @Test
    void paymentMethodReadsAvoidMysqlReservedRevokeAlias() {
        for (String methodName : new String[] {"findMethod", "listMethods"}) {
            Method method = Arrays.stream(UserPaymentMethodMapper.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow();
            String sql = String.join("\n", method.getAnnotation(Select.class).value());

            assertThat(sql).contains("nx_payment_method_revoke_command revoke_cmd");
            assertThat(sql).doesNotContain("nx_payment_method_revoke_command revoke ");
            assertThat(sql).doesNotContain("revoke.");
        }
    }
}
