package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F2 UnilevelCommissionService 单元测试。
 * 覆盖:L1-L7 上级链×费率→commission_event network(USDT+NEX)+D4、幂等防重、空上级/空费率/非法入参退化、
 * depthGate(L4+ 上级需 ≥ V2)、NEX 派发(nex_per_usd × USDT 佣金)。
 */
@ExtendWith(MockitoExtension.class)
class UnilevelCommissionServiceTest {

    @Mock private TeamCommissionMapper teamCommissionMapper;
    @Mock private TeamCommissionRepository commissionRepository;
    @Mock private TreasuryLedgerPostingFacade ledgerPostingFacade;
    @Mock private PlatformConfigFacade configFacade;

    @InjectMocks private UnilevelCommissionService service;

    private Map<String, Object> upline(long userId, int layer) {
        return upline(userId, layer, null);
    }

    private Map<String, Object> upline(long userId, int layer, String vRank) {
        Map<String, Object> m = new HashMap<>();
        m.put("userId", userId);
        m.put("layer", layer);
        m.put("vRank", vRank);
        return m;
    }

    private Map<String, Object> rate(String level, double usdtPct) {
        Map<String, Object> m = new HashMap<>();
        m.put("level", level);
        m.put("usdtPct", BigDecimal.valueOf(usdtPct));
        return m;
    }

    private Map<String, Object> rate(String level, double usdtPct, double nexPerUsd) {
        Map<String, Object> m = rate(level, usdtPct);
        m.put("nexReward", BigDecimal.valueOf(nexPerUsd));
        return m;
    }

    @Test
    void settle_twoLayers_eachAncestorGetsUsdtCommissionEventAndLedger() {
        // buyer 990686 → L1=990685(10%) + L2=990684(5%);订单 subtotal=$1000,无 NEX 配置
        when(teamCommissionMapper.listUplineChain(990686L, 7))
                .thenReturn(List.of(upline(990685L, 1), upline(990684L, 2)));
        when(teamCommissionMapper.unilevelRates())
                .thenReturn(List.of(rate("L1", 10), rate("L2", 5)));
        // L2 上级月业绩=100 → InfluenceScore=1+log10(1)=1.0 → L2 finalUsdt 不变(保持 50 断言)
        when(teamCommissionMapper.monthlyNetworkVolume(990684L)).thenReturn(new BigDecimal("100"));
        when(commissionRepository.countNetworkCommissionByOrder(anyLong(), anyString())).thenReturn(0);
        when(commissionRepository.insertNetworkCommissionEvent(anyLong(), eq("network"), anyLong(),
                any(), anyString(), any(BigDecimal.class), eq("USDT"), any(BigDecimal.class),
                any(BigDecimal.class), eq("COOLING"), anyInt(), anyString()))
                .thenReturn(101L, 102L);

        int settled = service.settle(990686L, new BigDecimal("1000"), "ORD-1");

        assertThat(settled).isEqualTo(2);
        // L1: 1000×10%=$100; L2: 1000×5%=$50;amountNex=0(USDT 事件)
        verify(commissionRepository).insertNetworkCommissionEvent(eq(990685L), eq("network"), eq(990686L),
                eq(1), eq("ORD-1"), eq(new BigDecimal("1000")), eq("USDT"),
                eq(new BigDecimal("100.000000")), eq(new BigDecimal("0.000000")),
                eq("COOLING"), anyInt(), anyString());
        verify(commissionRepository).insertNetworkCommissionEvent(eq(990684L), eq("network"), eq(990686L),
                eq(2), eq("ORD-1"), eq(new BigDecimal("1000")), eq("USDT"),
                eq(new BigDecimal("50.000000")), eq(new BigDecimal("0.000000")),
                eq("COOLING"), anyInt(), anyString());
        verify(ledgerPostingFacade, times(2)).postLedgerEntry(anyString(), anyLong(), anyString(),
                anyString(), anyString(), any(BigDecimal.class), anyString(), anyString());
    }

