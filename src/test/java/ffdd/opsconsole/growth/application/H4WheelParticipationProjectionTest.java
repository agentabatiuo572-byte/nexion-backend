package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static ffdd.opsconsole.growth.application.H4EventRuleService.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class H4WheelParticipationProjectionTest {
    private static final Long USER_ID = 60723153007L;
    private static final String CODE = "evt-spring-spin";
    private final H4EventRuleRepository db = mock(H4EventRuleRepository.class);
    private final H4EventRuleService policies = mock(H4EventRuleService.class);
    private final H4EventCommerceService commerce = mock(H4EventCommerceService.class);
    private final AppGrowthEngagementMapper events = mock(AppGrowthEngagementMapper.class);
    private final H4EventEngine engine = new H4EventEngine(db, policies, commerce, events,
            null, null, null, null, null);

    @BeforeEach
    void missingRowsAreNullAsInTheRealRepository() {
        when(db.row(anyString(), any(Object[].class))).thenReturn(null);
    }

    @ParameterizedTest
    @CsvSource({
            "AVAILABLE, 0, 1, false",
            "JOINED, 1, 1, false",
            "CLAIMED, 1, 1, false",
            "JOINED, 1, 1, true",
            "JOINED, 1, 0, false"
    })
    void listAndDetailPreserveReceiptProgressWithoutOfferingAClaim(
            String status, int progress, int enabled, boolean ended) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var rule = values("event_code", CODE, "kind", "wheel", "enabled", enabled,
                "starts_at", now.minusDays(2), "ends_at", ended ? now.minusDays(1) : now.plusDays(1));
        var source = values("eventCode", CODE, "kind", "wheel", "userStatus", status,
                "progressValue", progress, "targetValue", 1, "trackable", 0,
                "rewardType", "NEX", "rewardAmount", 30);
        when(policies.find(CODE, false)).thenReturn(rule);
        when(policies.rules(rule)).thenReturn(Map.of());
        when(events.eventState(USER_ID, "zh")).thenReturn(List.of(source));
        when(db.row(startsWith("SELECT *,TIMESTAMPDIFF"), eq(USER_ID)))
                .thenReturn(values("id", USER_ID));

        var list = engine.project(USER_ID, List.of(source), "zh");
        var detail = engine.detail(USER_ID, CODE, "zh").getData();
        assertThat(list).hasSize(1);
        assertThat(list.get(0)).containsEntry("userStatus", status)
                .containsEntry("progressValue", progress).containsEntry("targetValue", 1)
                .containsEntry("trackable", false).containsEntry("rewardType", "NEX")
                .containsEntry("rewardAmount", 30);
        assertThat(detail).containsEntry("userStatus", status)
                .containsEntry("progressValue", progress).containsEntry("targetValue", 1)
                .containsEntry("event", list.get(0));
        assertThat(detail.get("eligibilityReason")).isNotEqualTo("READY");
        assertThat(source.get("trackable")).isEqualTo(0);
        verify(events).eventState(USER_ID, "zh");
        assertThat(mockingDetails(db).getInvocations())
                .noneMatch(invocation -> invocation.getMethod().getName().equals("write"));
        verifyNoInteractions(commerce);
    }

    @Test
    void nonWheelProgressStillUsesItsCanonicalBusinessCalculation() {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        String code = "evt-refer-5-get-pro";
        var rule = values("event_code", code, "kind", "referral", "enabled", 1,
                "starts_at", now.minusDays(2), "activated_at", now.minusDays(1), "ends_at", now.plusDays(1));
        var rules = values("targetInvites", 5, "windowDays", 7, "giftProductNo", "PRO",
                "giftQuantity", 1, "deviceBudget", 10);
        var source = values("eventCode", code, "userStatus", "JOINED", "progressValue", 99, "targetValue", 99);
        when(policies.find(code, false)).thenReturn(rule);
        when(policies.rules(rule)).thenReturn(rules);
        when(commerce.progress(eq(USER_ID), eq(code), eq(rules), any(), any()))
                .thenReturn(values("current", 3, "target", 5, "eligible", false));

        assertThat(engine.project(USER_ID, List.of(source), "zh").get(0))
                .containsEntry("progressValue", 3).containsEntry("targetValue", 5)
                .containsEntry("trackable", true).containsEntry("userStatus", "JOINED");
    }
}
