package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.team.mapper.AppTeamNetworkMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppTeamNetworkServiceTest {
    @Test
    void returnsServerOwnedTotalsFromMembers() {
        AppTeamNetworkMapper mapper = mock(AppTeamNetworkMapper.class);
        when(mapper.members(7L)).thenReturn(List.of(new AppTeamNetworkMapper.MemberRow(
                42L, "Member 42", null, "V1", 1, "A", 7L, LocalDateTime.of(2026, 8, 13, 0, 0),
                new BigDecimal("12.5"), new BigDecimal("20"), "ACTIVE", "SG")));
        var result = new AppTeamNetworkService(mapper).snapshot(7L);
        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("totalMembers", 1).containsEntry("directMembers", 1)
                .containsEntry("source", "server").containsEntry("lifetimeVolumeUsdt", null);
    }
}
