package ffdd.opsconsole.promotion.application;

import static org.junit.jupiter.api.Assertions.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class PromotionGovernanceTest {
    private final PromotionContractValidator validator = new PromotionContractValidator(new ObjectMapper());
    @Test void incompleteDraftIsPersistableButNotPublishable() {
        var draft=values("category","PROMOTION","template","SKU_GIFT","rules",List.of(),"budgets",List.of());
        assertDoesNotThrow(()->validator.validate("Draft",draft));
        assertThrows(RuntimeException.class,()->validator.validate("PromotionContract",draft));
    }
    @Test void changingTemplateRequiresExplicitResolutionOfExistingFields() {
        var prior=values("category","PROMOTION","template","SKU_GIFT","rules",List.of(values("ruleId","old")),"buyerAudience",values("purchaseHistory","ANY"));
        assertThrows(RuntimeException.class,()->PromotionAdminService.mergeDraft(prior,values("template","FIRST_PURCHASE")));
        var next=PromotionAdminService.mergeDraft(prior,values("template","FIRST_PURCHASE","rules",List.of(),"buyerAudience",null));
        assertEquals("FIRST_PURCHASE",next.get("template"));
        assertNull(next.get("buyerAudience"));
        assertEquals("SKU_GIFT",prior.get("template"));
    }
    @Test void partialWritePreservesOtherTopLevelFieldsAndReplacesObjects() {
        var prior=values("category","PROMOTION","template","SKU_GIFT","title",values("zh","旧","en","old"));
        var next=PromotionAdminService.mergeDraft(prior,values("title",values("zh","新")));
        assertEquals(Map.of("zh","新"),next.get("title"));
        assertEquals("SKU_GIFT",next.get("template"));
    }
    @Test void timeWindowsNeverReceiveInventedDates() {
        assertDoesNotThrow(()->PromotionAdminService.validateWindow(values("startsAt",null,"endsAt",null)));
        assertThrows(RuntimeException.class,()->PromotionAdminService.validateWindow(values("startsAt","2026-10-07T00:00:00Z")));
        assertThrows(RuntimeException.class,()->PromotionAdminService.validateWindow(values("startsAt","2026-10-08T00:00:00Z","endsAt","2026-10-07T00:00:00Z","displayTimezone","UTC")));
    }
    @Test void pausedPublicationStaysPaused() {
        assertEquals("PAUSED",PromotionAdminService.publicationState("PAUSED",java.time.Instant.now().minusSeconds(1)));
        assertEquals("SCHEDULED",PromotionAdminService.publicationState("DRAFT",java.time.Instant.now().plusSeconds(30)));
    }
    @Test void giftEligibilityRejectsShareUsingEveryCanonicalIdentity() {
        var product=values("product_no","box-s1","product_type","DEVICE","status","ACTIVE","vram_total_gb",8);
        var sku=values("name","StellarBox S1","power_text","100W","datacenter","dc");
        assertNull(PromotionNativeContractResolver.giftFailure(product,sku));
        product.put("device_type","SHARE");assertNotNull(PromotionNativeContractResolver.giftFailure(product,sku));
        product.remove("device_type");sku.put("name","Cloud Share");assertNotNull(PromotionNativeContractResolver.giftFailure(product,sku));
        sku.put("name","StellarBox S1");product.put("product_no","cloud-share");assertNotNull(PromotionNativeContractResolver.giftFailure(product,sku));
    }
    @Test void nativeCatalogOnlyReturnsReferencesAndExplainsUnresolvableContracts() {
        var nativeResolver=new PromotionNativeContractResolver(org.mockito.Mockito.mock(ffdd.opsconsole.promotion.mapper.PromotionMapper.class),org.mockito.Mockito.mock(ffdd.opsconsole.platform.facade.PlatformConfigFacade.class),null,null,new org.springframework.mock.env.MockEnvironment());
        org.springframework.test.util.ReflectionTestUtils.setField(nativeResolver,"orderTtlMinutes",30);
        var title=values("zh","订单","en","Orders","vi","Đơn hàng");
        var available=nativeResolver.catalogOption("ORDER_CONTRACT","WALLET_ORDER_V1",title,null);
        assertEquals(true,available.get("available"));assertNull(available.get("unavailabilityReason"));assertEquals(Set.of("system","resourceId","revision","contentHash"),map(available.get("reference")).keySet());
        validator.validate("NativeContractOption",available);
        var unavailable=nativeResolver.catalogOption("EARNINGS_RELEASE","risk.k1.release.version",title,null);
        assertEquals(false,unavailable.get("available"));assertNull(unavailable.get("reference"));assertNotNull(unavailable.get("unavailabilityReason"));validator.validate("NativeContractOption",unavailable);
    }
    @Test void completeConfigurationRejectsContradictoryWindowsAndMissingRewardBudget() {
        var audience=values("registrationAge",values("minDays",5,"maxDays",1),"lastValidPurchaseAge",values("minDays",null,"maxDays",null),"purchaseHistory","ANY");
        var contract=values("template","SKU_GIFT","buyerAudience",audience,"rules",List.of(),"budgets",List.of());
        assertThrows(RuntimeException.class,()->PromotionAdminService.validateConfiguration(contract));
        audience.put("registrationAge",values("minDays",null,"maxDays",null));
        contract.put("rules",List.of(values("buyerReward",values("type","NEX","amount","1.000000"))));
        assertThrows(RuntimeException.class,()->PromotionAdminService.validateConfiguration(contract));
        contract.put("budgets",List.of(values("asset","NEX","total","10.000000")));
        assertDoesNotThrow(()->PromotionAdminService.validateConfiguration(contract));
        contract.put("template","REPURCHASE");assertThrows(RuntimeException.class,()->PromotionAdminService.validateConfiguration(contract));
    }
}
