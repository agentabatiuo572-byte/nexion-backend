package ffdd.opsconsole.promotion;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.promotion.application.*;
import ffdd.opsconsole.promotion.domain.PromotionRules;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.canonical.AppCanonicalBoundaryService;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PromotionOrderDiscountTest {
    final PromotionContractValidator validator=new PromotionContractValidator(new ObjectMapper());
    static Map<String,Object> spec(String amount){return values("rewardRuleId","D1","beneficiaryRole","BUYER","type","DISCOUNT_USDT","calculation","FIXED","amount",amount);}
    static Map<String,Object> award(String product,String amount){return values("productNo",product,"beneficiaryRole","BUYER","reward",spec(amount));}
    static Map<String,Object> line(String product,String subtotal,String discount){return values("lineId",product,"productNo",product,"quantity",1,"unitPriceUsdt",subtotal,"subtotalUsdt",subtotal,"discountUsdt",discount,"payableUsdt",money(amount(subtotal).subtract(amount(discount))));}
    static Map<String,Object> price(List<Map<String,Object>> lines){BigDecimal sub=BigDecimal.ZERO,discount=BigDecimal.ZERO;for(var line:lines){sub=sub.add(amount(line.get("subtotalUsdt")));discount=discount.add(amount(line.get("discountUsdt")));}return values("items",lines,"subtotalUsdt",money(sub),"discountUsdt",money(discount),"amountUsdt",money(sub.subtract(discount)),"bundlePolicyVersion",1);}

    @Test void contractAcceptsPureBuyerDiscountButRejectsInviterWalletPolicyAndInvalidAmounts(){
        validator.validate("RewardSpec",spec("500.000000"));
        validator.validate("DraftRewardSpec",values("rewardRuleId","D1","beneficiaryRole","BUYER","type","DISCOUNT_USDT"));
        validator.validate("BudgetInput",values("asset","DISCOUNT_USDT","total","500"));
        for(String invalid:List.of("0","-1","1.0000001"))assertThrows(BizException.class,()->validator.validate("RewardSpec",spec(invalid)));
        var inviter=spec("500");inviter.put("beneficiaryRole","DIRECT_INVITER");assertThrows(BizException.class,()->validator.validate("RewardSpec",inviter));
        var wallet=spec("500");wallet.put("assetPolicy",values("policyId","P1","version",1,"contentHash","a".repeat(64)));assertThrows(BizException.class,()->validator.validate("RewardSpec",wallet));
    }
    @Test void singleItemDeductsImmediatelyAndDoesNotMutateBasePrices(){
        var base=price(List.of(line("PRO","1500.000000","0.000000")));
        var result=PromotionQuoteService.applyDiscounts(base,List.of(award("PRO","500")));
        assertEquals("1000.000000",result.get("amountUsdt"));assertEquals("500.000000",result.get("promotionDiscountUsdt"));
        assertEquals("1000.000000",maps(result.get("items")).get(0).get("payableUsdt"));assertEquals("1500.000000",base.get("amountUsdt"));
        validator.validate("AdminSimulationPricing",result);
    }
    @Test void multiItemDiscountStaysOnItsProductAndPreservesBundleAllocation(){
        var base=price(List.of(line("S1","100.000000","5.000000"),line("PRO","1500.000000","75.000000")));
        var result=PromotionQuoteService.applyDiscounts(base,List.of(award("PRO","250"),award("PRO","250")));
        assertEquals("1020.000000",result.get("amountUsdt"));assertEquals("580.000000",result.get("discountUsdt"));
        var lines=maps(result.get("items"));assertEquals("95.000000",lines.get(0).get("payableUsdt"));assertEquals("925.000000",lines.get(1).get("payableUsdt"));
        assertEquals("0.000000",lines.get(0).get("promotionDiscountUsdt"));assertEquals("500.000000",lines.get(1).get("promotionDiscountUsdt"));
    }
    @Test void cannotDiscountAnotherLineOrExceedRemainingPayable(){
        var base=price(List.of(line("S1","100.000000","5.000000"),line("PRO","1500.000000","75.000000")));
        assertEquals("PROMOTION_DISCOUNT_EXCEEDS_LINE_AMOUNT",assertThrows(BizException.class,()->PromotionQuoteService.applyDiscounts(base,List.of(award("S1","96")))).getMessage());
        assertEquals("PROMOTION_DISCOUNT_LINE_MISSING",assertThrows(BizException.class,()->PromotionQuoteService.applyDiscounts(base,List.of(award("MISSING","1")))).getMessage());
        var inviter=award("PRO","500");inviter.put("beneficiaryRole","DIRECT_INVITER");assertThrows(BizException.class,()->PromotionQuoteService.applyDiscounts(base,List.of(inviter)));
        assertEquals("PROMOTION_DISCOUNT_REQUIRES_PAYMENT",assertThrows(BizException.class,()->PromotionQuoteService.applyDiscounts(price(List.of(line("P","1.000000","0.000000"))),List.of(award("P","1")))).getMessage());
        var freeLine=PromotionQuoteService.applyDiscounts(base,List.of(award("S1","95")));
        assertEquals("0.000000",maps(freeLine.get("items")).get(0).get("payableUsdt"));
        assertEquals("1425.000000",freeLine.get("amountUsdt"));
        assertEquals("0.000000",PromotionQuoteService.applyDiscounts(price(List.of(line("P","1.000000","1.000000"))),List.of()).get("amountUsdt"));
    }
    @Test void existingCoinAndDeviceRewardsNeverReduceOrderPrice(){
        var base=price(List.of(line("PRO","1500.000000","0.000000")));
        var result=PromotionQuoteService.applyDiscounts(base,List.of(values("reward",values("type","USDT","amount","500")),values("reward",values("type","UVT","amount","10")),values("reward",values("type","DEVICE","quantity",1))));
        assertEquals("1500.000000",result.get("amountUsdt"));assertEquals("0.000000",result.get("promotionDiscountUsdt"));
    }
    @Test void realQuoteRequiresEligibilityAndBudgetAndFreezesDiscountSnapshot() throws Exception {
        var db=mock(PromotionMapper.class);var resolver=mock(PromotionPolicyResolver.class);var evaluator=mock(PromotionEvaluationService.class);
        when(db.deviceSlotCap()).thenReturn(6L);
        var canonical=mock(AppCanonicalBoundaryService.class);
        @SuppressWarnings("unchecked") ObjectProvider<AppCanonicalBoundaryService> provider=mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(canonical);
        when(canonical.purchaseEligibilityBatch(7L,List.of("PRO"))).thenReturn(ApiResult.ok(Map.of("PRO",new AppCanonicalBoundaryService.PurchaseEligibilityDecision("PRO",true,"ELIGIBLE"))));
        var contract=map(map(map(parse(Files.readString(Path.of("src/test/resources/growth-promotions/contracts.json"))).get("examples")).get("request_SimulateInput")).get("value"));
        contract=copy(map(contract.get("draft")));contract.put("startsAt",Instant.now().minusSeconds(60).toString());contract.put("endsAt",Instant.now().plusSeconds(3600).toString());
        var rules=maps(contract.get("rules"));var rule=copy(rules.get(0));rule.put("productNo","PRO");rule.put("buyerReward",spec("500"));rule.put("inviterReward",null);contract.put("rules",List.of(rule));
        contract.put("budgets",List.of(values("asset","DISCOUNT_USDT","total","500")));
        var policies=values("$quote",values("content",values("ttlSeconds",300)),"$stacking",values("content",values("voucher","ALLOW","bundleDiscount","ALLOW")),"$refund",values("content",values("terms",values("zh","按实付退款","en","Refund paid amount","vi","Hoàn số tiền thực trả"))));
        when(resolver.resolve(anyMap(),eq(false))).thenReturn(policies);
        when(db.product("PRO",false)).thenReturn(values("status","ACTIVE","store_visible",1,"price_usdt",new BigDecimal("1500")));
        when(db.list(startsWith("SELECT p.activity_id"),any(Object[].class))).thenReturn(List.of(values("activity_id","A1","active_version",1)));
        when(db.version("A1",1,false)).thenReturn(values("contract_json",json(contract)));
        when(db.activity("A1",false)).thenReturn(values("reserved_orders",0,"used_orders",0));
        when(db.one(contains("nx_promotion_budget"),any(Object[].class))).thenReturn(values("total",500,"reserved",0,"committed",0,"issued",0,"reversed",0));
        when(evaluator.evaluate(eq("A1"),anyMap(),anyMap(),eq(7L),anyList(),any(),isNull())).thenReturn(List.of(new PromotionRules.Award("PRO",text(rule.get("ruleId")),"D1",7L,"BUYER",0,spec("500"))));
        when(db.write(anyString(),any(Object[].class))).thenReturn(1);
        var voucherMapper=mock(ffdd.opsconsole.growth.mapper.AppGrowthLifecycleMapper.class);
        var vouchers=new ffdd.opsconsole.growth.application.AppGrowthLifecyclePublisher(voucherMapper,null,null);
        var quotes=new PromotionQuoteService(db,validator,resolver,evaluator,null,vouchers,provider);
        var request=values("activityId","A1","items",List.of(values("productNo","PRO","quantity",1)),"clientCapabilities",List.of("PROMOTION_QUOTE_V1","ORDER_ITEM_QUANTITIES_V1","PROMOTION_REWARDS_V1"));
        var result=quotes.quote(7L,request);assertEquals("1000.000000",result.get("amountUsdt"));assertEquals("500.000000",result.get("discountUsdt"));
        assertEquals("DISCOUNT_USDT",map(maps(result.get("expectedRewards")).get(0).get("reward")).get("type"));
        verify(db).write(startsWith("INSERT INTO nx_promotion_quote"),any(Object[].class));verify(db,never()).write(contains("nx_promotion_reward"),any(Object[].class));
        // Use the existing H7 percentage calculation before the promotion's fixed discount.
        when(voucherMapper.lockAvailableVoucher(eq(7L),eq("BF20"),eq("PRO"),anyLong())).thenReturn(
            new ffdd.opsconsole.growth.mapper.AppGrowthLifecycleMapper.VoucherRedemptionRow("G1","BF20","percent",BigDecimal.ZERO,new BigDecimal("20"),BigDecimal.ZERO,BigDecimal.ZERO));
        when(voucherMapper.voucherAllowsPromotionStacking("BF20")).thenReturn(true);
        request.put("voucherId","BF20");request.put("autoSelectPromotion",false);
        var stacked=quotes.quote(7L,request);
        assertEquals("700.000000",stacked.get("amountUsdt"));assertEquals("800.000000",stacked.get("discountUsdt"));
        assertEquals("500.000000",stacked.get("promotionDiscountUsdt"));
        var captured=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(db,times(2)).write(startsWith("INSERT INTO nx_promotion_quote"),captured.capture());
        var persisted=captured.getAllValues().get(1);
        var frozenContract=map(parse(persisted[4]).get("contract"));
        when(db.requiredRow(startsWith("SELECT * FROM nx_promotion_quote"),eq(stacked.get("quoteId"))))
            .thenReturn(values("user_id",7,"expires_at",Instant.now().plusSeconds(60),"quote_hash",persisted[3],"quote_json",persisted[4]));
        when(db.user(7L,true)).thenReturn(values("status","ACTIVE"));
        when(db.activity("A1",true)).thenReturn(values("status","ACTIVE","active_version",1,"reserved_orders",0,"used_orders",0));
        when(db.version("A1",1,true)).thenReturn(values("status","PUBLISHED","content_hash",hash(frozenContract)));
        when(db.product("PRO",true)).thenReturn(values("status","ACTIVE","store_visible",1,"price_usdt",new BigDecimal("1500"),"product_type","SERVER"));
        when(db.deviceSlotCap()).thenReturn(10L);
        when(evaluator.evaluateCurrent(eq("A1"),anyMap(),anyMap(),eq(7L),anyList(),any(),isNull()))
            .thenReturn(List.of(new PromotionRules.Award("PRO",text(rule.get("ruleId")),"D1",7L,"BUYER",0,spec("500"))));
        var orders=new PromotionOrderService(db,quotes,evaluator,resolver,null,null);
        var selectedItems=List.of(new PromotionOrderService.Selection("PRO",1));
        assertEquals(new BigDecimal("700.000000"),orders.prepareCreate(7L,text(stacked.get("quoteId")),selectedItems,"BF20",new BigDecimal("700")).price("amountUsdt"));
        when(voucherMapper.voucherAllowsPromotionStacking("BF20")).thenReturn(false);
        assertEquals("VOUCHER_PROMOTION_STACKING_NOT_ALLOWED",assertThrows(BizException.class,()->orders.prepareCreate(7L,text(stacked.get("quoteId")),selectedItems,"BF20",null)).getMessage());
        when(voucherMapper.voucherAllowsPromotionStacking("BF20")).thenReturn(true);
        when(voucherMapper.lockAvailableVoucher(eq(7L),eq("BF20"),eq("PRO"),anyLong())).thenReturn(null);
        assertEquals("VOUCHER_NOT_AVAILABLE_OR_NOT_APPLICABLE",assertThrows(BizException.class,()->orders.prepareCreate(7L,text(stacked.get("quoteId")),selectedItems,"BF20",null)).getMessage());
        when(voucherMapper.lockAvailableVoucher(eq(7L),eq("BF20"),eq("PRO"),anyLong())).thenReturn(
            new ffdd.opsconsole.growth.mapper.AppGrowthLifecycleMapper.VoucherRedemptionRow("G1","BF20","percent",BigDecimal.ZERO,new BigDecimal("15"),BigDecimal.ZERO,BigDecimal.ZERO));
        assertEquals("PROMOTION_QUOTE_PRICE_CHANGED",assertThrows(BizException.class,()->orders.prepareCreate(7L,text(stacked.get("quoteId")),selectedItems,"BF20",null)).getMessage());
        when(voucherMapper.lockAvailableVoucher(eq(7L),eq("BF20"),eq("PRO"),anyLong())).thenReturn(
            new ffdd.opsconsole.growth.mapper.AppGrowthLifecycleMapper.VoucherRedemptionRow("G1","BF20","percent",BigDecimal.ZERO,new BigDecimal("20"),BigDecimal.ZERO,BigDecimal.ZERO));
        var plain=copy(request);plain.remove("activityId");
        var voucherOnly=quotes.quote(7L,plain);
        assertEquals("ELIGIBLE",voucherOnly.get("eligibility"));
        assertEquals("1200.000000",voucherOnly.get("amountUsdt"));assertNull(voucherOnly.get("activityId"));assertTrue(maps(voucherOnly.get("expectedRewards")).isEmpty());
        plain.remove("autoSelectPromotion");
        assertEquals("700.000000",quotes.quote(7L,plain).get("amountUsdt")); // Existing automatic selection remains the default.
        when(voucherMapper.voucherAllowsPromotionStacking("BF20")).thenReturn(false);
        assertEquals("PROMOTION_NOT_ELIGIBLE_OR_UNAVAILABLE",assertThrows(BizException.class,()->quotes.quote(7L,request)).getMessage());
        assertEquals("VOUCHER_PROMOTION_STACKING_NOT_ALLOWED",assertThrows(BizException.class,()->quotes.requireVoucherPromotionStacking("BF20")).getMessage());
        when(voucherMapper.voucherAllowsPromotionStacking("BF20")).thenReturn(true);
        ((Map<String,Object>)((Map<String,Object>)policies.get("$stacking")).get("content")).put("voucher","DENY");
        assertEquals("PROMOTION_NOT_ELIGIBLE_OR_UNAVAILABLE",assertThrows(BizException.class,()->quotes.quote(7L,request)).getMessage());
        ((Map<String,Object>)((Map<String,Object>)policies.get("$stacking")).get("content")).put("voucher","ALLOW");
        for(String invalid:List.of("FORGED","EXPIRED","USED","FOREIGN")){
            var bad=copy(plain);bad.put("voucherId",invalid);
            assertEquals("VOUCHER_NOT_AVAILABLE_OR_NOT_APPLICABLE",assertThrows(BizException.class,()->quotes.quote(7L,bad)).getMessage());
        }
        when(db.product("S1",false)).thenReturn(values("status","ACTIVE","store_visible",1,"price_usdt",new BigDecimal("100")));
        when(canonical.purchaseEligibilityBatch(7L,List.of("S1"))).thenReturn(ApiResult.ok(Map.of("S1",new AppCanonicalBoundaryService.PurchaseEligibilityDecision("S1",true,"ELIGIBLE"))));
        when(voucherMapper.lockAvailableVoucher(eq(7L),eq("BF20"),eq("S1"),anyLong())).thenReturn(
            new ffdd.opsconsole.growth.mapper.AppGrowthLifecycleMapper.VoucherRedemptionRow("G1","BF20","percent",BigDecimal.ZERO,new BigDecimal("20"),BigDecimal.ZERO,BigDecimal.ZERO));
        var ordinarySku=values("items",List.of(values("productNo","S1","quantity",1)),"voucherId","BF20","autoSelectPromotion",false,"clientCapabilities",request.get("clientCapabilities"));
        var ordinaryQuote=quotes.quote(7L,ordinarySku);
        assertEquals("ELIGIBLE",ordinaryQuote.get("eligibility"));assertEquals("0.000000",ordinaryQuote.get("promotionDiscountUsdt"));
        Files.writeString(Path.of("target/black-friday-voucher-quote-fixture.json"),json(ordinaryQuote));
        assertEquals("80.000000",ordinaryQuote.get("amountUsdt"));assertNull(ordinaryQuote.get("activityId"));assertTrue(maps(ordinaryQuote.get("expectedRewards")).isEmpty());
        assertEquals("BUNDLE_VOUCHER_UNSUPPORTED",assertThrows(BizException.class,()->quotes.prices(7L,List.of(new PromotionRules.Selection("PRO",1),new PromotionRules.Selection("S1",1)),"BF20")).getMessage());
        when(db.one(contains("nx_promotion_budget"),any(Object[].class))).thenReturn(values("total",499,"reserved",0,"committed",0,"issued",0,"reversed",0));
        assertEquals("PROMOTION_NOT_ELIGIBLE_OR_UNAVAILABLE",assertThrows(BizException.class,()->quotes.quote(7L,request)).getMessage());
    }
}
