package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.promotion.application.PromotionPublicService;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ffdd.opsconsole.growth.application.H4EventRuleService.*;

class H4EventSeasonalVoucherTest {
    static final String CODE="evt-black-friday";
    final PromotionMapper db=mock(PromotionMapper.class);
    final PromotionPublicService promotions=mock(PromotionPublicService.class);
    final Clock clock=Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"),ZoneOffset.UTC);
    final LocalDateTime start=LocalDateTime.of(2026,10,24,0,0),end=start.plusDays(7);
    final Map<String,Object> rules=values("voucherId","BF20");
    final ffdd.opsconsole.promotion.application.PromotionPolicyResolver resolver=mock(ffdd.opsconsole.promotion.application.PromotionPolicyResolver.class);
    final H4EventCommerceService service=new H4EventCommerceService(db,promotions,clock,null,null,null,resolver);
    Map<String,Object> definition(){
        return values("voucher_id","BF20","status","active","voucher_type","percent","percent_value",20,
            "min_purchase_usd",0,"max_discount_usd",0,"audience","all","all_skus",1,"store_claimable",1,
            "start_at",start.toInstant(ZoneOffset.UTC).toEpochMilli(),"end_at",end.toInstant(ZoneOffset.UTC).toEpochMilli(),
            "stack_with_others",1,"issuance_limit",null,"issued_count",0);
    }
    void voucher(Map<String,Object> definition){when(db.one(contains("FROM nx_growth_voucher v"),eq("BF20"))).thenReturn(definition);when(db.one(contains("SELECT grant_id,status"),eq(7L),eq("BF20"))).thenReturn(null);}
    Map<String,Object> progress(){return service.progress(7L,CODE,rules,start,end);}