    @Test
    void settle_idempotent_skipAlreadySettledAncestor() {
        when(teamCommissionMapper.listUplineChain(990686L, 7))
                .thenReturn(List.of(upline(990685L, 1), upline(990684L, 2)));
        when(teamCommissionMapper.unilevelRates())
                .thenReturn(List.of(rate("L1", 10), rate("L2", 5)));
        // L1 已派发(幂等命中) → 只 L2 派发
        when(commissionRepository.countNetworkCommissionByOrder(990685L, "ORD-1")).thenReturn(1);
        when(commissionRepository.countNetworkCommissionByOrder(990684L, "ORD-1")).thenReturn(0);
        // L2 月业绩=100 → InfluenceScore=1.0 → 不影响 L2 finalUsdt
        when(teamCommissionMapper.monthlyNetworkVolume(990684L)).thenReturn(new BigDecimal("100"));
        when(commissionRepository.insertNetworkCommissionEvent(anyLong(), anyString(), anyLong(),
                any(), anyString(), any(BigDecimal.class), anyString(), any(BigDecimal.class),
                any(BigDecimal.class), anyString(), anyInt(), anyString()))
                .thenReturn(201L);

        int settled = service.settle(990686L, new BigDecimal("1000"), "ORD-1");

        assertThat(settled).isEqualTo(1);
        verify(commissionRepository, never()).insertNetworkCommissionEvent(eq(990685L), anyString(),
                anyLong(), any(), anyString(), any(BigDecimal.class), anyString(), any(BigDecimal.class),
                any(BigDecimal.class), anyString(), anyInt(), anyString());
        verify(commissionRepository, times(1)).insertNetworkCommissionEvent(eq(990684L), anyString(),
                anyLong(), any(), anyString(), any(BigDecimal.class), anyString(), any(BigDecimal.class),
                any(BigDecimal.class), anyString(), anyInt(), anyString());
    }

    @Test
    void settle_noUpline_returnsZero() {
        when(teamCommissionMapper.listUplineChain(888888L, 7)).thenReturn(List.of());
        int settled = service.settle(888888L, new BigDecimal("1000"), "ORD-X");
        assertThat(settled).isZero();
        verify(commissionRepository, never()).insertNetworkCommissionEvent(anyLong(), anyString(),
                anyLong(), any(), anyString(), any(BigDecimal.class), anyString(), any(BigDecimal.class),
                any(BigDecimal.class), anyString(), anyInt(), anyString());
    }

    @Test
    void settle_invalidInputs_returnsZero() {
        assertThat(service.settle(null, new BigDecimal("1000"), "ORD")).isZero();
        assertThat(service.settle(990686L, null, "ORD")).isZero();
        assertThat(service.settle(990686L, BigDecimal.ZERO, "ORD")).isZero();
        assertThat(service.settle(990686L, new BigDecimal("1000"), "")).isZero();
        assertThat(service.settle(990686L, new BigDecimal("1000"), " ")).isZero();
    }

    @Test
    void settle_layerWithoutRate_skipped() {
        // 上级链含 L3 但费率表只配 L1/L2 → L3 跳过
        when(teamCommissionMapper.listUplineChain(990686L, 7))
                .thenReturn(List.of(upline(990685L, 1), upline(990683L, 3)));
        when(teamCommissionMapper.unilevelRates())
                .thenReturn(List.of(rate("L1", 10)));
        when(commissionRepository.countNetworkCommissionByOrder(anyLong(), anyString())).thenReturn(0);
        when(commissionRepository.insertNetworkCommissionEvent(anyLong(), anyString(), anyLong(),
                any(), anyString(), any(BigDecimal.class), anyString(), any(BigDecimal.class),
                any(BigDecimal.class), anyString(), anyInt(), anyString()))
                .thenReturn(301L);

        int settled = service.settle(990686L, new BigDecimal("1000"), "ORD-3");

        assertThat(settled).isEqualTo(1);
        // 只 L1 派发,L3 无费率跳过
        verify(commissionRepository, never()).insertNetworkCommissionEvent(eq(990683L), anyString(),
                anyLong(), any(), anyString(), any(BigDecimal.class), anyString(), any(BigDecimal.class),
                any(BigDecimal.class), anyString(), anyInt(), anyString());
    }

    // ============================================================
    // F2 depthGate(L4+ 上级需 ≥ depthGateRank V2;configFacade 默认 empty → L4/V2)
    // ============================================================

