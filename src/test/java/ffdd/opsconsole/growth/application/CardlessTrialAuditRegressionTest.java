package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CardlessTrialAuditRegressionTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void legacyChargeCannotPurchaseWithoutUserConfirmation() {
        var mapper = mock(AppTrialLifecycleMapper.class);
        when(mapper.lockActiveUser(7L)).thenReturn(7L);
        when(mapper.emergencyValue("killswitch.trial")).thenReturn("enabled");
        var idempotency = mock(ffdd.opsconsole.shared.idempotency.AdminIdempotencyService.class);
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(call -> ((java.util.function.Supplier) call.getArgument(4)).get());
        var service = new AppTrialLifecycleService(mapper, null, idempotency, null, null, null, null, null,
                new org.springframework.mock.env.MockEnvironment().withProperty("spring.profiles.active", "prod"),
                java.time.Clock.systemUTC());
        ApiResult<?> result = service.charge(7L, "legacy-charge");
        assertThat(result.getMessage()).isEqualTo("TRIAL_EXPLICIT_PURCHASE_REQUIRED");
        verify(mapper, never()).settleWallet(anyLong(), any(), any(), any());
        verify(mapper, never()).lockTrial(anyLong());
    }

    @Test
    void convertedDeviceUsesCatalogueEarningsNotTrialShadowRates() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ffdd/opsconsole/growth/mapper/AppTrialLifecycleMapper.java"));
        String insert = source.substring(source.indexOf("INSERT INTO nx_user_device"), source.indexOf("int insertPurchasedDevice"));
        assertThat(insert).contains("p.estimated_daily_usdt", "p.daily_nex")
                .doesNotContain("#{dailyUsdt}", "#{dailyNex}");
    }

    @Test
    void regularOrdersKeepAuthoritativePowerInsteadOfZero() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ffdd/opsconsole/commerce/mapper/AppOrderCommandMapper.java"));
        String insert = source.substring(source.indexOf("INSERT IGNORE INTO nx_user_device"), source.indexOf("int insertWalletDevice"));
        assertThat(insert).contains("s.power_text")
                .doesNotContain("GREATEST(COALESCE(p.vram_total_gb,0),0),0,");
    }
}
