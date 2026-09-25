package ffdd.opsconsole.bi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.bi.application.BehaviorAnalyticsService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppBehaviorAnalyticsControllerTest {
    private final BehaviorAnalyticsService service = mock(BehaviorAnalyticsService.class);
    private final AppBehaviorAnalyticsController controller = new AppBehaviorAnalyticsController(service);
    private final BehaviorEventRequest view = new BehaviorEventRequest(
            "a".repeat(32), "store.viewed", "b".repeat(32), "/pages/store/store",
            null, null, null, null, null, 1_700_000_000_000L, "H5", "zh-CN");

    @Test
    void derivesStoreActorFromUserToken() {
        when(service.ingest(42L, view)).thenReturn(ApiResult.ok(Map.of("accepted", true)));

        assertThat(controller.ingest(auth("42", "USER"), view).getCode()).isZero();
        verify(service).ingest(42L, view);
    }

    @Test
    void rejectsAdminAndMissingSubjectsBeforeIngest() {
        assertThat(controller.ingest(auth("7", "ADMIN"), view).getCode()).isEqualTo(403);
        assertThat(controller.ingest(null, view).getCode()).isEqualTo(403);
        verify(service, never()).ingest(7L, view);
    }

    private UsernamePasswordAuthenticationToken auth(String id, String subjectType) {
        var authentication = new UsernamePasswordAuthenticationToken(id, null, java.util.List.of());
        authentication.setDetails(Map.of("subjectType", subjectType));
        return authentication;
    }
}
