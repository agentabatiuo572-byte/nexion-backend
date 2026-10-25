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
    @Test void referralDraftStillNeedsCompleteRulesContentInventoryAndCoverageToPublish() throws Exception {
        String code="evt-refer-5-get-pro";
        var db=mock(H4EventRuleRepository.class);
        var commerce=mock(H4EventCommerceService.class);
        var coverage=mock(ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade.class);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var service=new H4EventRuleService(db,json,mock(ffdd.opsconsole.shared.audit.AuditLogService.class),
                commerce,coverage,mock(ffdd.opsconsole.platform.application.A2RuntimePolicy.class));
        when(db.row("SELECT * FROM nx_growth_event_rule WHERE event_code=? FOR UPDATE",code))
                .thenReturn(values("event_code",code,"revision",1,"enabled",0,"rules_json","{}"));
        when(db.row("SELECT 1 FROM nx_event_quest WHERE quest_code=? AND is_deleted=1 FOR UPDATE",code)).thenReturn(null);
        var rules=Map.<String,Object>of("targetInvites",1,"windowDays",7,"giftQuantity",1,"deviceBudget",2,"giftProductNo","PRO");
        var incomplete=new ffdd.opsconsole.growth.dto.H4EventRuleRequest(1,true,start.toInstant(java.time.ZoneOffset.UTC).toString(),end.toInstant(java.time.ZoneOffset.UTC).toString(),Map.of(),"Publish referral draft");
        assertThat(service.update(code,incomplete).getMessage()).isEqualTo("EVENT_RULE_INCOMPLETE");
        var request=new ffdd.opsconsole.growth.dto.H4EventRuleRequest(1,true,start.toInstant(java.time.ZoneOffset.UTC).toString(),end.toInstant(java.time.ZoneOffset.UTC).toString(),rules,"Publish referral draft");
        assertThat(service.update(code,request).getData().get("missing")).asList().contains("localized.zh.name");
        var text=Map.of("name","Referral","description","Invite a friend","rewardName","Pro 设备1台");
        when(db.row("SELECT config_value FROM nx_config_item WHERE config_key='growth.content.localized' AND status=1 AND is_deleted=0 LIMIT 1"))
                .thenReturn(Map.of("config_value",json.writeValueAsString(Map.of("event",Map.of(code,Map.of("zh",text,"vi",text))))));
        when(commerce.readiness(code,rules,start,end)).thenReturn(List.of("giftProductNo"));
        assertThat(service.update(code,request).getData().get("missing")).asList().containsExactly("giftProductNo");
        when(commerce.readiness(code,rules,start,end)).thenReturn(List.of());
        when(coverage.snapshot()).thenReturn(new ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot(new BigDecimal("80"),new BigDecimal("85")));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.update(code,request))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class).hasMessage("COVERAGE_REDLINE");
        assertThat(mockingDetails(db).getInvocations()).noneMatch(call->call.getMethod().getName().equals("write"));
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
