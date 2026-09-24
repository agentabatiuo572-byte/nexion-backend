package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.market.application.AppStakingService;
import ffdd.opsconsole.market.application.AppExchangeService;
import ffdd.opsconsole.market.application.GenesisCatalogService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * zentao #195:连签增益指向的业务已停售时,页面仍承诺「30 天 质押活动入口」并显示解锁倒计时。
 *
 * <p>根因不在判据写错,而在**读数取错文档**:{@code stakingAvailable()} 原先读
 * {@code OpsNexMarketService.overview()} —— 那是 G3 NEX **价格市场**总览,响应里没有
 * {@code stats}/{@code pools} 两个键,于是两个 {@code instanceof} 守卫都不成立、
 * 各自走「当作开着」的默认分支,方法**恒返回 true**。任务面读公开目录如实标「业务暂停」,
 * 连签增益读这个恒真值继续承诺 —— 同一事实两端各说一套。</p>
 *
 * <p>本门钉住:质押可用性必须与 App 读的**同一份**目录同源,并遵守
 * {@code canOpenStakingPool} 的判据({@code enabled && !killed && status==ACTIVE}),
 * 同时保留「读失败才算不知道」的 fail-open 语义。</p>
 */
class StreakPerkBusinessAvailabilityAdapterTest {

    private final AppStakingService staking = mock(AppStakingService.class);
    private final GenesisCatalogService genesis = mock(GenesisCatalogService.class);
    private final AppExchangeService exchange = mock(AppExchangeService.class);
    private final StreakPerkBusinessAvailabilityAdapter adapter =
            new StreakPerkBusinessAvailabilityAdapter(staking, genesis, exchange);

    /** 造一档 {@code GET /api/config/staking/pools} 形状的目录行。 */
    private static Map<String, Object> pool(String tier, boolean enabled, boolean killed, String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tierKey", tier);
        row.put("enabled", enabled);
        row.put("killed", killed);
        row.put("status", status);
        return row;
    }

    private void catalog(List<Map<String, Object>> pools) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pools", pools);
        when(staking.pools()).thenReturn(ApiResult.ok(data));
    }

    @Test
    void readsTheSamePublicCatalogueTheAppStakingSurfaceReads() {
        catalog(List.of(pool("usdt30d", true, false, "ACTIVE")));

        assertThat(adapter.stakingAvailable()).isTrue();
        // 断言读数来源本身:换回 G3 价格市场总览就等于换回了那个恒真值。
        verify(staking).pools();
    }

    @Test
    void allPoolsStoppedMeansThePerkBusinessIsNotAvailable() {
        // 公网 TEST 的真实形状:四档全部 enabled=false / STOPPED。
        catalog(List.of(
                pool("usdt30d", false, false, "STOPPED"),
                pool("usdt90d", false, false, "STOPPED"),
                pool("usdt180d", false, false, "STOPPED"),
                pool("usdt365d", false, false, "STOPPED")));

        assertThat(adapter.stakingAvailable())
                .as("没有任何一档在售时,连签增益不能再承诺质押入口")
                .isFalse();
    }

    @Test
    void oneSellablePoolIsEnough() {
        catalog(List.of(
                pool("usdt30d", false, false, "STOPPED"),
                pool("usdt90d", true, false, "ACTIVE")));

        assertThat(adapter.stakingAvailable()).isTrue();
    }

    @Test
    void killedOrStoppedPoolsDoNotCountAsSellable() {
        // enabled=true 但被 kill 开关停掉 —— canOpenStakingPool 同样判不可开。
        catalog(List.of(pool("usdt30d", true, true, "KILLED")));
        assertThat(adapter.stakingAvailable()).isFalse();

        // enabled=true 但状态不是 ACTIVE。
        catalog(List.of(pool("usdt30d", true, false, "STOPPED")));
        assertThat(adapter.stakingAvailable()).isFalse();
    }

    @Test
    void anEmptyCatalogueIsKnowledgeNotIgnorance() {
        // 成功读到「一档都没有」是知识,不是「不知道」:此时没有可买的东西。
        catalog(new ArrayList<>());

        assertThat(adapter.stakingAvailable()).isFalse();
    }

    @Test
    void aReadThatThrowsFailsOpenSoEarnedPerksAreNotRevoked() {
        // 读失败是「不知道」,不能说成业务关闭 —— 那会让用户以为已获得的权益被下掉了。
        doThrow(new IllegalStateException("db down")).when(staking).pools();

        assertThat(adapter.stakingAvailable()).isNull();
    }

    @Test
    void aNullResponseFailsOpen() {
        doReturn(null).when(staking).pools();

        assertThat(adapter.stakingAvailable()).isNull();
    }

    @Test
    void aNonZeroCodeFailsOpen() {
        doReturn(ApiResult.fail(503, "STAKING_CATALOG_UNAVAILABLE")).when(staking).pools();

        assertThat(adapter.stakingAvailable()).isNull();
    }

    @Test
    void aCatalogueWithoutAPoolsListFailsOpen() {
        // 响应体里没有 pools 键 = 这份文档不回答这个问题,属于「不知道」。
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serverCanonical", true);
        doReturn(ApiResult.ok(data)).when(staking).pools();

        assertThat(adapter.stakingAvailable()).isNull();
    }

    @Test
    void genesisRequiresPublishedOpenStateAndDoesNotGuessOnReadFailure() {
        when(genesis.publicState()).thenReturn(Map.of("catalogAvailable", false, "tradeAvailable", true));
        assertThat(adapter.genesisPrimaryAvailable()).isFalse();
        when(genesis.publicState()).thenReturn(Map.of("catalogAvailable", true, "tradeAvailable", true));
        assertThat(adapter.genesisPrimaryAvailable()).isTrue();
        doThrow(new IllegalStateException("unavailable")).when(genesis).publicState();
        assertThat(adapter.genesisPrimaryAvailable()).isNull();
    }

    @Test
    void missionPublicationFailsClosedWhenTheBusinessCatalogIsUnreadable() {
        doThrow(new IllegalStateException("db down")).when(staking).pools();
        doThrow(new IllegalStateException("db down")).when(genesis).publicState();

        assertThat(adapter.stakingAvailableForMissionPublication()).isFalse();
        assertThat(adapter.genesisAvailableForMissionPublication()).isFalse();
    }

    @Test
    void exchangeMissionUsesThePublicCapsAndFailsClosedOnUnreadableOrSandboxState() {
        when(exchange.caps()).thenReturn(ApiResult.ok(Map.of(
                "swapEnabled", false, "sourceEnvironment", "PRODUCTION")));
        assertThat(adapter.exchangeAvailableForMissionPublication()).isFalse();
        when(exchange.caps()).thenReturn(ApiResult.ok(Map.of(
                "swapEnabled", true, "sourceEnvironment", "PRODUCTION")));
        assertThat(adapter.exchangeAvailableForMissionPublication()).isTrue();
        when(exchange.caps()).thenReturn(ApiResult.ok(Map.of(
                "swapEnabled", true, "sourceEnvironment", "SANDBOX")));
        assertThat(adapter.exchangeAvailableForMissionPublication()).isFalse();
        doThrow(new IllegalStateException("unavailable")).when(exchange).caps();
        assertThat(adapter.exchangeAvailableForMissionPublication()).isFalse();
        verify(exchange, org.mockito.Mockito.times(4)).caps();
    }
}
