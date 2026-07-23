package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformParamRegistrySource;
import ffdd.opsconsole.platform.dto.PlatformParamRegistryOverview;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsPlatformParamRegistryServiceTest {
    private final PlatformParamRegistrySource source = mock(PlatformParamRegistrySource.class);
    private final PlatformEmergencyStateProvider emergency = mock(PlatformEmergencyStateProvider.class);
    private final OpsPlatformParamRegistryService service = new OpsPlatformParamRegistryService(source, emergency);

    @Test
    void registryUsesAllActiveServerConfigsAndAuthoritativeEmergencyState() {
        when(source.findAllActive()).thenReturn(List.of(
                item("feature.ops.maintenanceBanner", "off", "admin_feature_flag"),
                item("wallet.exchange.spread_bps", "35", "wallet_exchange"),
                item("content.risk_disclosure.version", "v3", "content")));
        when(emergency.currentKillSwitches()).thenReturn(List.of(
                Map.of("key", "exchange", "name", "兑换闸", "status", "disabled", "lastChange", "2026-07-18T10:00:00"),
                Map.of("key", "geo-block", "name", "地区屏蔽", "status", "空列表 · 无封锁", "lastChange", "2026-07-18T10:01:00")));

        PlatformParamRegistryOverview overview = service.overview().getData();

        assertThat(overview.rows()).hasSize(5);
        assertThat(overview.rows()).extracting(row -> row.domain()).contains("A", "G", "I", "J");
        assertThat(overview.rows()).filteredOn(row -> row.canonicalKey().equals("feature.ops.maintenanceBanner"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.currentValue()).isEqualTo("off");
                    assertThat(row.ownerCode()).isEqualTo("A3");
                    assertThat(row.ownerRoute()).isEqualTo("/platform/config");
                });
        assertThat(overview.rows()).filteredOn(row -> row.canonicalKey().equals("emergency.geo-block"))
                .singleElement()
                .satisfies(row -> assertThat(row.ownerRoute()).isEqualTo("/emergency/geo-block"));
        assertThat(overview.stats().registeredCount()).isEqualTo(overview.rows().size());
        assertThat(overview.stats().domainCount()).isEqualTo(4);
    }

    @Test
    void registryRejectsDuplicateServerKeysInsteadOfFailingOpen() {
        when(source.findAllActive()).thenReturn(List.of(
                item("wallet.exchange.spread_bps", "35", "wallet_exchange"),
                item("wallet.exchange.spread_bps", "36", "wallet_exchange")));
        when(emergency.currentKillSwitches()).thenReturn(List.of());

        assertThatThrownBy(service::overview)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("A5_DUPLICATE_CANONICAL_KEY");
    }

    @Test
    void emergencyReadFailuresAreVisibleAsPartialSourceHealth() {
        when(source.findAllActive()).thenReturn(List.of(item("feature.ops.maintenanceBanner", "off", "admin_feature_flag")));
        when(emergency.currentKillSwitches()).thenReturn(List.of(
                Map.of("key", "j1-unavailable", "name", "J1 功能闸读取失败", "status", "读取失败", "lastChange", "2026-07-18T10:00:00")));

        PlatformParamRegistryOverview overview = service.overview().getData();

        assertThat(overview.rows()).hasSize(1);
        assertThat(overview.sources()).filteredOn(source -> source.key().equals("emergency"))
                .singleElement()
                .satisfies(source -> assertThat(source.status()).isEqualTo("PARTIAL"));
    }

    @Test
    void registryNeverReturnsSecretConfigurationValues() {
        when(source.findAllActive()).thenReturn(List.of(item("auth.provider.api_secret", "plain-secret", "auth")));
        when(emergency.currentKillSwitches()).thenReturn(List.of());

        PlatformParamRegistryOverview overview = service.overview().getData();

        assertThat(overview.rows()).singleElement().satisfies(row -> {
            assertThat(row.currentValue()).isEqualTo("已配置（敏感值已隐藏）");
            assertThat(row.currentValue()).doesNotContain("plain-secret");
            assertThat(row.valueType()).isEqualTo("SECRET");
        });
    }

    private PlatformConfigItem item(String key, String value, String group) {
        LocalDateTime now = LocalDateTime.of(2026, 7, 18, 10, 0);
        return new PlatformConfigItem(1L, key, value, "STRING", group, "ADMIN", "test", 1, now, now);
    }
}
