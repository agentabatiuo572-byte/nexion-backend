package ffdd.opsconsole.promotion.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.promotion.domain.PromotionRules;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PromotionAdminEvaluationTest {
    @Test void refundedButUnrecoveredFirstPurchaseRemainsRejectedInOperatorDiagnostics() throws Exception{
        var c=contract();c.put("template","FIRST_PURCHASE");var a=map(c.get("buyerAudience"));a.put("devicePresence","ANY");
        when(resolver.resolveAudience(anyMap())).thenReturn(values("$firstPurchase",values("content",values("restoreAfterRefund",true))));
        when(db.list(anyString())).thenReturn(List.of(values("id",1)));
        when(evaluator.account(eq(1L),anyMap(),anyMap(),isNull())).thenReturn(new PromotionRules.Account(1,Instant.now().minusSeconds(86400),"V0","",null,true,1,0,null,0,1));
        var result=service.audience(c);
        assertEquals(0,number(result.get("matched")));
        assertTrue(maps(result.get("reasonCounts")).stream().anyMatch(r->"FIRST_PURCHASE_ALREADY_USED".equals(r.get("code"))));
    }
    final PromotionMapper db=mock(PromotionMapper.class);
    final PromotionPolicyResolver resolver=mock(PromotionPolicyResolver.class);
    final PromotionEvaluationService evaluator=mock(PromotionEvaluationService.class);
    final PromotionQuoteService quotes=mock(PromotionQuoteService.class);
    final PromotionContractValidator validator=new PromotionContractValidator(new ObjectMapper());
    final PromotionAdminEvaluation service=new PromotionAdminEvaluation(db,validator,resolver,evaluator,quotes);
    Map<String,Object> contract() throws Exception{return map(map(map(parse(Files.readString(Path.of("src/test/resources/growth-promotions/contracts.json"))).get("examples")).get("request_SimulateInput")).get("value")).get("draft") instanceof Map<?,?> c?map(c):Map.of();}
    @Test void simulationRejectsUnmetStorefrontPurchaseConditionsBeforeAwardEvaluation() throws Exception {
        var c=contract();when(resolver.resolve(anyMap(),eq(false))).thenReturn(Map.of());
        when(db.product(anyString(),eq(false))).thenReturn(values("inventory_mode","UNLIMITED"));
        doThrow(new BizException(409,"F4B_REQUIREMENTS_NOT_MET")).when(quotes).requirePurchaseEligibility(eq(29L),anyList());
        var result=service.simulate("A1",values("draft_version",1,"reserved_orders",0,"used_orders",0),c,values("sampleAccountId","29","items",List.of(values("productNo","SKU1","quantity",1))));
        assertEquals("INELIGIBLE",result.get("eligibility"));assertTrue(maps(result.get("rewardUnits")).isEmpty());
        var failure=maps(result.get("blockers")).stream().filter(r->"F4B_REQUIREMENTS_NOT_MET".equals(r.get("code"))).findFirst().orElseThrow();
        assertTrue(text(map(failure.get("message")).get("zh")).contains("购买条件尚未满足"));
        verifyNoInteractions(evaluator);verify(db,never()).write(anyString(),any());
    }
    @Test void simulationNeverAssumesPurchaseEligibilityWithoutSampleOrReadableFacts() throws Exception {
        var c=contract();when(resolver.resolve(anyMap(),eq(false))).thenReturn(Map.of());
        when(db.product(anyString(),eq(false))).thenReturn(values("inventory_mode","UNLIMITED"));
        var items=List.of(values("productNo","SKU1","quantity",1));
        var anonymous=service.simulate("A1",values("draft_version",1,"reserved_orders",0,"used_orders",0),c,values("items",items));
        assertEquals("UNKNOWN",anonymous.get("eligibility"));verify(quotes,never()).requirePurchaseEligibility(anyLong(),anyList());
        doThrow(new BizException(503,"F4B_QUOTA_UNAVAILABLE")).when(quotes).requirePurchaseEligibility(eq(29L),anyList());
        var invalidQuota=service.simulate("A1",values("draft_version",1,"reserved_orders",0,"used_orders",0),c,values("sampleAccountId","29","items",items));
        assertEquals("UNKNOWN",invalidQuota.get("eligibility"));assertEquals("UNAVAILABLE",invalidQuota.get("completeness"));
        doThrow(new DataAccessResourceFailureException("offline")).when(quotes).requirePurchaseEligibility(eq(29L),anyList());
        var unavailable=service.simulate("A1",values("draft_version",1,"reserved_orders",0,"used_orders",0),c,values("sampleAccountId","29","items",items));
        assertEquals("UNKNOWN",unavailable.get("eligibility"));assertTrue(maps(unavailable.get("blockers")).stream().anyMatch(r->"PURCHASE_ELIGIBILITY_UNAVAILABLE".equals(r.get("code"))));
        verifyNoInteractions(evaluator);
    }
    @Test void incompleteStepTwoCountsMatchRejectAndUnknownSeparately() throws Exception {
        var c=contract();var a=copy(map(c.get("buyerAudience")));a.put("purchaseHistory","NEVER_PAID");a.put("rankIds",List.of());a.put("markets",List.of());a.put("devicePresence","ANY");a.put("sponsor","ANY");a.put("registrationAge",values("minDays",null,"maxDays",null));a.put("lastValidPurchaseAge",values("minDays",null,"maxDays",null));
        var draft=values("category","PROMOTION","template","SKU_GIFT","buyerAudience",a);
        when(resolver.resolveAudience(anyMap())).thenReturn(Map.of());when(db.list(anyString())).thenReturn(List.of(values("id",1),values("id",2),values("id",3)));
        when(evaluator.account(eq(1L),anyMap(),anyMap(),isNull())).thenReturn(new PromotionRules.Account(1,Instant.now().minusSeconds(86400),"V0","",null,true,0,0,null,0));
        when(evaluator.account(eq(2L),anyMap(),anyMap(),isNull())).thenReturn(new PromotionRules.Account(2,Instant.now().minusSeconds(86400),"V0","",null,true,1,1,Instant.now(),0));
        when(evaluator.account(eq(3L),anyMap(),anyMap(),isNull())).thenThrow(new DataAccessResourceFailureException("device/history source unavailable"));
        var result=service.audience(draft);validator.validate("AudiencePreview",result);
        assertEquals(1,number(result.get("matched")));assertEquals(1,number(result.get("rejected")));assertEquals(1,number(result.get("unknown")));assertEquals(3,number(result.get("total")));assertNull(result.get("estimatedAccounts"));assertEquals("UNAVAILABLE",result.get("completeness"));
        assertTrue(maps(result.get("reasonCounts")).stream().anyMatch(r->"AUDIENCE_PURCHASEHISTORY".equals(r.get("code"))));
        verify(resolver,never()).resolve(anyMap(),anyBoolean());verify(db,never()).write(anyString(),any());
    }
    @Test void unreadableAccountSourceIsNotEmptyAudience() throws Exception {
        when(resolver.resolveAudience(anyMap())).thenReturn(Map.of());when(db.list(anyString())).thenThrow(new DataAccessResourceFailureException("offline"));
        var result=service.audience(contract());validator.validate("AudiencePreview",result);assertNull(result.get("total"));assertNull(result.get("rejected"));assertEquals("UNAVAILABLE",result.get("completeness"));
    }
    @Test void unavailablePolicyNeverBecomesNoDeviceRejection() throws Exception {
        when(resolver.resolveAudience(anyMap())).thenThrow(new BizException(409,"NOT_APPROVED"));when(db.list(anyString())).thenReturn(List.of(values("id",1)));
        var result=service.audience(contract());assertEquals(1,number(result.get("unknown")));assertEquals(0,number(result.get("rejected")));verifyNoInteractions(evaluator);
    }
    @Test void aggregateMoneyMayExceedSingleEntryWhileSingleEntryStaysBounded(){
        var budget=values("asset","USDT","productNo",null,"total","600000000000.000000","available","600000000000.000000","reserved","0.000000","committed","0.000000","issued","0.000000","reversed","0.000000","unrecoverable","0.000000");validator.validate("Budget",budget);
        var impact=values("reserved",0,"issued",0,"unresolved",0,"unpaidOrders",0,"unfulfilledRewards",0,"budgets",List.of(budget));var row=values("state","ACTIVE","impact",impact);
        var summary=PromotionMetricsService.summarizeActivities(List.of(row,row),Instant.now());validator.validate("PromotionListSummary",summary);assertEquals("1200000000000.000000",maps(summary.get("budgets")).get(0).get("total"));assertThrows(BizException.class,()->validator.validate("Amount","1200000000000.000000"));validator.validate("AggregateAmount","1200000000000.000000");
    }
}
