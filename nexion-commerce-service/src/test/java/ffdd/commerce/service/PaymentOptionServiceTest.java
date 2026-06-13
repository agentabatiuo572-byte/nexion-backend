package ffdd.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.commerce.client.SystemConfigClient;
import ffdd.commerce.dto.PaymentOptionResponse;
import ffdd.common.api.ApiResult;
import java.util.List;
import java.util.Map;
import static java.util.Map.entry;
import org.junit.jupiter.api.Test;

class PaymentOptionServiceTest {
    private final SystemConfigClient systemConfigClient = mock(SystemConfigClient.class);
    private final PaymentProvider provider = mock(PaymentProvider.class);

    @Test
    void listsPaymentOptionsFromPublicCommerceConfig() {
        when(provider.code()).thenReturn("MOCK");
        when(systemConfigClient.commerce()).thenReturn(ApiResult.ok(Map.ofEntries(
                entry("payment.default_provider", "MOCK"),
                entry("payment.checkout.enabled", true),
                entry("payment.checkout.label", "USDT checkout"),
                entry("payment.checkout.currency", "USDT"),
                entry("payment.checkout.network", "TRC20 / ERC20"),
                entry("payment.checkout.min_usdt", "1"),
                entry("payment.checkout.max_usdt", "10000"),
                entry("payment.checkout.fee_mode", "FIXED"),
                entry("payment.checkout.fee_label", "TRC20 gas"),
                entry("payment.checkout.fee_amount_usdt", "1.5"),
                entry("payment.checkout.fee_rate_pct", "0.2"))));

        PaymentOptionService service = new PaymentOptionService(List.of(provider), systemConfigClient);

        List<PaymentOptionResponse> options = service.listOptions();

        assertThat(options).hasSize(1);
        PaymentOptionResponse option = options.get(0);
        assertThat(option.getProvider()).isEqualTo("MOCK");
        assertThat(option.getLabel()).isEqualTo("USDT checkout");
        assertThat(option.getCurrency()).isEqualTo("USDT");
        assertThat(option.getNetwork()).isEqualTo("TRC20 / ERC20");
        assertThat(option.getEnabled()).isTrue();
        assertThat(option.getDefaultOption()).isTrue();
        assertThat(option.getMinUsdt()).isEqualByComparingTo("1");
        assertThat(option.getMaxUsdt()).isEqualByComparingTo("10000");
        assertThat(option.getFeeMode()).isEqualTo("FIXED");
        assertThat(option.getFeeLabel()).isEqualTo("TRC20 gas");
        assertThat(option.getFeeAmountUsdt()).isEqualByComparingTo("1.5");
        assertThat(option.getFeeRatePct()).isEqualByComparingTo("0.2");
    }

    @Test
    void fallsBackToMockWhenConfigServiceIsUnavailable() {
        when(systemConfigClient.commerce()).thenThrow(new IllegalStateException("down"));

        PaymentOptionService service = new PaymentOptionService(List.of(), systemConfigClient);

        List<PaymentOptionResponse> options = service.listOptions();

        assertThat(options).hasSize(1);
        assertThat(options.get(0).getProvider()).isEqualTo("MOCK");
        assertThat(options.get(0).getEnabled()).isTrue();
        assertThat(options.get(0).getDefaultOption()).isTrue();
    }

    @Test
    void keepsLegacyMockPaymentConfigFallbackForExistingDatabases() {
        when(provider.code()).thenReturn("MOCK");
        when(systemConfigClient.commerce()).thenReturn(ApiResult.ok(Map.ofEntries(
                entry("payment.default_provider", "MOCK"),
                entry("payment.mock.label", "Legacy checkout"),
                entry("payment.mock.fee_mode", "RATE"),
                entry("payment.mock.fee_rate_pct", "0.3"))));

        PaymentOptionService service = new PaymentOptionService(List.of(provider), systemConfigClient);

        PaymentOptionResponse option = service.listOptions().get(0);

        assertThat(option.getLabel()).isEqualTo("Legacy checkout");
        assertThat(option.getFeeMode()).isEqualTo("RATE");
        assertThat(option.getFeeRatePct()).isEqualByComparingTo("0.3");
    }
}
