package ffdd.opsconsole.auth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AccountDeletionPaginationSqlContractTest {
    @Test
    void countAndRecordsUseTheSameActiveStatusScope() throws Exception {
        String records = select("listAccountDeletions");
        String count = select("countAccountDeletions");

        assertThat(records).contains("is_deleted=0", "(#{status} IS NULL OR #{status}='' OR status=#{status})");
        assertThat(count).contains("is_deleted=0", "(#{status} IS NULL OR #{status}='' OR status=#{status})");
    }

    private String select(String methodName) throws Exception {
        Method method = java.util.Arrays.stream(AppUserSecurityMapper.class.getMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
        return String.join(" ", method.getAnnotation(Select.class).value());
    }
}
