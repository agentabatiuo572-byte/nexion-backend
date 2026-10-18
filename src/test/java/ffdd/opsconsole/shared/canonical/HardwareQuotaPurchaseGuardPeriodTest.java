package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.shared.canonical.mapper.HardwareQuotaPurchaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HardwareQuotaPurchaseGuardPeriodTest {
    @Test
    void existingOctoberUsageAndOneNewUsageExhaustTheSameTierThenNovemberStarts() {
        var mapper = mock(HardwareQuotaPurchaseMapper.class);
        var tier = new HardwareQuotaPurchaseMapper.Tier(1L, "PRO", "SKU", 0, BigDecimal.ZERO, 2, "ALL", 1);
        when(mapper.lockHardwarePurchaseTiers("SKU")).thenReturn(List.of(tier));
        var records = new ArrayList<LocalDateTime>(List.of(LocalDateTime.parse("2026-10-01T00:00:00")));
        when(mapper.lockHardwarePurchaseUsage(eq(1L), any(), any())).thenAnswer(call -> {
            LocalDateTime from = call.getArgument(1), until = call.getArgument(2);
            return records.stream().filter(at -> !at.isBefore(from) && at.isBefore(until)).map(at -> 1).toList();
        });
        when(mapper.recordHardwarePurchase(eq(tier), eq(7L), anyString(), any())).thenAnswer(call -> {
            records.add(call.getArgument(3));
            return 1;
        });
        var now = LocalDateTime.parse("2026-10-18T17:00:00");
        var reservation = HardwareQuotaPurchaseGuard.reserve(mapper, 7L, "SKU", now);
        HardwareQuotaPurchaseGuard.record(mapper, reservation, 7L, "NEW-OCTOBER");
        assertThat(records).containsExactly(LocalDateTime.parse("2026-10-01T00:00:00"), now);
        assertThatThrownBy(() -> HardwareQuotaPurchaseGuard.reserve(mapper, 7L, "SKU", LocalDateTime.parse("2026-10-31T16:59:59")))
                .hasMessage("ORDER_MONTHLY_QUOTA_EXHAUSTED");
        var firstNovember = LocalDateTime.parse("2026-10-31T17:00:00");
        var november = HardwareQuotaPurchaseGuard.reserve(mapper, 7L, "SKU", firstNovember);
        HardwareQuotaPurchaseGuard.record(mapper, november, 7L, "NEW-NOVEMBER");
        assertThat(records).hasSize(3).contains(firstNovember);
        verify(mapper, times(2)).recordHardwarePurchase(any(), anyLong(), anyString(), any());
        verify(mapper).lockHardwarePurchaseUsage(1L, firstNovember, LocalDateTime.parse("2026-11-30T17:00:00"));
    }

    @Test
    void volumeFactsAndUsageShareOneEventPeriodButKeepTheirStorageZones() {
        var mapper = mock(HardwareQuotaPurchaseMapper.class);
        var tier = new HardwareQuotaPurchaseMapper.Tier(1L, "PRO", "SKU", 0, BigDecimal.TEN, 2, "ALL", 1);
        when(mapper.lockHardwarePurchaseTiers("SKU")).thenReturn(List.of(tier));
        when(mapper.hardwarePurchaseFacts(eq(7L), any(), any())).thenReturn(new HardwareQuotaPurchaseMapper.Facts(0L, BigDecimal.TEN));
        when(mapper.lockHardwarePurchaseUsage(eq(1L), any(), any())).thenReturn(List.of());
        HardwareQuotaPurchaseGuard.reserve(mapper, 7L, "SKU", LocalDateTime.parse("2026-10-31T17:00:00"));
        verify(mapper).hardwarePurchaseFacts(7L, LocalDateTime.parse("2026-11-01T01:00:00"), LocalDateTime.parse("2026-12-01T01:00:00"));
        verify(mapper).lockHardwarePurchaseUsage(1L, LocalDateTime.parse("2026-10-31T17:00:00"), LocalDateTime.parse("2026-11-30T17:00:00"));
        verify(mapper, never()).recordHardwarePurchase(any(), anyLong(), anyString(), any());
    }
}
