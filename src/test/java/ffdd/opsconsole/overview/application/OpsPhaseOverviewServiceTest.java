package ffdd.opsconsole.overview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.overview.mapper.OpsPhaseMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OpsPhaseOverviewServiceTest {
    @Mock private OpsPhaseMapper mapper;
    @Mock private GrowthRhythmFacade rhythmFacade;
    @Mock private AuditLogService auditLogService;

    private OpsPhaseOverviewService service;

    @BeforeEach
    void setUp() {
        service = new OpsPhaseOverviewService(mapper, rhythmFacade, auditLogService);
    }

    @Test
    void usesH1FacadeForDistributionAndExactlyEightCurrentDials() {
        when(rhythmFacade.snapshot()).thenReturn(snapshot(7, "P3"));
        when(rhythmFacade.snapshotAtMonth(8)).thenReturn(snapshot(8, "P4"));
        when(rhythmFacade.phaseForMonth(anyInt()))
                .thenAnswer(invocation -> phaseForMonth(invocation.getArgument(0)));
        when(mapper.selectAccountAgeMonthBuckets()).thenReturn(List.of(
                Map.of("monthAge", 0, "userCount", 10),
                Map.of("monthAge", 3, "userCount", 5),
                Map.of("monthAge", 40, "userCount", 2)));

        Map<String, Object> data = service.overview("PHASE", "7", null).getData();

        assertThat((List<?>) data.get("dials")).hasSize(8);
        assertThat((List<?>) data.get("phaseDistribution")).hasSize(6);
        assertThat(String.valueOf(data.get("sourceStatement"))).contains("H1");
        assertThat(String.valueOf(data)).doesNotContain("Premium", "NEXv2", "NEX v2");
        verify(auditLogService).record(any());
    }

    @Test
    void rejectsDirtyInputAndUnknownDialFailClosed() {
        when(rhythmFacade.snapshot()).thenReturn(snapshot(7, "P3"));
        when(mapper.selectAccountAgeMonthBuckets()).thenReturn(List.of(
                Map.of("monthAge", -1, "userCount", 10)));

        assertThatThrownBy(() -> service.overview("PHASE", null, null))
                .isInstanceOf(BizException.class)
                .hasMessage("B4_DISTRIBUTION_UNRELIABLE");
        assertThatThrownBy(() -> service.jump("premiumUnlock", "P3"))
                .isInstanceOf(BizException.class)
                .hasMessage("B4_DIAL_NOT_FOUND");
    }

    @Test
    void exportIsAggregateOnlyAndRequiresAudit() {
        when(rhythmFacade.snapshot()).thenReturn(snapshot(7, "P3"));
        when(rhythmFacade.phaseForMonth(anyInt()))
                .thenAnswer(invocation -> phaseForMonth(invocation.getArgument(0)));
        when(mapper.selectAccountAgeMonthBuckets()).thenReturn(List.of(
                Map.of("monthAge", 0, "userCount", 10)));

        OpsPhaseOverviewService.PhaseCsvFile file = service.export("PHASE", null, null);

        assertThat(new String(file.body(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("phase,user_count")
                .doesNotContain("user_id", "email", "phone");
        verify(auditLogService).recordRequired(any());
    }

    private static GrowthRhythmSnapshot snapshot(int month, String phase) {
        return new GrowthRhythmSnapshot(
                12, month, phase, 60,
                new BigDecimal("1.5"), new BigDecimal("1.5"), BigDecimal.ONE,
                new BigDecimal("20"), 30, new BigDecimal("2000"),
                BigDecimal.ONE, month >= 8, List.of("H1.rhythm.currentMonth", "growth.phase.*"));
    }

    private static String phaseForMonth(int month) {
        return GrowthRhythmSnapshot.phaseForRhythmMonth(month, 12);
    }
}
