package ffdd.compute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import ffdd.compute.domain.DeviceLifecycleRule;
import ffdd.compute.domain.UserDevice;
import ffdd.compute.dto.DeviceLifecycleResponse;
import ffdd.compute.mapper.DeviceLifecycleRuleMapper;
import ffdd.compute.mapper.UserDeviceMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeviceLifecycleServiceTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-05-26T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final DeviceLifecycleRuleMapper ruleMapper = mock(DeviceLifecycleRuleMapper.class);
    private final UserDeviceMapper userDeviceMapper = mock(UserDeviceMapper.class);
    private final DeviceLifecycleService service = new DeviceLifecycleService(ruleMapper, userDeviceMapper, CLOCK);

    @Test
    void evaluatesDefaultSegmentedDecayWithFloor() {
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(defaultRules());
        UserDevice device = device("NEXION_BOX", "S1", 1L, LocalDateTime.of(2025, 5, 26, 8, 0));

        DeviceLifecycleResponse response = service.evaluate(device);

        assertThat(response.getMonthsOwned()).isEqualTo(12);
        assertThat(response.getCurrentEfficiency()).isEqualByComparingTo("0.426013");
        assertThat(response.getEffectiveDailyUsdt()).isEqualByComparingTo("16.410021");
        assertThat(response.isExempt()).isFalse();
    }

    @Test
    void appliesFloorAfterLongLifecycle() {
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(defaultRules());
        UserDevice device = device("NEXION_BOX", "S1", 1L, LocalDateTime.of(2023, 5, 26, 8, 0));

        DeviceLifecycleResponse response = service.evaluate(device);

        assertThat(response.getCurrentEfficiency()).isEqualByComparingTo("0.220000");
        assertThat(response.getEffectiveDailyUsdt()).isEqualByComparingTo("8.474400");
    }

    @Test
    void productTypeExemptKeepsFullEfficiency() {
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(defaultRules());
        UserDevice device = device("MOBILE", null, null, LocalDateTime.of(2024, 5, 26, 8, 0));

        DeviceLifecycleResponse response = service.evaluate(device);

        assertThat(response.isExempt()).isTrue();
        assertThat(response.getCurrentEfficiency()).isEqualByComparingTo("1.000000");
        assertThat(response.getEffectiveDailyUsdt()).isEqualByComparingTo("38.520000");
    }

    @Test
    void productIdRuleOverridesDefaultRule() {
        DeviceLifecycleRule productOverride = rule(99L, "PRODUCT_ID", "1", 1, null, "0.010000", "0.500000", 0, 100);
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                rule(1L, "DEFAULT", null, 1, null, "0.100000", "0.220000", 0, 1),
                productOverride));
        UserDevice device = device("NEXION_BOX", "S1", 1L, LocalDateTime.of(2026, 1, 26, 8, 0));

        DeviceLifecycleResponse response = service.evaluate(device);

        assertThat(response.getMonthsOwned()).isEqualTo(4);
        assertThat(response.getCurrentEfficiency()).isEqualByComparingTo("0.960596");
        assertThat(response.getFloorEfficiency()).isEqualByComparingTo("0.500000");
    }

    private List<DeviceLifecycleRule> defaultRules() {
        return List.of(
                rule(1L, "DEFAULT", null, 1, 3, "0.040000", "0.220000", 0, 10),
                rule(2L, "DEFAULT", null, 4, 8, "0.060000", "0.220000", 0, 20),
                rule(3L, "DEFAULT", null, 9, null, "0.100000", "0.220000", 0, 30),
                rule(4L, "PRODUCT_TYPE", "MOBILE", 0, null, "0.000000", "1.000000", 1, 100));
    }

    private DeviceLifecycleRule rule(
            Long id,
            String scopeType,
            String scopeValue,
            Integer startMonth,
            Integer endMonth,
            String decay,
            String floor,
            int exempt,
            int sortOrder) {
        DeviceLifecycleRule rule = new DeviceLifecycleRule();
        rule.setId(id);
        rule.setScopeType(scopeType);
        rule.setScopeValue(scopeValue);
        rule.setStartMonth(startMonth);
        rule.setEndMonth(endMonth);
        rule.setMonthlyDecayRate(new BigDecimal(decay));
        rule.setFloorEfficiency(new BigDecimal(floor));
        rule.setExempt(exempt);
        rule.setStatus(1);
        rule.setSortOrder(sortOrder);
        rule.setIsDeleted(0);
        return rule;
    }

    private UserDevice device(String productType, String tier, Long productId, LocalDateTime activatedAt) {
        UserDevice device = new UserDevice();
        device.setId(7L);
        device.setProductId(productId);
        device.setProductTier(tier);
        device.setDeviceType(productType);
        device.setDailyUsdt(new BigDecimal("38.520000"));
        device.setDailyNex(new BigDecimal("720.000000"));
        device.setActivatedAt(activatedAt);
        device.setIsDeleted(0);
        return device;
    }
}
