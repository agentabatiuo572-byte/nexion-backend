package ffdd.opsconsole.team.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class F1RewardPayoutCursorContractTest {

    @Test
    void repositoryAndMapperExposeOnlyTheStableRowIdPagingContract() {
        assertThat(Arrays.stream(TeamCommissionRepository.class.getMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("queryRewardPayouts");
        assertThat(Arrays.stream(TeamCommissionMapper.class.getMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("queryRewardPayouts");
    }
}
