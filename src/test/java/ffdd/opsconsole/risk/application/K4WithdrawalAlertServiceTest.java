package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.risk.mapper.K4WithdrawalAlertMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class K4WithdrawalAlertServiceTest {

    @Test
    void returnsOnlyTheAuthenticatedRiskLeadsDurableReceiptsAndMarksOwnAlertRead() {
        K4WithdrawalAlertMapper mapper = mock(K4WithdrawalAlertMapper.class);
        K4WithdrawalAlertService service = new K4WithdrawalAlertService(mapper);
        when(mapper.listAlerts(11L, 50)).thenReturn(List.of(new K4WithdrawalAlertMapper.AlertRow(
                1L, "evt-1", "WD-1", 91, "ESCALATED", "k4-v13",
                "2026-07-22T12:00:00", null, LocalDateTime.now())));
        when(mapper.markRead("evt-1", 11L)).thenReturn(1);

        assertThat(service.alerts(11L).getData().get("alerts").toString())
                .contains("WD-1", "91", "ESCALATED");
        assertThat(service.markRead(11L, "evt-1").getData()).containsEntry("read", true);
    }
}