    @Test void voucherAloneMakesBlackFridayCompleteWithoutProQualification(){
        voucher(definition());
        assertThat(missing(CODE,rules,start,end)).isEmpty();
        assertThat(service.readiness(CODE,rules,start,end)).isEmpty();
        assertThat(progress()).containsEntry("eligible",true).containsEntry("eligibilityReason","READY");
        assertThat((Map<String,Object>)progress().get("voucher")).containsEntry("grantStatus","UNCLAIMED").containsEntry("claimable",true).containsEntry("singleUse",true).containsEntry("singleSkuOnly",true);
        verifyNoInteractions(promotions);
        assertThat(missing(CODE,values("promotionActivityId","PRO"),start,end)).contains("voucherId");
        assertThat(missing(CODE,values("voucherId","BF20","extraGenesisTickets",1,"ticketBudget",10),start,end)).contains("promotionActivityId");
    }
    @Test void openingChecksRealDiscountScopeClaimSurfaceAndEntirePeriod(){
        for(var change:List.of(values("percent_value",19),values("voucher_type","fixed"),values("min_purchase_usd",100),values("max_discount_usd",100))){
            var v=definition();v.putAll(change);voucher(v);assertThat(service.readiness(CODE,rules,start,end)).contains("voucherDiscount");
        }
        for(var change:List.of(values("audience","new"),values("all_skus",0))){var v=definition();v.putAll(change);voucher(v);assertThat(service.readiness(CODE,rules,start,end)).contains("voucherScope");}
        var scoped=definition();scoped.put("all_skus",0);scoped.put("applicable_skus","[\"PRO\"]");voucher(scoped);
        assertThat((Map<String,Object>)progress().get("voucher")).containsEntry("applicableSkus",List.of("PRO"));
        assertThat(progress()).containsEntry("eligible",false);
        var v=definition();v.put("store_claimable",false);voucher(v);assertThat(service.readiness(CODE,rules,start,end)).contains("voucherClaimSurface");
        v=definition();v.put("end_at",end.minusSeconds(1).toInstant(ZoneOffset.UTC).toEpochMilli());voucher(v);assertThat(service.readiness(CODE,rules,start,end)).contains("voucherPeriod");
        v=definition();v.put("start_at",start.plusSeconds(1).toInstant(ZoneOffset.UTC).toEpochMilli());voucher(v);assertThat(service.readiness(CODE,rules,start,end)).contains("voucherPeriod");
        v=definition();v.put("stack_with_others",0);voucher(v);assertThat(service.readiness(CODE,values("voucherId","BF20","promotionActivityId","PRO"),start,end)).contains("voucherStacking");
        assertThat(service.readiness(CODE,rules,start,end)).isEmpty();
    }
    @Test void optionalPromotionMustAlsoHaveAnApprovedStackingPolicy(){
        voucher(definition());var stacked=values("voucherId","BF20","promotionActivityId","PRO");
        when(db.one(contains("SELECT p.activity_id,v.contract_json"),any(Object[].class))).thenReturn(values("activity_id","PRO","contract_json","{}"));
        when(resolver.resolve(anyMap(),eq(false))).thenReturn(values("$stacking",values("content",values("voucher","DENY"))));
        assertThat(service.readiness(CODE,stacked,start,end)).containsExactly("voucherStacking");
        when(resolver.resolve(anyMap(),eq(false))).thenReturn(values("$stacking",values("content",values("voucher","ALLOW"))));
        assertThat(service.readiness(CODE,stacked,start,end)).isEmpty();
        when(resolver.resolve(anyMap(),eq(false))).thenThrow(new ffdd.opsconsole.shared.exception.BizException(409,"PROMOTION_POLICY_NOT_APPROVED"));
        assertThat(service.readiness(CODE,stacked,start,end)).containsExactly("voucherStacking");
    }
    @Test void forgedPausedExpiredAndUnstartedVouchersAreNotParticipation(){
        voucher(null);assertThat(progress()).containsEntry("eligible",false).containsEntry("eligibilityReason","VOUCHER_UNAVAILABLE");
        var v=definition();v.put("status","paused");voucher(v);assertThat(progress()).containsEntry("eligible",false);
        v=definition();v.put("end_at",clock.millis()-1);voucher(v);assertThat(progress()).containsEntry("eligible",false).containsEntry("eligibilityReason","VOUCHER_EXPIRED");
        v=definition();v.put("start_at",clock.millis()+1);voucher(v);assertThat(progress()).containsEntry("eligible",false).containsEntry("eligibilityReason","VOUCHER_NOT_STARTED");
        verify(db,never()).write(anyString(),any(Object[].class));verifyNoInteractions(promotions);
    }
    @Test void usedOrRevokedGrantsDoNotInviteAnotherClaimAndExhaustionPreservesAvailableGrant(){
        var v=definition();v.put("issuance_limit",1);v.put("issued_count",1);voucher(v);
        assertThat(progress()).containsEntry("eligible",false).containsEntry("eligibilityReason","VOUCHER_EXHAUSTED");
        for(String status:List.of("USED","REVOKED")){
            when(db.one(contains("SELECT grant_id,status"),eq(7L),eq("BF20"))).thenReturn(values("status",status));
            assertThat(progress()).containsEntry("eligible",false);
            assertThat((Map<String,Object>)progress().get("voucher")).containsEntry("claimable",false).containsEntry("usable",false);
        }
        when(db.one(contains("SELECT grant_id,status"),eq(7L),eq("BF20"))).thenReturn(values("status","AVAILABLE"));
        assertThat(progress()).containsEntry("eligible",true);
        assertThat((Map<String,Object>)progress().get("voucher")).containsEntry("claimable",false).containsEntry("usable",true);
    }
    @Test void detailUsesVoucherEligibilityInsteadOfOptionalProEligibility(){
        var repository=mock(H4EventRuleRepository.class);var policies=mock(H4EventRuleService.class);var events=mock(AppGrowthEngagementMapper.class);
        var commerce=mock(H4EventCommerceService.class);var engine=new H4EventEngine(repository,policies,commerce,events,null,null,null,null,null);
        var now=LocalDateTime.now(ZoneOffset.UTC);
        var row=values("event_code",CODE,"kind","seasonal","enabled",1,"starts_at",now.minusDays(1),"ends_at",now.plusDays(1),"activated_at",now.minusDays(1));
        var source=values("eventCode",CODE,"userStatus","AVAILABLE");
        when(repository.row(startsWith("SELECT *,TIMESTAMPDIFF"),eq(7L))).thenReturn(values("id",7L));
        when(policies.find(CODE,false)).thenReturn(row);when(policies.rules(row)).thenReturn(rules);
        when(events.eventState(7L,"en")).thenReturn(List.of(source));
        for(boolean available:List.of(false,true)){
            var voucher=values("voucherId","BF20","usable",available);
            when(commerce.progress(eq(7L),eq(CODE),eq(rules),any(),any())).thenReturn(values("eligible",available,"voucher",voucher,"eligibilityReason",available?"READY":"VOUCHER_ALREADY_USED"));
            assertThat(engine.detail(7L,CODE,"en").getData()).containsEntry("eligible",available).containsEntry("voucher",voucher).containsEntry("eligibilityReason",available?"READY":"VOUCHER_ALREADY_USED");
        }
    }
}