    @Test
    void settle_depthGate_L4_ancestorV0_skipped() {
        // L4 上级 v_rank=V0(0 < 2)→ depthGate 跳过,不派发
        when(teamCommissionMapper.listUplineChain(990686L, 7))
                .thenReturn(List.of(upline(990685L, 4, "V0")));
        when(teamCommissionMapper.unilevelRates())
                .thenReturn(List.of(rate("L4", 2)));

        int settled = service.settle(990686L, new BigDecimal("1000"), "ORD-G");

        assertThat(settled).isZero();
        verify(commissionRepository, never()).insertNetworkCommissionEvent(anyLong(), anyString(),
                anyLong(), any(), anyString(), any(BigDecimal.class), anyString(), any(BigDecimal.class),
                any(BigDecimal.class), anyString(), anyInt(), anyString());
    }

    @Test
    void settle_depthGate_L4_ancestorV2_passes() {
        // L4 上级 v_rank=V2(2 >= 2)→ depthGate 通过,正常派发 USDT
        when(teamCommissionMapper.listUplineChain(990686L, 7))
                .thenReturn(List.of(upline(990685L, 4, "V2")));
        when(teamCommissionMapper.unilevelRates())
                .thenReturn(List.of(rate("L4", 2)));
        // L4 月业绩=100 → InfluenceScore=1.0 → 不影响 L4 finalUsdt(保持 20 断言)
        when(teamCommissionMapper.monthlyNetworkVolume(990685L)).thenReturn(new BigDecimal("100"));
        when(commissionRepository.countNetworkCommissionByOrder(anyLong(), anyString())).thenReturn(0);
        when(commissionRepository.insertNetworkCommissionEvent(anyLong(), anyString(), anyLong(),
                any(), anyString(), any(BigDecimal.class), anyString(), any(BigDecimal.class),
                any(BigDecimal.class), anyString(), anyInt(), anyString()))
                .thenReturn(501L);

        int settled = service.settle(990686L, new BigDecimal("1000"), "ORD-G2");

        assertThat(settled).isEqualTo(1);
        // L4: 1000×2%=$20
        verify(commissionRepository).insertNetworkCommissionEvent(eq(990685L), eq("network"), eq(990686L),
                eq(4), eq("ORD-G2"), eq(new BigDecimal("1000")), eq("USDT"),
                eq(new BigDecimal("20.000000")), any(BigDecimal.class),
                eq("COOLING"), anyInt(), anyString());
    }

    // ============================================================
    // F2 NEX 派发(nex_per_usd × USDT 佣金)
    // ============================================================

    @Test
    void settle_nexPerUsd_dispatchesBothUsdtAndNexCommissionEvents() {
        // L1 上级:usdtPct=10%, nexPerUsd=0.1;订单 subtotal=$1000
        when(teamCommissionMapper.listUplineChain(990686L, 7))
                .thenReturn(List.of(upline(990685L, 1, "V1")));
        when(teamCommissionMapper.unilevelRates())
                .thenReturn(List.of(rate("L1", 10, 0.1)));
        when(commissionRepository.countNetworkCommissionByOrder(anyLong(), anyString())).thenReturn(0);
        when(commissionRepository.insertNetworkCommissionEvent(anyLong(), anyString(), anyLong(),
                any(), anyString(), any(BigDecimal.class), anyString(), any(BigDecimal.class),
                any(BigDecimal.class), anyString(), anyInt(), anyString()))
                .thenReturn(601L, 602L);

        int settled = service.settle(990686L, new BigDecimal("1000"), "ORD-N");

        assertThat(settled).isEqualTo(1);
        // USDT: 1000×10%=100; NEX: 100×0.1=10
        verify(commissionRepository).insertNetworkCommissionEvent(eq(990685L), eq("network"), eq(990686L),
                eq(1), eq("ORD-N"), eq(new BigDecimal("1000")), eq("USDT"),
                eq(new BigDecimal("100.000000")), eq(new BigDecimal("0.000000")),
                eq("COOLING"), anyInt(), anyString());
        verify(commissionRepository).insertNetworkCommissionEvent(eq(990685L), eq("network"), eq(990686L),
                eq(1), eq("ORD-N"), eq(new BigDecimal("1000")), eq("NEX"),
                eq(new BigDecimal("0.000000")), eq(new BigDecimal("10.000000")),
                eq("COOLING"), anyInt(), anyString());
        verify(ledgerPostingFacade, times(2)).postLedgerEntry(anyString(), anyLong(), anyString(),
                anyString(), anyString(), any(BigDecimal.class), anyString(), anyString());
    }

