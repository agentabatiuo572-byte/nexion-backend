package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.mapper.AppDeviceRankMapper;
import ffdd.opsconsole.growth.application.DeviceRankConfigService;
import ffdd.opsconsole.growth.application.DeviceRankConfigService.Model;
import ffdd.opsconsole.growth.application.DeviceRankConfigService.Scope;
import ffdd.opsconsole.growth.application.HomeDisplayService;
import ffdd.opsconsole.growth.web.DeviceRankConfigController;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.access.prepost.PreAuthorize;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppDeviceRankServiceTest {
    final DeviceRankConfigService configs = mock(DeviceRankConfigService.class);
    final AppDeviceRankMapper mapper = mock(AppDeviceRankMapper.class);
    final HomeDisplayService display = mock(HomeDisplayService.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final ObjectMapper json = new ObjectMapper();
    final Scope scope = new Scope("PRODUCTION", "");
    final Model model = DeviceRankSamplesTest.model(82374);
    final AtomicReference<String> shared = new AtomicReference<>();
    AppDeviceRankService service;

    @BeforeEach void setup() {
        when(configs.scope()).thenReturn(scope);
        when(configs.model(scope)).thenReturn(model);
        when(mapper.userSandbox(7)).thenReturn(0);
        when(mapper.currentPhone(7, "PRODUCTION", "")).thenReturn(new AppDeviceRankMapper.Phone(11L, new BigDecimal("40")));
        when(display.current()).thenReturn(Map.of("serverNow", 2000L, "startAt", 1000L, "online", new long[]{99999, 100000}));
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(i -> shared.get());
        when(redis.execute(eq(AppDeviceRankService.PUBLISH), anyList(), any(Object[].class))).thenAnswer(i -> {
            shared.set(i.getArgument(2)); return 1L;
        });
        service = new AppDeviceRankService(configs, mapper, Optional.of(display), redis, json);
    }

    @Test void sharedRecoveryAndProcessRestartKeepSameRankAndSmallResponse() throws Exception {
        var first = service.snapshot(7L).getData();
        assertThat(first).containsEntry("state", "ranked").containsEntry("population", 100000L)
                .containsEntry("deviceId", 11L).containsEntry("deviceTops", new BigDecimal("40"))
                .containsEntry("source", "SIMULATED_DEVICE_DISTRIBUTION");
        assertThat(shared.get()).contains("seed", "algorithm", "tiers");
        assertThat(service.snapshot(7L).getData()).isEqualTo(first);
        shared.set(null);
        var restarted = new AppDeviceRankService(configs, mapper, Optional.of(display), redis, json);
        assertThat(restarted.snapshot(7L).getData()).isEqualTo(first);
        String response = json.writeValueAsString(first);
        assertThat(response).doesNotContain("seed", "tiers", "samples");
        assertThat(response.length()).isLessThan(512);
        System.out.println("DEVICE_RANK_RESPONSE_BYTES=" + response.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test void redisFailureDoesNotServeInventedOrCachedRankAndRecoveryUsesSameModel() {
        Object rank = service.snapshot(7L).getData().get("currentRank");
        when(values.get(anyString())).thenThrow(new IllegalStateException("Redis unavailable"));
        assertThat(service.snapshot(7L).getData()).containsEntry("state", "unavailable").containsEntry("currentRank", null);
        doAnswer(i -> shared.get()).when(values).get(anyString());
        assertThat(service.snapshot(7L).getData()).containsEntry("currentRank", rank).containsEntry("state", "ranked");
    }

    @Test void disabledUnboundZeroAndOutOfBoundsStatesHaveNoRank() {
        when(configs.model(scope)).thenReturn(new Model(2, false, model.tiers(), model.seed(), 1, model.generatedAt()));
        assertThat(service.snapshot(7L).getData()).containsEntry("state", "disabled").containsEntry("currentRank", null);
        verify(mapper, never()).currentPhone(anyLong(), anyString(), anyString());
        when(configs.model(scope)).thenReturn(model);
        when(mapper.currentPhone(7, "PRODUCTION", "")).thenReturn(null);
        assertThat(service.snapshot(7L).getData()).containsEntry("state", "unranked");
        when(mapper.currentPhone(7, "PRODUCTION", "")).thenReturn(new AppDeviceRankMapper.Phone(11L, BigDecimal.ZERO));
        assertThat(service.snapshot(7L).getData()).containsEntry("state", "unranked");
        when(mapper.currentPhone(7, "PRODUCTION", "")).thenReturn(new AppDeviceRankMapper.Phone(11L, BigDecimal.TEN));
        for (long population : new long[]{0, 10000001}) {
            when(display.current()).thenReturn(Map.of("serverNow", 0L, "startAt", 0L, "online", new long[]{population}));
            assertThat(service.snapshot(7L).getData()).containsEntry("state", "unavailable").containsEntry("currentRank", null);
        }
    }

    @Test void crossScopeAndOtherUsersCannotBeSelected() {
        when(mapper.userSandbox(7)).thenReturn(1);
        assertThatThrownBy(() -> service.snapshot(7L)).hasMessage("DEVICE_RANK_USER_SCOPE_REQUIRED");
        verify(mapper, never()).currentPhone(anyLong(), anyString(), anyString());
        assertThatThrownBy(() -> service.snapshot(null)).hasMessage("USER_AUTH_REQUIRED");
        Scope sandbox = new Scope("SANDBOX", "rank-run-20261025");
        when(configs.scope()).thenReturn(sandbox);
        when(configs.model(sandbox)).thenReturn(model);
        when(mapper.currentPhone(7, "SANDBOX", "rank-run-20261025")).thenReturn(new AppDeviceRankMapper.Phone(44L, BigDecimal.TEN));
        assertThat(service.snapshot(7L).getData()).containsEntry("state", "unavailable").containsEntry("sourceEnvironment", "SANDBOX");
        verifyNoInteractions(display, redis);
    }

    @Test void configFailureAndStalePublicationReturnUnavailable() {
        when(configs.model(scope)).thenThrow(new IllegalStateException("invalid"));
        assertThat(service.snapshot(7L).getData()).containsEntry("state", "unavailable");
        doReturn(model).when(configs).model(scope);
        when(redis.execute(eq(AppDeviceRankService.PUBLISH), anyList(), any(Object[].class))).thenReturn(0L);
        assertThat(service.snapshot(7L).getData()).containsEntry("state", "unavailable");
    }

    @Test void corruptFutureCacheIsRepairedButConfigChangedDuringRecoveryIsNotPublished() throws Exception {
        shared.set("{\"version\":999}");
        assertThat(service.snapshot(7L).getData()).containsEntry("state", "ranked");
        assertThat(shared.get()).isEqualTo(json.writeValueAsString(model));
        shared.set(null);
        Model next = new Model(2, true, model.tiers(), 19, 1, model.generatedAt());
        when(configs.model(scope)).thenReturn(model, next);
        assertThat(service.snapshot(7L).getData()).containsEntry("state", "unavailable");
        assertThat(shared.get()).isNull();
    }

    @Test void bindingSqlAndAdminPermissionsHaveExplicitBoundaries() throws Exception {
        String sql = String.join(" ", AppDeviceRankMapper.class.getMethod("currentPhone", long.class, String.class, String.class).getAnnotation(Select.class).value());
        assertThat(sql).contains("b.user_id=#{userId}", "d.id=b.user_device_id", "d.user_id=b.user_id",
                "d.source_environment=b.source_environment", "BINARY d.run_id=BINARY b.run_id", "b.source_environment=#{sourceEnvironment}",
                "BINARY b.run_id=BINARY #{runId}", "d.product_code='phone'", "d.activated_at IS NOT NULL", "d.pending_deactivate=0")
                .doesNotContain("SUM(", "GROUP BY");
        assertThat(DeviceRankConfigController.class.getMethod("overview").getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('growth_h9_read')");
        assertThat(DeviceRankConfigController.class.getMethod("update", String.class, ffdd.opsconsole.growth.dto.DeviceRankConfigRequest.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('growth_h9_write')");
    }
}
