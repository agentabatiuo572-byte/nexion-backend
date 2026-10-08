package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.team.mapper.AppTeamInsightsMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SevenLayerAppInsightsServiceTest {
    private final AppTeamInsightsMapper mapper = mock(AppTeamInsightsMapper.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final AppTeamInsightsService service = new AppTeamInsightsService(mapper,
            mock(LeadershipPoolConfigGuard.class), config, new MockEnvironment(), null);

    @Test
    @SuppressWarnings("unchecked")
    void filterAndGroupCountDoNotTurnCurrentPageIntoPeriodTotals() {
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        when(mapper.purchaseSummary(eq(7L),eq(0),eq("PRODUCTION"),eq(""),any(),any(),any())).thenReturn(List.of(
            new AppTeamInsightsMapper.PurchaseSummaryRow("direct",41,money("123"),money("67"),money("100"),money("40"),money("23"),money("27")),
            new AppTeamInsightsMapper.PurchaseSummaryRow("extended",8,money("56"),money("900"),money("50"),money("800"),money("6"),money("100"))));
        var at = LocalDateTime.of(2026,10,5,12,0);
        when(mapper.purchaseRewards(eq(7L),eq(0),eq("PRODUCTION"),eq(""),any(),any(),any(),eq("direct"),eq(20L),eq(20L)))
            .thenReturn(List.of(new AppTeamInsightsMapper.PurchaseRewardRow("DR-L1",8L,"B",1,"ORDER","2026-W41",
                money("100"),money("0"),money("0"),"DUAL","WAITING_CALCULATION",at,at.plusDays(30),
                "direct_purchase","ORDER",2L,null,money("0"),money("0"),"WAITING_CALCULATION","WAITING_CALCULATION")));
        var result = service.unilevel(7L,"month",2,20,"2026-10-05T08:00:00Z","direct",2);
        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("totalRows",41L).containsEntry("schemaVersion",2).containsEntry("filter","direct");
        var summary=(Map<String,Object>)result.getData().get("summary");
        assertThat(summary).containsEntry("amountUSDT",money("123")).containsEntry("amountNEX",money("67"));
        var split=(Map<String,Map<String,Object>>)result.getData().get("split");
        assertThat(split.get("extended")).containsEntry("count",8L).containsEntry("amountNEX",money("900"));
        var events=(List<Map<String,Object>>)result.getData().get("events");
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("id","DR-L1").containsEntry("status","waiting_calculation")
            .containsEntry("nexUsdtPrice",null).containsEntry("currency","DUAL").containsEntry("withdrawable",false);
        verify(mapper,never()).unilevelEvents(any(),any(),any(),any(),any(),anyLong(),anyLong());
    }

    @Test
    void oldSchemaIsRejectedAfterExplicitCutoverAndWalletIsNotInvolved() {
        when(config.activeValue("team.seven-layer.cutover-at")).thenReturn(Optional.of("2026-01-01T00:00:00Z"));
        assertThat(service.unilevel(7L,"week",1,20,null,"all",1).getCode()).isEqualTo(422);
        verifyNoInteractions(mapper);
    }

    @Test
    void unknownFilterOrSchemaIsRejectedBeforeReadingRewards() {
        assertThat(service.unilevel(7L,"week",1,20,null,"device",2).getCode()).isEqualTo(422);
        assertThat(service.unilevel(7L,"week",1,20,null,"all",3).getCode()).isEqualTo(422);
        assertThat(service.unilevel(7L,"week",1,20,null,"direct",1).getCode()).isEqualTo(422);
        verifyNoInteractions(mapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void allSummaryAddsOnlyTheTwoPurchaseScopes() {
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0,"V5"));
        when(mapper.purchaseSummary(eq(7L),eq(0),eq("PRODUCTION"),eq(""),any(),any(),any())).thenReturn(List.of(
            new AppTeamInsightsMapper.PurchaseSummaryRow("direct",2,money("10"),money("5"),money("4"),money("2"),money("6"),money("3")),
            new AppTeamInsightsMapper.PurchaseSummaryRow("extended",1,money("3"),money("9"),money("3"),money("9"),money("0"),money("0"))));
        var data=service.unilevel(7L,"all",1,1,"2026-10-05T08:00:00Z","all",2).getData();
        assertThat((Map<String,Object>)data.get("summary")).containsEntry("count",3L)
            .containsEntry("amountUSDT",money("13")).containsEntry("amountNEX",money("14"))
            .containsEntry("creditedUSDT",money("7")).containsEntry("pendingUSDT",money("6"));
    }

    private static BigDecimal money(String value) { return new BigDecimal(value); }
}