    // ============================================================
    // F2 InfluenceScore clamp(PRD line1400/1529):L2-L7 扩展层乘 ancestor 月业绩 score;L1 直推不乘
    // 公式:score = clamp(1 + log10(monthlyNetworkVolume/100), clampMin=1.0, clampMax=5.0)
    // ============================================================

    @Test
    void settle_influenceScoreBoostsL2() {
        // L2 上级月业绩=10000 → score=1+log10(100)=3.0 → L2 finalUsdt = 50×3 = 150;L1 不乘(恒 100)
        when(teamCommissionMapper.listUplineChain(990686L, 7))
                .thenReturn(List.of(upline(990685L, 1), upline(990684L, 2)));
        when(teamCommissionMapper.unilevelRates())
                .thenReturn(List.of(rate("L1", 10), rate("L2", 5)));
        when(teamCommissionMapper.monthlyNetworkVolume(990684L)).thenReturn(new BigDecimal("10000"));
        when(commissionRepository.countNetworkCommissionByOrder(anyLong(), anyString())).thenReturn(0);
        when(commissionRepository.insertNetworkCommissionEvent(anyLong(), eq("network"), anyLong(),
                any(), anyString(), any(BigDecimal.class), eq("USDT"), any(BigDecimal.class),
                any(BigDecimal.class), eq("COOLING"), anyInt(), anyString()))
                .thenReturn(701L, 702L);

        int settled = service.settle(990686L, new BigDecimal("1000"), "ORD-IS");

        assertThat(settled).isEqualTo(2);
        // L1: 1000×10%=100(InfluenceScore 不乘);L2: 1000×5%×score(3.0)=150
        verify(commissionRepository).insertNetworkCommissionEvent(eq(990685L), eq("network"), eq(990686L),
                eq(1), eq("ORD-IS"), eq(new BigDecimal("1000")), eq("USDT"),
                eq(new BigDecimal("100.000000")), eq(new BigDecimal("0.000000")),
                eq("COOLING"), anyInt(), anyString());
        verify(commissionRepository).insertNetworkCommissionEvent(eq(990684L), eq("network"), eq(990686L),
                eq(2), eq("ORD-IS"), eq(new BigDecimal("1000")), eq("USDT"),
                eq(new BigDecimal("150.000000")), eq(new BigDecimal("0.000000")),
                eq("COOLING"), anyInt(), anyString());
    }

    @Test
    void settle_influenceScore_zeroVolume_returnsClampMin() {
        // L2 上级月业绩=0 → InfluenceScore=clampMin=1.0 → L2 finalUsdt=50(不放大)
        when(teamCommissionMapper.listUplineChain(990686L, 7))
                .thenReturn(List.of(upline(990685L, 1), upline(990684L, 2)));
        when(teamCommissionMapper.unilevelRates())
                .thenReturn(List.of(rate("L1", 10), rate("L2", 5)));
        when(teamCommissionMapper.monthlyNetworkVolume(990684L)).thenReturn(BigDecimal.ZERO);
        when(commissionRepository.countNetworkCommissionByOrder(anyLong(), anyString())).thenReturn(0);
        when(commissionRepository.insertNetworkCommissionEvent(anyLong(), eq("network"), anyLong(),
                any(), anyString(), any(BigDecimal.class), eq("USDT"), any(BigDecimal.class),
                any(BigDecimal.class), eq("COOLING"), anyInt(), anyString()))
                .thenReturn(801L, 802L);

        service.settle(990686L, new BigDecimal("1000"), "ORD-Z");

        // L2 finalUsdt=50(0 业绩回退 clampMin=1.0,不放大)
        verify(commissionRepository).insertNetworkCommissionEvent(eq(990684L), eq("network"), eq(990686L),
                eq(2), eq("ORD-Z"), eq(new BigDecimal("1000")), eq("USDT"),
                eq(new BigDecimal("50.000000")), eq(new BigDecimal("0.000000")),
                eq("COOLING"), anyInt(), anyString());
    }
}
