package ffdd.commerce.service;

import ffdd.commerce.client.SystemConfigClient;
import ffdd.commerce.dto.PaymentOptionResponse;
import ffdd.common.api.ApiResult;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PaymentOptionService {
    private static final String DEFAULT_PROVIDER = "MOCK";

    private final List<PaymentProvider> providers;
    private final SystemConfigClient systemConfigClient;

    public PaymentOptionService(List<PaymentProvider> providers, SystemConfigClient systemConfigClient) {
        this.providers = providers == null ? List.of() : providers;
        this.systemConfigClient = systemConfigClient;
    }

    public List<PaymentOptionResponse> listOptions() {
        Map<String, Object> config = commerceConfig();
        String defaultProvider = text(config.get("payment.default_provider"), DEFAULT_PROVIDER).toUpperCase(Locale.ROOT);
        List<PaymentOptionResponse> options = providers.stream()
                .map(provider -> option(provider, config, defaultProvider))
                .sorted(Comparator.comparing(PaymentOptionResponse::getDefaultOption).reversed()
                        .thenComparing(PaymentOptionResponse::getProvider))
                .toList();
        if (!options.isEmpty()) {
            return options;
        }
        return List.of(fallbackOption(defaultProvider));
    }

    private PaymentOptionResponse option(PaymentProvider provider, Map<String, Object> config, String defaultProvider) {
        String code = provider.code().toUpperCase(Locale.ROOT);
        String providerPrefix = "payment." + code.toLowerCase(Locale.ROOT) + ".";
        String primaryPrefix = "MOCK".equals(code) ? "payment.checkout." : providerPrefix;
        String legacyPrefix = "MOCK".equals(code) ? providerPrefix : null;
        boolean enabled = bool(configValue(config, primaryPrefix, legacyPrefix, "enabled"), true);
        return new PaymentOptionResponse(
                code,
                text(configValue(config, primaryPrefix, legacyPrefix, "label"), code + " checkout"),
                text(configValue(config, primaryPrefix, legacyPrefix, "currency"), "USDT"),
                text(configValue(config, primaryPrefix, legacyPrefix, "network"), "USDT"),
                enabled,
                code.equals(defaultProvider),
                decimal(configValue(config, primaryPrefix, legacyPrefix, "min_usdt")),
                decimal(configValue(config, primaryPrefix, legacyPrefix, "max_usdt")),
                text(configValue(config, primaryPrefix, legacyPrefix, "fee_mode"), "INCLUDED").toUpperCase(Locale.ROOT),
                text(configValue(config, primaryPrefix, legacyPrefix, "fee_label"), "Included"),
                decimal(configValue(config, primaryPrefix, legacyPrefix, "fee_amount_usdt")),
                decimal(configValue(config, primaryPrefix, legacyPrefix, "fee_rate_pct")));
    }

    private PaymentOptionResponse fallbackOption(String defaultProvider) {
        return new PaymentOptionResponse(defaultProvider, defaultProvider + " checkout", "USDT", "USDT", true, true, null, null, "INCLUDED", "Included", null, null);
    }

    private Map<String, Object> commerceConfig() {
        try {
            ApiResult<Map<String, Object>> response = systemConfigClient.commerce();
            if (response != null && response.getCode() == 0 && response.getData() != null) {
                return response.getData();
            }
        } catch (RuntimeException ignored) {
            // Checkout remains available with safe local defaults if system config is temporarily unavailable.
        }
        return Map.of();
    }

    private Object configValue(Map<String, Object> config, String primaryPrefix, String legacyPrefix, String suffix) {
        Object value = config.get(primaryPrefix + suffix);
        if (value != null || legacyPrefix == null) {
            return value;
        }
        return config.get(legacyPrefix + suffix);
    }

    private String text(Object value, String fallback) {
        String raw = value == null ? "" : String.valueOf(value).trim();
        return StringUtils.hasText(raw) ? raw : fallback;
    }

    private boolean bool(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean bool) return bool;
        String raw = String.valueOf(value).trim();
        if (!StringUtils.hasText(raw)) return fallback;
        return "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw);
    }

    private BigDecimal decimal(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) return null;
        return new BigDecimal(String.valueOf(value).trim());
    }
}
