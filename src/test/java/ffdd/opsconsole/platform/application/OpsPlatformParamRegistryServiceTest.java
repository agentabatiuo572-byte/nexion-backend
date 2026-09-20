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
    /** A3 系统健康的实时来源:健康类参数键由它供值,不再读 nx_config_item 的存量快照。 */
    private final PlatformSystemHealthProvider health = mock(PlatformSystemHealthProvider.class);
    private final OpsPlatformParamRegistryService service =
            new OpsPlatformParamRegistryService(source, emergency, health);

    @Test
    void registryUsesAllActiveServerConfigsAndAuthoritativeEmergencyState() {
        when(source.findAllActive()).thenReturn(List.of(
                item("feature.ops.maintenanceBanner", "off", "admin_feature_flag"),
                item("wallet.exchange.spread_bps", "35", "wallet_exchange"),
                item("E.task.queueSaturation", "80", "E2"),
                item("content.risk_disclosure.version", "v3", "content")));
        when(emergency.currentKillSwitches()).thenReturn(List.of(
                Map.of("key", "exchange", "name", "兑换闸", "status", "disabled", "lastChange", "2026-07-18T10:00:00"),
                Map.of("key", "geo-block", "name", "地区屏蔽", "status", "空列表 · 无封锁", "lastChange", "2026-07-18T10:01:00")));

        PlatformParamRegistryOverview overview = service.overview().getData();

        assertThat(overview.rows()).hasSize(6);
        assertThat(overview.rows()).extracting(row -> row.domain()).contains("A", "E", "G", "I", "J");
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
        assertThat(overview.rows()).filteredOn(row -> row.canonicalKey().equals("E.task.queueSaturation"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.ownerCode()).isEqualTo("E2");
                    assertThat(row.ownerRoute()).isEqualTo("/devices/tasks");
                });
        assertThat(overview.stats().registeredCount()).isEqualTo(overview.rows().size());
        assertThat(overview.stats().domainCount()).isEqualTo(5);
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

    @Test
    void registryResolvesEveryCurrentCrossDomainConfigFamilyToItsRealOwner() {
        when(source.findAllActive()).thenReturn(List.of(
                item("E.task.queueSaturation", "80", "E2"),
                item("growth.wheel.pool_signature", "sig", "growth"),
                item("growth.withdraw_nex_gate.hold_days", "7", "growth"),
                item("K.rewards.referral.version", "3", "GROWTH_REFERRAL"),
                item("G.genesis.lottery.monthlyCapacity", "100000", "market"),
                item("payout.network_whitelist", "TRC20", "finance"),
                item("treasury.d3.forecast-config.version", "4", "treasury")));
        when(emergency.currentKillSwitches()).thenReturn(List.of());

        PlatformParamRegistryOverview overview = service.overview().getData();

        assertThat(overview.rows()).extracting(row -> row.ownerCode())
                .containsExactlyInAnyOrder("E2", "H3", "H1", "H8", "G4", "D5", "D3");
    }

    @Test
    void registryRoutesEveryG1StakingParameterToTheStakingOwner() {
        when(source.findAllActive()).thenReturn(List.of(
                item("G.staking.apy.usdt30d", "12", "market"),
                item("G.staking.any.future_parameter", "enabled", "market")));
        when(emergency.currentKillSwitches()).thenReturn(List.of());

        PlatformParamRegistryOverview overview = service.overview().getData();

        assertThat(overview.rows()).hasSize(2).allSatisfy(row -> {
            assertThat(row.domain()).isEqualTo("G");
            assertThat(row.ownerCode()).isEqualTo("G1");
            assertThat(row.ownerRoute()).isEqualTo("/finance-products/staking");
        });
    }

    @Test
    void registryRoutesLegacyDeviceYieldKeysToE6WithoutOpeningUnknownDeviceKeys() {
        when(source.findAllActive()).thenReturn(List.of(
                item("dailyUsdtPerBaseline", "0.06", "legacy_device_yield"),
                item("nexPerUsdt", "20", "legacy_device_yield")));
        when(emergency.currentKillSwitches()).thenReturn(List.of());

        PlatformParamRegistryOverview overview = service.overview().getData();

        assertThat(overview.rows()).hasSize(2).allSatisfy(row -> {
            assertThat(row.domain()).isEqualTo("E");
            assertThat(row.ownerCode()).isEqualTo("E6");
            assertThat(row.ownerRoute()).isEqualTo("/devices/compute-config");
        });
    }

    @Test
    void registryRoutesCurrentD7K1AndH9ConfigFamiliesToTheirVisibleOwners() {
        when(source.findAllActive()).thenReturn(List.of(
                item("finance.payout_vnd.version", "1", "finance"),
                item("risk.k1.release.version", "1", "risk"),
                item("growth.public_stats.version", "1", "growth")));
        when(emergency.currentKillSwitches()).thenReturn(List.of());

        PlatformParamRegistryOverview overview = service.overview().getData();

        assertThat(overview.rows()).extracting(row -> row.ownerCode())
                .containsExactlyInAnyOrder("D7", "K1", "H9");
        assertThat(overview.rows()).extracting(row -> row.ownerRoute())
                .containsExactlyInAnyOrder("/finance/payout-vnd", "/risk/multi-account", "/growth/public-stats");
        assertThat(overview.rows()).filteredOn(row -> row.ownerCode().equals("K1"))
                .singleElement()
                .satisfies(row -> assertThat(row.domainLabel()).isEqualTo("风控与反作弊"));
    }

    @Test
    void registryRoutesCommissionCoolingDaysToF2AsItsCurrentAuthoritativeWriteOwner() {
        when(source.findAllActive()).thenReturn(List.of(
                item("commission/cooling-days", "30", "team")));
        when(emergency.currentKillSwitches()).thenReturn(List.of());

        PlatformParamRegistryOverview overview = service.overview().getData();

        assertThat(overview.rows()).singleElement().satisfies(row -> {
            assertThat(row.domain()).isEqualTo("F");
            assertThat(row.ownerCode()).isEqualTo("F2");
            assertThat(row.ownerRoute()).isEqualTo("/network/royalty");
        });
    }

    @Test
    void registryIsolatesUnknownFamiliesWithoutReturningTheirValuesOrInventingOwners() {
        when(source.findAllActive()).thenReturn(List.of(
                item("feature.ops.maintenanceBanner", "off", "admin_feature_flag"),
                item("openapi.developer.default_qps_limit", "20", "openapi"),
                item("unknown/family", "private-configuration-payload", "unknown")));
        when(emergency.currentKillSwitches()).thenReturn(List.of());

        PlatformParamRegistryOverview overview = service.overview().getData();

        assertThat(overview.rows()).singleElement().satisfies(row -> {
            assertThat(row.canonicalKey()).isEqualTo("feature.ops.maintenanceBanner");
            assertThat(row.ownerCode()).isEqualTo("A3");
            assertThat(row.currentValue()).isEqualTo("off");
        });
        assertThat(overview.sources().get(0).status()).isEqualTo("PARTIAL");
        assertThat(overview.sources().get(0).detail()).contains("2 项", "未展示");
        assertThat(overview.sources().get(0).rowCount()).isEqualTo(1);
        assertThat(overview.stats().registeredCount()).isEqualTo(1);
        assertThat(overview.toString()).doesNotContain("private-configuration-payload");
    }

    @Test
    void registryRoutesBundleDiscountsToTheirAuthoritativeE1Owner() {
        when(source.findAllActive()).thenReturn(List.of(
                item("store.bundle.discount.2.rate", "0.05", "store"),
                item("store.bundle.discount.3.rate", "0.08", "store"),
                item("store.bundle.discount.4plus.rate", "0.12", "store"),
                item("store.bundle.discount.version", "1", "store")));
        when(emergency.currentKillSwitches()).thenReturn(List.of());

        PlatformParamRegistryOverview overview = service.overview().getData();

        assertThat(overview.rows()).hasSize(4).allSatisfy(row -> {
            assertThat(row.ownerCode()).isEqualTo("E1");
            assertThat(row.ownerRoute()).isEqualTo("/devices/pricing");
        });
        assertThat(overview.sources().get(0).status()).isEqualTo("READY");
    }

    @Test
    void unmappedSecretsDoNotBlockEmergencyStateOrBecomePartOfItsCounts() {
        when(source.findAllActive()).thenReturn(List.of(
                item("future.provider.credentials", "private-provider-value", "future")));
        when(emergency.currentKillSwitches()).thenReturn(List.of(
                Map.of("key", "exchange", "name", "兑换闸", "status", "disabled")));

        PlatformParamRegistryOverview overview = service.overview().getData();

        assertThat(overview.rows()).singleElement()
                .satisfies(row -> assertThat(row.canonicalKey()).isEqualTo("emergency.gate.exchange"));
        assertThat(overview.sources().get(0).status()).isEqualTo("PARTIAL");
        assertThat(overview.sources().get(0).rowCount()).isZero();
        assertThat(overview.sources().get(1).rowCount()).isEqualTo(1);
        assertThat(overview.stats().registeredCount()).isEqualTo(1);
        assertThat(overview.toString()).doesNotContain("private-provider-value", "future.provider.credentials");
    }

    @Test
    void registryDoesNotDowngradeInvalidKeysOrDatabaseFailuresToPartialSuccess() {
        when(source.findAllActive()).thenReturn(List.of(item(" ", "value", "unknown")));
        assertThatThrownBy(service::overview).hasMessage("A5_CONFIG_KEY_REQUIRED");

        when(source.findAllActive()).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(service::overview).hasMessage("database unavailable");
    }

    @Test
    void registryExcludesRunScopedPublicStatsFixturesFromOperationalParameters() {
        when(source.findAllActive()).thenReturn(List.of(
                item("growth.public_stats.version", "1", "growth"),
                item("h9.sb.seven-closures-20260817.v", "2", "growth_sandbox"),
                item("h9.sb.seven-closures-20260817.data", "private-fixture-payload", "growth_sandbox")));
        when(emergency.currentKillSwitches()).thenReturn(List.of());

        PlatformParamRegistryOverview overview = service.overview().getData();

        assertThat(overview.rows()).singleElement().satisfies(row -> {
            assertThat(row.canonicalKey()).isEqualTo("growth.public_stats.version");
            assertThat(row.ownerCode()).isEqualTo("H9");
        });
        assertThat(overview.stats().registeredCount()).isEqualTo(1);
        assertThat(overview.sources().get(0).rowCount()).isEqualTo(1);
    }

    @Test
    void registryDoesNotHideUnknownParametersUsingOnlyASandboxGroupOrKeyPrefix() {
        for (PlatformConfigItem unknown : List.of(
                item("unknown/family", "1", "growth_sandbox"),
                item("h9.sb.example.data", "1", "unknown"))) {
            when(source.findAllActive()).thenReturn(List.of(unknown));
            when(emergency.currentKillSwitches()).thenReturn(List.of());
            PlatformParamRegistryOverview overview = service.overview().getData();
            assertThat(overview.rows()).isEmpty();
            assertThat(overview.sources().get(0).status()).isEqualTo("PARTIAL");
            assertThat(overview.sources().get(0).detail()).contains("1 项", "未展示");
        }
    }

    /**
     * 健康类参数必须来自 A3 的实时采样,不得把 nx_config_item 的存量快照当当前值。
     *
     * 缺陷原形:A3 显示「事件待投递 严重异常 8 条 · 最久 41481 秒」,A5 同屏显示
     * 2026-06-24 的「正常 · 延迟 1.2s」并标成「当前服务端值」。
     */
    @Test
    void healthParametersReportTheLiveSamplingInsteadOfTheStoredSnapshot() {
        when(source.findAllActive()).thenReturn(List.of(
                item("admin.health.event_pipeline", "正常 · 延迟 1.2s", "admin_system_health"),
                item("admin.health.ledger_write", "正常 · p99 84ms", "admin_system_health")));
        when(emergency.currentKillSwitches()).thenReturn(List.of());
        when(health.currentHealth()).thenReturn(List.of(
                Map.of("name", "事件待投递", "tone", "bad", "metric", "8 条 · 最久 41481 秒",
                        "source", "nx_event_outbox", "observedAt", "2026-09-20T04:41:07", "stale", false),
                Map.of("name", "资金账本可读性", "tone", "ok", "metric", "24h 120 笔 · 查询 12ms",
                        "source", "nx_wallet_ledger", "observedAt", "2026-09-20T04:41:07", "stale", false)));

        PlatformParamRegistryOverview overview = service.overview().getData();

        assertThat(overview.rows()).hasSize(2);
        assertThat(overview.rows()).allSatisfy(row -> {
            assertThat(row.live()).isTrue();
            assertThat(row.stale()).isFalse();
            assertThat(row.observedAt()).isEqualTo("2026-09-20T04:41:07");
        });
        assertThat(overview.rows())
                .filteredOn(row -> row.canonicalKey().equals("admin.health.event_pipeline"))
                .singleElement()
                .satisfies(row -> {
                    // 存量的「正常 · 延迟 1.2s」绝不能再出现。
                    assertThat(row.currentValue()).isEqualTo("8 条 · 最久 41481 秒");
                    assertThat(row.currentValue()).doesNotContain("1.2s");
                });
    }

    /** 实时采样读不到时必须标过期,不得把旧快照冒充当前权威值。 */
    @Test
    void healthParametersMarkTheRowStaleWhenTheLiveProbeIsUnavailable() {
        when(source.findAllActive()).thenReturn(List.of(
                item("admin.health.event_pipeline", "正常 · 延迟 1.2s", "admin_system_health")));
        when(emergency.currentKillSwitches()).thenReturn(List.of());
        when(health.currentHealth()).thenThrow(new IllegalStateException("probe down"));

        PlatformParamRegistryOverview overview = service.overview().getData();

        assertThat(overview.rows()).singleElement().satisfies(row -> {
            assertThat(row.stale()).isTrue();
            assertThat(row.sourceStatus()).isEqualTo("PARTIAL");
            assertThat(row.currentValue()).contains("读取失败");
            assertThat(row.currentValue()).doesNotContain("1.2s");
        });
    }

    private PlatformConfigItem item(String key, String value, String group) {
        LocalDateTime now = LocalDateTime.of(2026, 7, 18, 10, 0);
        return new PlatformConfigItem(1L, key, value, "STRING", group, "ADMIN", "test", 1, now, now);
    }
}
