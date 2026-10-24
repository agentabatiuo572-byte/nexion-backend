package ffdd.opsconsole.growth.application;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static ffdd.opsconsole.growth.application.H4EventRuleService.*;
import static org.mockito.Mockito.*;

class H4EventRulesTest {
    private final LocalDateTime start=LocalDateTime.of(2026,11,1,0,0),end=start.plusDays(7);

    @Test void refusesMissingTimeBudgetAndFractionalDeviceRights(){
        var rules=Map.<String,Object>of("targetInvites",5,"windowDays",7,"giftQuantity",1.5,"deviceBudget",0,"giftProductNo","PRO");
        assertThat(missing("evt-refer-5-get-pro",rules,null,null)).contains("startsAt","endsAt","giftQuantity","deviceBudget");
    }
    @Test void requiresExactReinvestActionAndUniqueRegionMembership(){
        assertThat(missing("evt-reinvest-bonus",Map.of("thresholdUsdt",100,"ticketsPerThreshold",2,"ticketBudget",10),start,end)).contains("action");
        var regional=Map.<String,Object>of("poolUsdt",100,"metric","PAID_ORDER_USDT","allocation","PROPORTIONAL_VOLUME",
                "regions",List.of(Map.of("name","SEA","countries",List.of("VN")),Map.of("name","OTHER","countries",List.of("VN"))));
        assertThat(missing("evt-regional-pk",regional,start,end)).contains("regions.countries");
    }
    @Test void equalPoolPreservesEveryMicroUnitAndIsIndependentOfQueryOrder(){
        var members=List.of(values("userId",3L,"volume",1),values("userId",1L,"volume",1),values("userId",2L,"volume",1));
        var allocations=H4EventEngine.allocatePool(members,new BigDecimal("1.000000"));
        assertThat(allocations).containsEntry(1L,new BigDecimal("0.333334"))
                .containsEntry(2L,new BigDecimal("0.333333")).containsEntry(3L,new BigDecimal("0.333333"));
        assertThat(allocations.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("1");
    }
    @Test void weightedPoolGivesRemainderToLargestFractionWithoutExceedingPool(){
        var allocation=H4EventEngine.allocatePool(List.of(values("userId",1L,"volume",1),values("userId",2L,"volume",2)),new BigDecimal("0.000002"));
        assertThat(allocation).containsEntry(1L,new BigDecimal("0.000001")).containsEntry(2L,new BigDecimal("0.000001"));
        assertThat(H4EventEngine.allocatePool(List.of(),new BigDecimal("100"))).isEmpty();
    }
    @Test void acceptsConfiguredCompositeOnboardingAndHoldingPolicies(){
        assertThat(missing("evt-onboarding-7d",Map.of("windowDays",7,"minRank",2,"rewardNex",200,"streakSavers",1,"budgetNex",2000),start,end)).isEmpty();
        assertThat(missing("evt-nex-holders-share",Map.of("minNex",1000,"holdDays",7,"poolUsdt",5000,"allocation","EQUAL"),start,end)).isEmpty();
        assertThat(missing("evt-nex-holders-share",Map.of("minNex",new BigDecimal("0.0000001"),"holdDays",7,"poolUsdt",5000,"allocation","EQUAL"),start,end)).contains("minNex");
        assertThat(missing("evt-nex-holders-share",Map.of("minNex",new BigDecimal("1000000000000"),"holdDays",7,"poolUsdt",5000,"allocation","EQUAL"),start,end)).contains("minNex");
    }
    @Test void rewardSummaryUsesActualCompositeConfigurationAndTicketGrantLimits(){
        assertThat(H4EventEngine.rewardSummary(Map.of("rewardNex",200,"streakSavers",1),"zh")).isEqualTo("200 UVT + 1 补签卡");
        assertThat(H4EventEngine.rewardSummary(Map.of("giftProductNo","P1","giftQuantity",2),"en")).isEqualTo("2 device");
        assertThat(H4EventEngine.rewardSummary(Map.of("poolUsdt",5000),"en")).isEqualTo("5000 USDT total pool");
        assertThat(missing("evt-pro-upgrade-7d",Map.of("promotionActivityId","P1","extraGenesisTickets",1000001,"ticketBudget",2000000),start,end)).contains("extraGenesisTickets");
        assertThat(missing("evt-weekend-double-nex",Map.of("action","CHECKIN_MULTIPLIER","multiplier",1.5,"budgetNex",100,"timeZone","UTC","weekdays",List.of(6,7)),start,end)).contains("multiplier");
        assertThat(missing("evt-anniversary-spin",Map.of(),LocalDateTime.now().minusDays(2),LocalDateTime.now().minusDays(1))).isEmpty();
        assertThat(missing("evt-anniversary-spin",Map.of(),LocalDateTime.now(java.time.ZoneOffset.UTC),LocalDateTime.now(java.time.ZoneOffset.UTC).plusDays(1))).contains("endsAt");
    }
    @Test void exhaustedBonusBudgetPreservesBaseCheckInWithoutAFalseBonusReceipt(){
        var db=mock(H4EventRuleRepository.class);var policies=mock(H4EventRuleService.class);
        var now=LocalDateTime.now(java.time.ZoneOffset.UTC);
        var rule=values("event_code","evt-weekend-double-nex","enabled",1,"starts_at",now.minusDays(1),"ends_at",now.plusDays(1),"spent_nex",9);
        when(policies.find("evt-weekend-double-nex",true)).thenReturn(rule);
        when(policies.rules(rule)).thenReturn(Map.of("timeZone","UTC","weekdays",List.of(1,2,3,4,5,6,7),"multiplier",2,"budgetNex",10));
        var engine=new H4EventEngine(db,policies,null,null,null,null,null,null,null);
        assertThat(engine.checkInReward(1L,now.toLocalDate(),3)).isEqualTo(3);
        verifyNoInteractions(db);
    }
}
