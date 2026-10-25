package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ffdd.opsconsole.growth.application.H4EventRuleService.values;

class H4EventVisibilityTest {
    static final String CODE="evt-reinvest-bonus";
    final H4EventRuleRepository db=mock(H4EventRuleRepository.class);
    final H4EventRuleService policies=mock(H4EventRuleService.class);
    final H4EventCommerceService commerce=mock(H4EventCommerceService.class);
    final AppGrowthEngagementMapper events=mock(AppGrowthEngagementMapper.class);
    final AdminIdempotencyService idempotency=mock(AdminIdempotencyService.class);
    final H4EventEngine engine=new H4EventEngine(db,policies,commerce,events,null,null,null,idempotency,mock(AuditLogService.class));
    final LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC);
    final Map<String,Object> rule=values("event_code",CODE,"kind","boost","enabled",1,"starts_at",now.minusDays(1),"ends_at",now.plusDays(1),"activated_at",now.minusDays(1),"spent_tickets",0);
    final Map<String,Object> rules=Map.of("action","REINVEST_TICKETS","thresholdUsdt",100,"ticketsPerThreshold",2,"ticketBudget",10);
    final Map<String,Object> source=values("eventCode",CODE,"kind","boost","state","ongoing","userStatus","AVAILABLE","href","/pages/me/wallet-repurchase");

    @BeforeEach @SuppressWarnings({"rawtypes","unchecked"}) void setup(){
        when(db.row(anyString(),any(Object[].class))).thenReturn(null);
        when(db.row(startsWith("SELECT *,TIMESTAMPDIFF"),eq(7L))).thenReturn(Map.of("id",7L,"status","ACTIVE"));
        when(policies.find(eq(CODE),anyBoolean())).thenReturn(rule);when(policies.rules(rule)).thenReturn(rules);
        when(events.eventState(7L,"en")).thenReturn(List.of(source));
        when(commerce.progress(eq(7L),eq(CODE),eq(rules),any(),any())).thenReturn(Map.of("current",0,"target",100,"eligible",false,"claimKeys",List.of()));
        when(idempotency.executeRetained(anyString(),anyString(),any(),eq(ApiResult.class),any()))
                .thenAnswer(i->((java.util.function.Supplier)i.getArgument(4)).get());
        when(db.write(anyString(),any(Object[].class))).thenReturn(1);
    }

    @Test void hiddenSwitchHidesNewParticipationAndRejectsDeepLinksBeforeWriting(){
        assertThat(engine.project(7L,List.of(source),"en")).isEmpty();
        var detail=engine.detail(7L,CODE,"en").getData();
        assertThat(detail).containsEntry("enabled",false).containsEntry("eligible",false).containsEntry("eligibilityReason","DISABLED");
        assertThat(((Map<?,?>)detail.get("event")).get("state")).isEqualTo("ended");
        assertThat(((Map<?,?>)detail.get("event")).get("href")).isEqualTo("");
        assertThatThrownBy(()->engine.join(7L,CODE)).hasMessage("EVENT_NOT_OPEN");
        assertThatThrownBy(()->engine.remind(7L,CODE)).hasMessage("EVENT_REMINDER_NOT_AVAILABLE");
        engine.notifyReminder(7L,CODE);
        verify(db,never()).write(anyString(),any(Object[].class));
    }

    @Test void hiddenSwitchRetainsEarnedClaimAndBudgetSettlement(){
        when(commerce.progress(eq(7L),eq(CODE),eq(rules),any(),any())).thenReturn(Map.of("current",100,"target",100,"eligible",true,"claimKeys",List.of("RP-1")));
        var projected=engine.project(7L,List.of(source),"en");
        assertThat(projected).hasSize(1);assertThat(projected.get(0)).containsEntry("userStatus","CLAIMABLE").containsEntry("href","");
        assertThat(engine.detail(7L,CODE,"en").getData()).containsEntry("enabled",false).containsEntry("eligible",true).containsEntry("eligibilityReason","READY");
        when(commerce.awardReinvestTickets(eq(7L),eq(CODE),eq(rules),any(),any(),eq("RP-1"))).thenReturn(Map.of("rewardType","GENESIS_TICKET","quantity",1,"receiptId","GT-1"));
        assertThat(engine.claim(7L,CODE,"claim-old").getCode()).isZero();
        verify(commerce).awardReinvestTickets(eq(7L),eq(CODE),eq(rules),any(),any(),eq("RP-1"));
        verify(db).write(contains("INSERT INTO nx_growth_event_claim"),any(Object[].class));
        verify(commerce,never()).reinvestParticipationOpen(true);
    }

    @Test void previouslyJoinedUsersKeepHistoryWithoutAnOpenRepurchaseLink(){
        source.put("userStatus","JOINED");
        assertThat(engine.project(7L,List.of(source),"en").get(0)).containsEntry("userStatus","JOINED").containsEntry("href","").containsEntry("state","ended");
    }

    @Test void deletedActivityRetainsOnlyTheOwnersHistoryAndPreviouslyEarnedClaim(){
        when(events.eventState(7L,"en")).thenReturn(List.of());
        when(events.deletedEventState(7L,"en",CODE)).thenReturn(List.of(source));
        rule.put("enabled",0);rule.put("ends_at",now.minusHours(1));
        assertThatThrownBy(()->engine.detail(7L,CODE,"en")).hasMessage("EVENT_NOT_FOUND");
        when(commerce.progress(eq(7L),eq(CODE),eq(rules),any(),any()))
                .thenReturn(Map.of("current",100,"target",100,"eligible",true,"claimKeys",List.of("RP-1")));
        var detail=engine.detail(7L,CODE,"en").getData();
        assertThat(detail).containsEntry("enabled",false).containsEntry("eligible",true).containsEntry("eligibilityReason","READY");
        assertThat((Map<String,Object>)detail.get("event")).containsEntry("deleted",true).containsEntry("href","");
        when(commerce.awardReinvestTickets(eq(7L),eq(CODE),eq(rules),any(),any(),eq("RP-1")))
                .thenReturn(Map.of("rewardType","GENESIS_TICKET","quantity",1));
        assertThat(engine.claim(7L,CODE,"claim-before-delete").getCode()).isZero();
        assertThatThrownBy(()->engine.join(7L,CODE)).hasMessage("EVENT_NOT_OPEN");
        verify(db).write(contains("INSERT INTO nx_growth_event_claim"),any(Object[].class));
    }

    @Test void reopeningRestoresEntryAndLocksVisibilityBeforeTheAccount(){
        when(commerce.reinvestParticipationOpen(anyBoolean())).thenReturn(true);
        assertThat(engine.project(7L,List.of(source),"en")).hasSize(1);
        assertThat(engine.detail(7L,CODE,"en").getData()).containsEntry("enabled",true).containsEntry("eligible",true);
        clearInvocations(commerce,db);
        assertThat(engine.join(7L,CODE).getCode()).isZero();
        var order=inOrder(commerce,db);order.verify(commerce).reinvestParticipationOpen(true);
        order.verify(db).row(startsWith("SELECT *,TIMESTAMPDIFF"),eq(7L));
    }
}
