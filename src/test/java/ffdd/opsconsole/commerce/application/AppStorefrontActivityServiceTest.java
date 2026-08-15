package ffdd.opsconsole.commerce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.commerce.mapper.AppStorefrontActivityMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppStorefrontActivityServiceTest {
    private final AppStorefrontActivityMapper mapper = mock(AppStorefrontActivityMapper.class);
    private final AppStorefrontActivityService service = new AppStorefrontActivityService(mapper);

    @Test
    void activityReturnsOnlyAnonymousServerFactsAndOpaqueCursor() {
        when(mapper.userEnvironment(7L)).thenReturn(new AppStorefrontActivityMapper.UserEnvironmentRow(false));
        when(mapper.recentActivities(eq(false), isNull(), isNull(), eq(4))).thenReturn(List.of(
                new AppStorefrontActivityMapper.ActivityRow(
                        42L, "NexionBox Pro", LocalDateTime.of(2026, 8, 15, 11, 37), 1)));

        ApiResult<Map<String, Object>> result = service.activity(7L, null, 3);

        assertThat(result.getCode()).isZero();
        Map<String, Object> data = result.getData();
        assertThat(data).containsEntry("sourceEnvironment", "PRODUCTION");
        assertThat(data).containsKey("items").doesNotContainKeys("userId", "orderNo", "walletAddress", "country");
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) ((List<?>) data.get("items")).get(0);
        assertThat(item).containsEntry("eventType", "ORDER_PAID")
                .containsEntry("productName", "NexionBox Pro")
                .containsEntry("occurredAt", "2026-08-15T11:00");
        assertThat(item).doesNotContainKeys("userId", "orderNo", "walletAddress", "country", "quantity");
        assertThat(data.get("nextCursor")).isNull();
    }

    @Test
    void activityRejectsOutOfRangeLimitAndMalformedCursorBeforeQuery() {
        assertThat(service.activity(7L, null, 0).getCode()).isEqualTo(400);
        assertThat(service.activity(7L, "not-a-cursor", 20).getCode()).isEqualTo(400);
        verifyNoInteractions(mapper);
    }

    @Test
    void socialProofUsesTheAuthenticatedUserEnvironmentAndWhitelistedWindow() {
        when(mapper.userEnvironment(8L)).thenReturn(new AppStorefrontActivityMapper.UserEnvironmentRow(true));
        when(mapper.product("stellarbox-pro-v2")).thenReturn(
                new AppStorefrontActivityMapper.ProductRow(12L, "NexionBox Pro"));
        when(mapper.salesTotal(12L, true)).thenReturn(91L);
        when(mapper.salesSince(eq(12L), eq(true), any(LocalDateTime.class))).thenReturn(4L);

        ApiResult<Map<String, Object>> result = service.socialProof(8L, "stellarbox-pro-v2", 30);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("cumulativeSales", 91L)
                .containsEntry("windowSales", 4L)
                .containsEntry("windowDays", 30)
                .doesNotContainKey("viewing");
        verify(mapper).salesTotal(12L, true);
        verify(mapper).salesSince(eq(12L), eq(true), any(LocalDateTime.class));
    }

    @Test
    void socialProofRejectsUnknownWindowAndProductWithoutReadingSales() {
        assertThat(service.socialProof(8L, "stellarbox-pro-v2", 14).getCode()).isEqualTo(400);
        verifyNoInteractions(mapper);

        when(mapper.userEnvironment(8L)).thenReturn(new AppStorefrontActivityMapper.UserEnvironmentRow(false));
        when(mapper.product("missing")).thenReturn(null);
        assertThat(service.socialProof(8L, "missing", 30).getCode()).isEqualTo(404);
        verify(mapper, never()).salesSince(anyLong(), anyBoolean(), any());
    }
}
