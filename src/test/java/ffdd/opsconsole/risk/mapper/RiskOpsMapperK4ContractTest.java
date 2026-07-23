package ffdd.opsconsole.risk.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class RiskOpsMapperK4ContractTest {

    @Test
    void modelProjectionAndBatchTargetsExcludeDeletedOrMissingCanonicalUsers() throws Exception {
        assertTargetsOnlyActiveCanonicalUsers("scoreUserNosNeedingProjection", long.class, int.class);
        assertTargetsOnlyActiveCanonicalUsers("countScoreUsersNeedingProjection", long.class);
        assertTargetsOnlyActiveCanonicalUsers("scoreUserNos");
    }

    private void assertTargetsOnlyActiveCanonicalUsers(String methodName, Class<?>... parameterTypes)
            throws Exception {
        Select select = RiskOpsMapper.class.getMethod(methodName, parameterTypes).getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").toLowerCase();

        assertThat(sql)
                .as("%s must derive K4 targets from active nx_user truth", methodName)
                .contains("join nx_user u", "u.is_deleted=0");
    }
}
