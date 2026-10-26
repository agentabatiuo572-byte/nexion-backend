package ffdd.opsconsole.promotion.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.application.AppGrowthLifecyclePublisher;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.canonical.AppCanonicalBoundaryService;
import ffdd.opsconsole.shared.canonical.AppCanonicalBoundaryService.PurchaseEligibilityDecision;
import ffdd.opsconsole.shared.canonical.BundleDiscountPolicy;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrdinaryPurchaseQuoteTest {
    final PromotionMapper db=mock(PromotionMapper.class);
    final AppCanonicalBoundaryService canonical=mock(AppCanonicalBoundaryService.class);
    final AppGrowthLifecyclePublisher vouchers=mock(AppGrowthLifecyclePublisher.class);
    final PlatformConfigFacade config=mock(PlatformConfigFacade.class);
    final PromotionPolicyResolver resolver=mock(PromotionPolicyResolver.class);
    final PromotionEvaluationService evaluator=mock(PromotionEvaluationService.class);
    @SuppressWarnings("unchecked") final ObjectProvider<AppCanonicalBoundaryService> provider=mock(ObjectProvider.class);
    final PromotionQuoteService quotes=new PromotionQuoteService(db,new PromotionContractValidator(new ObjectMapper()),resolver,evaluator,config,vouchers,provider);

    OrdinaryPurchaseQuoteTest() {
        when(provider.getObject()).thenReturn(canonical);
        when(canonical.purchaseEligibilityBatch(eq(29L),anyList())).thenAnswer(call->{
            Map<String,PurchaseEligibilityDecision> decisions=new LinkedHashMap<>();
            for(String sku:call.<List<String>>getArgument(1))decisions.put(sku,new PurchaseEligibilityDecision(sku,true,"ELIGIBLE"));
            return ApiResult.ok(decisions);
        });
        when(db.product(anyString(),eq(false))).thenReturn(values("status","ACTIVE","store_visible",1,"price_usdt",new BigDecimal("19.90")));
        when(db.write(startsWith("INSERT INTO nx_promotion_quote"),any(Object[].class))).thenReturn(1);
    }

    Map<String,Object> request(String sku,int quantity) {
        return values("items",List.of(values("productNo",sku,"quantity",quantity)),
            "clientCapabilities",List.of("PROMOTION_QUOTE_V1","ORDER_ITEM_QUANTITIES_V1","PROMOTION_REWARDS_V1"));
    }

    @ParameterizedTest @CsvSource({"cloud-share,1,19.900000","cloud-share,2,39.800000","cloud-share,100,1990.000000","stellarbox-s1,2,39.800000"})
    void ordinaryCanonicalPurchaseDoesNotRequirePromotionOrVoucher(String sku,int quantity,String amount) {
        var result=quotes.quote(29L,request(sku,quantity));
        assertEquals("ELIGIBLE",result.get("eligibility"));
        assertEquals(amount,result.get("amountUsdt"));
        assertEquals("0.000000",result.get("discountUsdt"));
        assertEquals("0.000000",result.get("promotionDiscountUsdt"));
        assertEquals(quantity,result.get("quantity"));assertEquals(1,result.get("itemCount"));
        assertNull(result.get("activityId"));assertNull(result.get("activityVersion"));
        assertEquals(List.of(),result.get("expectedRewards"));assertEquals(false,result.get("reserved"));
        verify(canonical).purchaseEligibilityBatch(29L,List.of(sku));
        verify(db).write(startsWith("INSERT INTO nx_promotion_quote"),any(Object[].class));
        verify(db,never()).write(contains("nx_order"),any(Object[].class));
        verify(db,never()).write(contains("nx_promotion_reservation"),any(Object[].class));
        verifyNoInteractions(vouchers);
    }

    @Test void ordinaryMultiSkuQuoteKeepsServerBundlePricingWithoutInventingRewards() {
        when(config.activeValue(BundleDiscountPolicy.TWO_ITEMS_KEY)).thenReturn(Optional.of("0.05"));
        when(config.activeValue(BundleDiscountPolicy.THREE_ITEMS_KEY)).thenReturn(Optional.of("0.08"));
        when(config.activeValue(BundleDiscountPolicy.FOUR_PLUS_ITEMS_KEY)).thenReturn(Optional.of("0.12"));
        when(config.activeValue(BundleDiscountPolicy.VERSION_KEY)).thenReturn(Optional.of("7"));
        var input=request("cloud-share",2);
        input.put("items",List.of(values("productNo","cloud-share","quantity",2),values("productNo","stellarbox-s1","quantity",1)));
        var result=quotes.quote(29L,input);
        assertEquals("ELIGIBLE",result.get("eligibility"));
        assertEquals("59.700000",result.get("subtotalUsdt"));assertEquals("2.985000",result.get("discountUsdt"));
        assertEquals("56.715000",result.get("amountUsdt"));assertEquals(7L,number(result.get("bundlePolicyVersion")));
        assertEquals(List.of(),result.get("expectedRewards"));assertEquals(false,result.get("reserved"));
    }

    @Test void explicitPromotionOptOutAllowsAnOrdinaryQuote() {
        var input=request("cloud-share",2);input.put("autoSelectPromotion",false);
        assertEquals("ELIGIBLE",quotes.quote(29L,input).get("eligibility"));
    }

    @ParameterizedTest @ValueSource(strings={"missing-activity","ended-activity"})
    void explicitActivityAbsentFromCurrentPublishedCandidatesCannotBecomeOrdinaryPurchase(String activity) {
        var input=request("cloud-share",2);input.put("activityId",activity);
        var failure=assertThrows(BizException.class,()->quotes.quote(29L,input));
        assertEquals("PROMOTION_NOT_ELIGIBLE_OR_UNAVAILABLE",failure.getMessage());
        verify(db,never()).write(anyString(),any(Object[].class));
    }

    @Test void unmatchedAutomaticActivityAllowsOrdinaryPurchaseButExplicitActivityStillRejects() throws Exception {
        var example=map(map(map(parse(Files.readString(Path.of("src/test/resources/growth-promotions/contracts.json"))).get("examples")).get("request_SimulateInput")).get("value"));
        var contract=copy(map(example.get("draft")));
        contract.put("startsAt",Instant.now().minusSeconds(60).toString());contract.put("endsAt",Instant.now().plusSeconds(3600).toString());
        when(db.list(startsWith("SELECT p.activity_id"),any(Object[].class))).thenReturn(List.of(values("activity_id","A1","active_version",1)));
        when(db.version("A1",1,false)).thenReturn(values("contract_json",json(contract)));
        when(resolver.resolve(anyMap(),eq(false))).thenReturn(Map.of());
        when(evaluator.evaluate(eq("A1"),anyMap(),anyMap(),eq(29L),anyList(),any(),isNull())).thenReturn(List.of());
        var input=request("cloud-share",2);input.put("activityId","A1");
        assertEquals("PROMOTION_NOT_ELIGIBLE_OR_UNAVAILABLE",assertThrows(BizException.class,()->quotes.quote(29L,input)).getMessage());
        verify(db,never()).write(anyString(),any(Object[].class));
        input.remove("activityId");
        var ordinary=quotes.quote(29L,input);
        assertEquals("ELIGIBLE",ordinary.get("eligibility"));assertNull(ordinary.get("activityId"));
        assertEquals(List.of(),ordinary.get("expectedRewards"));assertEquals("39.800000",ordinary.get("amountUsdt"));
    }

    @Test void validVoucherWithoutMatchingActivityRemainsUsableInAutomaticMode() {
        when(vouchers.prepareVoucher(29L,"valid","cloud-share",new BigDecimal("39.800000"))).thenReturn(new AppGrowthLifecyclePublisher.VoucherRedemption("grant","valid",new BigDecimal("5.000000")));
        var input=request("cloud-share",2);input.put("voucherId","valid");
        var result=quotes.quote(29L,input);
        assertEquals("ELIGIBLE",result.get("eligibility"));assertEquals("34.800000",result.get("amountUsdt"));
        assertEquals(List.of(),result.get("expectedRewards"));assertNull(result.get("activityId"));
    }

    @Test void ordinaryQuoteCanPrepareCreationWhileOwnershipExpiryQuantityAndPriceChecksRemain() {
        var result=quotes.quote(29L,request("cloud-share",2));
        assertEquals("ELIGIBLE",result.get("eligibility"));
        var captured=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(db).write(startsWith("INSERT INTO nx_promotion_quote"),captured.capture());
        var persisted=captured.getValue();
        var row=values("user_id",29L,"expires_at",Instant.now().plusSeconds(60),"quote_hash",persisted[3],"quote_json",persisted[4]);
        when(db.requiredRow(startsWith("SELECT * FROM nx_promotion_quote"),eq(result.get("quoteId")))).thenReturn(row);
        when(db.user(29L,true)).thenReturn(values("status","ACTIVE"));
        when(db.product("cloud-share",true)).thenReturn(values("status","ACTIVE","store_visible",1,"price_usdt",new BigDecimal("19.90"),"product_type","SHARE"));
        var orders=new PromotionOrderService(db,quotes,evaluator,resolver,null,null);
        var selected=List.of(new PromotionOrderService.Selection("cloud-share",2));
        String quoteId=text(result.get("quoteId"));
        assertEquals(new BigDecimal("39.800000"),orders.prepareCreate(29L,quoteId,selected,null,new BigDecimal("39.80")).price("amountUsdt"));
        assertEquals("PROMOTION_QUOTE_OWNER_MISMATCH",assertThrows(BizException.class,()->orders.prepareCreate(30L,quoteId,selected,null,null)).getMessage());
        assertEquals("PROMOTION_QUOTE_INPUT_CHANGED",assertThrows(BizException.class,()->orders.prepareCreate(29L,quoteId,List.of(new PromotionOrderService.Selection("cloud-share",1)),null,null)).getMessage());
        assertEquals("PROMOTION_QUOTE_AMOUNT_CHANGED",assertThrows(BizException.class,()->orders.prepareCreate(29L,quoteId,selected,null,BigDecimal.ONE)).getMessage());
        row.put("expires_at",Instant.now().minusSeconds(1));
        assertEquals("PROMOTION_QUOTE_EXPIRED",assertThrows(BizException.class,()->orders.prepareCreate(29L,quoteId,selected,null,null)).getMessage());
        row.put("expires_at",Instant.now().plusSeconds(60));
        when(db.product("cloud-share",true)).thenReturn(values("status","ACTIVE","store_visible",1,"price_usdt",new BigDecimal("20"),"product_type","SHARE"));
        assertEquals("PROMOTION_QUOTE_PRICE_CHANGED",assertThrows(BizException.class,()->orders.prepareCreate(29L,quoteId,selected,null,null)).getMessage());
        verify(db,never()).write(contains("nx_order"),any(Object[].class));
    }

    @ParameterizedTest @ValueSource(strings={"F4B_REQUIREMENTS_NOT_MET","F4B_MONTHLY_QUOTA_EXHAUSTED","PURCHASE_GATE_NOT_MET","PURCHASE_GATE_SOLD_OUT","PRODUCT_NOT_RELEASED"})
    void canonicalRejectionStillPrecedesPricingAndQuotePersistence(String decisionCode) {
        when(canonical.purchaseEligibilityBatch(29L,List.of("cloud-share"))).thenReturn(ApiResult.ok(Map.of("cloud-share",new PurchaseEligibilityDecision("cloud-share",false,decisionCode))));
        var failure=assertThrows(BizException.class,()->quotes.quote(29L,request("cloud-share",2)));
        assertEquals(409,failure.getCode());assertEquals(decisionCode,failure.getMessage());
        verify(db,never()).product(anyString(),anyBoolean());verify(db,never()).write(anyString(),any(Object[].class));
    }

    @ParameterizedTest @ValueSource(strings={"forged","expired","used","foreign"})
    void explicitInvalidVoucherCannotFallBackToOrdinaryPrice(String voucherId) {
        when(vouchers.prepareVoucher(eq(29L),eq(voucherId),eq("cloud-share"),any())).thenThrow(new BizException(409,"VOUCHER_NOT_AVAILABLE_OR_NOT_APPLICABLE"));
        var input=request("cloud-share",2);input.put("voucherId",voucherId);
        assertEquals("VOUCHER_NOT_AVAILABLE_OR_NOT_APPLICABLE",assertThrows(BizException.class,()->quotes.quote(29L,input)).getMessage());
        verify(db,never()).write(anyString(),any(Object[].class));
    }

    @Test void unavailableProductAndExcessQuantityRemainRejected() {
        when(db.product("cloud-share",false)).thenReturn(values("status","INACTIVE","store_visible",1,"price_usdt",new BigDecimal("19.90")));
        assertEquals("PRODUCT_NOT_AVAILABLE",assertThrows(BizException.class,()->quotes.quote(29L,request("cloud-share",2))).getMessage());
        assertThrows(BizException.class,()->quotes.quote(29L,request("cloud-share",101)));
        verify(db,never()).write(anyString(),any(Object[].class));
    }
}
