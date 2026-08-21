package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppGrowthEngagementMapperUserScopeContractTest {

    @Test
    void canonicalQuestReadsAcceptAnyActiveDevelopmentAccount() throws Exception {
        Method method = AppGrowthEngagementMapper.class.getMethod("findActiveUser", Long.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ").toLowerCase();

        assertThat(sql)
                .contains("status='active'")
                .contains("is_deleted=0")
                .doesNotContain("sandbox");
    }
}
