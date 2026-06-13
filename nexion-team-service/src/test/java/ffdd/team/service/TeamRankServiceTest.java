package ffdd.team.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.exception.BizException;
import ffdd.team.dto.VRankConfigUpdateRequest;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TeamRankServiceTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final TeamRankService service = new TeamRankService(jdbcTemplate);

    @Test
    void updatesVRankConfigWithStructuredFields() {
        VRankConfigUpdateRequest request = new VRankConfigUpdateRequest();
        request.setTitleEn("Captain");
        request.setSelfBuyUsd(new BigDecimal("1000"));
        request.setDirectRefs(5);
        request.setTeamVolumeUsd(new BigDecimal("20000"));
        request.setUnilevelDepth("L2-L4");
        request.setPeerBonusRate(new BigDecimal("0.0500"));
        request.setLeadershipVotes(1);
        request.setPhysicalReward("Apple Watch SE");
        request.setStatus(1);

        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(vRankRow(4L, "V3")));

        Map<String, Object> saved = service.updateVRank(4L, request);

        assertThat(saved).containsEntry("id", 4L).containsEntry("rankCode", "V3");
        verify(jdbcTemplate).update(anyString(), any(Object[].class));
    }

    @Test
    void rejectsNegativeVRankThresholds() {
        VRankConfigUpdateRequest request = new VRankConfigUpdateRequest();
        request.setTeamVolumeUsd(new BigDecimal("-1"));

        assertThatThrownBy(() -> service.updateVRank(4L, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Team volume USD must be non-negative");
    }

    private Map<String, Object> vRankRow(Long id, String rankCode) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("rankCode", rankCode);
        return row;
    }
}
