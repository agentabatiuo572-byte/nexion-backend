package ffdd.opsconsole.promotion.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.promotion.domain.PromotionRules.Selection;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.canonical.AppCanonicalBoundaryService;
import ffdd.opsconsole.shared.canonical.AppCanonicalBoundaryService.PurchaseEligibilityDecision;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.dao.DataAccessResourceFailureException;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PromotionPurchaseEligibilityTest {
    final PromotionMapper db=mock(PromotionMapper.class);
    final AppCanonicalBoundaryService canonical=mock(AppCanonicalBoundaryService.class);
    @SuppressWarnings("unchecked") final ObjectProvider<AppCanonicalBoundaryService> provider=mock(ObjectProvider.class);
    final PromotionQuoteService quotes=new PromotionQuoteService(db,new PromotionContractValidator(new ObjectMapper()),null,null,null,null,provider);
    final List<Selection> items=List.of(new Selection("s1",1),new Selection("cloudshare",1),new Selection("stellarbox-pro",1));
    final List<String> products=List.of("s1","cloudshare","stellarbox-pro");

    PromotionPurchaseEligibilityTest(){when(provider.getObject()).thenReturn(canonical);}
    Map<String,PurchaseEligibilityDecision> decisions(){
        var result=new LinkedHashMap<String,PurchaseEligibilityDecision>();
        for(String product:products)result.put(product,new PurchaseEligibilityDecision(product,true,"ELIGIBLE"));
        return result;
    }
    @Test void quoteRejectsAnySelectedSkuBeforePricingOrPersistence(){
        var decisions=decisions();decisions.put("stellarbox-pro",new PurchaseEligibilityDecision("stellarbox-pro",false,"F4B_REQUIREMENTS_NOT_MET"));
        when(canonical.purchaseEligibilityBatch(29L,products)).thenReturn(ApiResult.ok(decisions));
        var request=values("items",items.stream().map(i->values("productNo",i.productNo(),"quantity",i.quantity())).toList(),
            "clientCapabilities",List.of("PROMOTION_QUOTE_V1","ORDER_ITEM_QUANTITIES_V1","PROMOTION_REWARDS_V1"));
        var failure=assertThrows(BizException.class,()->quotes.quote(29L,request));
        assertEquals(409,failure.getCode());assertEquals("F4B_REQUIREMENTS_NOT_MET",failure.getMessage());
        verify(canonical).purchaseEligibilityBatch(29L,products);
        verify(db,never()).product(anyString(),anyBoolean());verify(db,never()).write(anyString(),any());
    }
    @Test void ordinaryHardwareAndShareUseCanonicalAllowanceWithoutInventingQuota(){
        when(canonical.purchaseEligibilityBatch(29L,products.subList(0,2))).thenReturn(ApiResult.ok(decisions()));
        assertDoesNotThrow(()->quotes.requirePurchaseEligibility(29L,items.subList(0,2)));
        verifyNoInteractions(db);
    }
    @Test void missingOrFailedFactsNeverFallBackToEligible(){
        when(canonical.purchaseEligibilityBatch(29L,products)).thenReturn(ApiResult.ok(Map.of()),ApiResult.fail(503,"F4B_QUOTA_UNAVAILABLE"));
        assertEquals("PURCHASE_ELIGIBILITY_UNAVAILABLE",assertThrows(BizException.class,()->quotes.requirePurchaseEligibility(29L,items)).getMessage());
        assertEquals("F4B_QUOTA_UNAVAILABLE",assertThrows(BizException.class,()->quotes.requirePurchaseEligibility(29L,items)).getMessage());
        var offline=new DataAccessResourceFailureException("offline");when(canonical.purchaseEligibilityBatch(29L,products)).thenThrow(offline);
        assertSame(offline,assertThrows(DataAccessResourceFailureException.class,()->quotes.requirePurchaseEligibility(29L,items)));
        when(provider.getObject()).thenThrow(new NoSuchBeanDefinitionException(AppCanonicalBoundaryService.class));
        assertThrows(NoSuchBeanDefinitionException.class,()->quotes.requirePurchaseEligibility(29L,items));
        verifyNoInteractions(db);
    }
    @Test void unavailableOrInvalidDecisionsAreUnknownEvenInsideASuccessfulBatch(){
        for(String code:List.of("F4B_QUOTA_UNAVAILABLE","PURCHASE_GATE_FACTS_INVALID","PURCHASE_GATE_FACTS_UNAVAILABLE","PURCHASE_GATE_INVALID","PRODUCT_SPECS_UNAVAILABLE","PRODUCT_INVENTORY_MODE_INVALID","FUTURE_UNRECOGNIZED_CODE")){
            var decisions=decisions();decisions.put("stellarbox-pro",new PurchaseEligibilityDecision("stellarbox-pro",false,code));
            when(canonical.purchaseEligibilityBatch(29L,products)).thenReturn(ApiResult.ok(decisions));
            var failure=assertThrows(BizException.class,()->quotes.requirePurchaseEligibility(29L,items));
            assertEquals(503,failure.getCode(),code);assertEquals(code,failure.getMessage());
        }
        verifyNoInteractions(db);
    }
    @Test void unavailableDiagnosticReadRollsBackItsOwnTransactionWhileSimulationCanReturnUnknown() throws Exception {
        var dataSource=mock(javax.sql.DataSource.class);var outer=mock(java.sql.Connection.class);var inner=mock(java.sql.Connection.class);
        when(dataSource.getConnection()).thenReturn(outer,inner);
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        var factory=new org.springframework.aop.framework.ProxyFactory(quotes);factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var diagnostic=(PromotionQuoteService)factory.getProxy();
        when(canonical.purchaseEligibilityBatch(29L,products)).thenThrow(new DataAccessResourceFailureException("offline"));
        var simulation=new org.springframework.transaction.support.TransactionTemplate(manager);simulation.setReadOnly(true);
        simulation.executeWithoutResult(status->{
            assertThrows(DataAccessResourceFailureException.class,()->diagnostic.requirePurchaseEligibility(29L,items));
            assertFalse(status.isRollbackOnly());
        });
        verify(inner).rollback();verify(outer).commit();verify(outer,never()).rollback();
    }
}
