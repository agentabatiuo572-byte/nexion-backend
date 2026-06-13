package ffdd.compute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import ffdd.common.api.ApiResult;
import ffdd.common.exception.BizException;
import ffdd.compute.client.SystemConfigClient;
import ffdd.compute.domain.TrialClaim;
import ffdd.compute.domain.UserDevice;
import ffdd.compute.dto.TrialClaimResponse;
import ffdd.compute.mapper.TrialClaimMapper;
import ffdd.compute.service.impl.TrialClaimServiceImpl;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrialClaimServiceTest {
    private final TrialClaimMapper trialClaimMapper = mock(TrialClaimMapper.class);
    private final ComputeService computeService = mock(ComputeService.class);
    private final SystemConfigClient systemConfigClient = mock(SystemConfigClient.class);
    private final TrialClaimService service = new TrialClaimServiceImpl(trialClaimMapper, computeService, systemConfigClient);

    @Test
    void claimCreatesDurableTrialRecordAndActivatesTrialDevice() {
        when(trialClaimMapper.selectOne(anyWrapper())).thenReturn(null);
        when(systemConfigClient.growth()).thenReturn(ApiResult.ok(Map.of(
                "trial.enabled", true,
                "trial.claim_enabled", true,
                "trial.device_name", "NexionBox S1",
                "trial.duration_days", new BigDecimal("3"),
                "trial.daily_usdt", new BigDecimal("38.50"),
                "trial.daily_nex", new BigDecimal("65"),
                "trial.seats_left_today", new BigDecimal("47"),
                "trial.offset_cap_usdt", new BigDecimal("50"),
                "trial.price_usdt", new BigDecimal("1299"))));
        UserDevice device = new UserDevice();
        device.setId(88L);
        device.setName("NexionBox S1");
        device.setStatus("ONLINE");
        when(computeService.activateDevices(any())).thenReturn(List.of(device));

        TrialClaimResponse response = service.claim(10001L, "REQ-1");

        assertThat(response.getClaimNo()).startsWith("TRIAL-");
        assertThat(response.getStatus()).isEqualTo("CLAIMED");
        assertThat(response.getUserDeviceId()).isEqualTo(88L);
        assertThat(response.getDeviceName()).isEqualTo("NexionBox S1");
        assertThat(response.getDurationDays()).isEqualTo(3);
        assertThat(response.getDailyUsdt()).isEqualByComparingTo("38.50");
        assertThat(response.getDailyNex()).isEqualByComparingTo("65");
        assertThat(response.getSeatsLeftToday()).isEqualTo(47);
        assertThat(response.getExpiresAt()).isNotNull();
        verify(trialClaimMapper).insert(any(TrialClaim.class));
        verify(trialClaimMapper).updateById(any(TrialClaim.class));
        verify(computeService).activateDevices(any());
    }

    @Test
    void duplicateClaimReturnsExistingRecordWithoutActivatingAgain() {
        TrialClaim existing = new TrialClaim();
        existing.setId(7L);
        existing.setUserId(10001L);
        existing.setClaimNo("TRIAL-EXISTING");
        existing.setStatus("CLAIMED");
        existing.setDeviceName("NexionBox S1");
        existing.setDurationDays(3);
        existing.setDailyUsdt(new BigDecimal("38.50"));
        existing.setDailyNex(new BigDecimal("65"));
        existing.setSeatsLeftToday(46);
        existing.setOffsetCapUsdt(new BigDecimal("50"));
        existing.setPriceUsdt(new BigDecimal("1299"));
        existing.setUserDeviceId(88L);
        when(trialClaimMapper.selectOne(anyWrapper())).thenReturn(existing);

        TrialClaimResponse response = service.claim(10001L, "REQ-2");

        assertThat(response.getClaimNo()).isEqualTo("TRIAL-EXISTING");
        assertThat(response.getUserDeviceId()).isEqualTo(88L);
        verify(computeService, never()).activateDevices(any());
        verify(trialClaimMapper, never()).insert(any(TrialClaim.class));
    }

    @Test
    void claimRejectsWhenBackendConfigDisablesClaimFlow() {
        when(trialClaimMapper.selectOne(anyWrapper())).thenReturn(null);
        when(systemConfigClient.growth()).thenReturn(ApiResult.ok(Map.of(
                "trial.enabled", true,
                "trial.claim_enabled", false)));

        assertThatThrownBy(() -> service.claim(10001L, "REQ-3"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Trial claim is not open");
        verify(computeService, never()).activateDevices(any());
    }

    @Test
    void currentReturnsLatestClaimForUser() {
        TrialClaim existing = new TrialClaim();
        existing.setUserId(10001L);
        existing.setClaimNo("TRIAL-ME");
        existing.setStatus("CLAIMED");
        existing.setDeviceName("NexionBox S1");
        existing.setDurationDays(3);
        when(trialClaimMapper.selectOne(anyWrapper())).thenReturn(existing);

        TrialClaimResponse response = service.current(10001L);

        assertThat(response.getClaimNo()).isEqualTo("TRIAL-ME");
        assertThat(response.getStatus()).isEqualTo("CLAIMED");
    }

    @SuppressWarnings("unchecked")
    private static Wrapper<TrialClaim> anyWrapper() {
        return any(Wrapper.class);
    }
}
