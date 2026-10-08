package ffdd.opsconsole.promotion.application;

import java.util.*;
import java.math.BigDecimal;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;

class PromotionMetricsBreakdownTest {
    @Test void skuSalesUseExactFrozenDiscountAllocationAndRejectUnmatchedFacts(){
        var db=mock(PromotionMapper.class);var service=new PromotionMetricsService(db,null,null,null);
        var quoted=List.of(values("productNo","A","quantity",2,"payableUsdt","66.000001"),values("productNo","D","quantity",1,"payableUsdt","34.000002"));
        when(db.requiredRow(startsWith("SELECT snapshot_json"),any(Object[].class))).thenReturn(values("snapshot_json",json(values("public",values("items",quoted)))));
        when(db.list(startsWith("SELECT id,product_no"),any(Object[].class))).thenReturn(List.of(values("id",101,"product_no","A","product_name","Original A","quantity",2),values("id",102,"product_no","D","product_name","Original D","quantity",1)));
        var order=values("order_no","order-1");var paid=new BigDecimal("100.000003");
        for(var refund:List.of(BigDecimal.ZERO,paid)){
            List<Map<String,Object>> rows=ReflectionTestUtils.invokeMethod(service,"orderSales",order,paid,refund);
            assertEquals("66.000001",rows.get(0).get("grossPaidUsdt"));assertEquals("34.000002",rows.get(1).get("grossPaidUsdt"));
            assertEquals("Original A",rows.get(0).get("productName"));assertEquals("101",rows.get(0).get("lineId"));
            assertEquals(refund.setScale(6),rows.stream().map(r->decimal(r.get("refundUsdt"))).reduce(BigDecimal.ZERO,BigDecimal::add));
            assertEquals(paid.subtract(refund),rows.stream().map(r->decimal(r.get("netReceivedUsdt"))).reduce(BigDecimal.ZERO,BigDecimal::add));
        }
        assertThrows(BizException.class,()->ReflectionTestUtils.invokeMethod(service,"orderSales",order,new BigDecimal("100"),BigDecimal.ZERO));
        assertThrows(BizException.class,()->ReflectionTestUtils.invokeMethod(service,"orderSales",order,paid,BigDecimal.ONE));
        when(db.list(startsWith("SELECT id,product_no"),any(Object[].class))).thenReturn(List.of(values("id",101,"product_no","A","quantity",2)));
        assertThrows(BizException.class,()->ReflectionTestUtils.invokeMethod(service,"orderSales",order,paid,BigDecimal.ZERO));
        verify(db,never()).product(anyString(),anyBoolean());
    }
    @Test void wideExactTotalsSplitNativeAssetsDeviceSkuAndBeneficiaryRoles(){
        var first=fact("USDT",null,"1","BUYER","600000000000.000000");var second=copy(first);second.put("orderNo","order-2");
        var facts=List.of(first,second,fact("NEX",null,"1","BUYER","1.000001"),fact("DEVICE","gift-a","1","BUYER","2.000000"),fact("DEVICE","gift-b","1","BUYER","3.000000"),fact("USDT",null,"1","DIRECT_INVITER","4.000000"));
        var groups=PromotionMetricsService.groupRewards(facts);var sku=maps(groups.get("SKU"));assertEquals(4,sku.size());
        var dollars=sku.stream().filter(r->"USDT".equals(r.get("asset"))).findFirst().orElseThrow();assertEquals("1200000000004.000000",dollars.get("amount"));assertEquals("1200000000004.000000",dollars.get("issued"));assertEquals(2L,dollars.get("orders"));assertEquals(1L,dollars.get("beneficiaries"));
        assertEquals(5,maps(groups.get("BENEFICIARY")).size());assertEquals(4,maps(groups.get("TEMPLATE")).size());
        var reverse=new ArrayList<Map<String,Object>>(facts);Collections.reverse(reverse);assertEquals(hash(groups),hash(PromotionMetricsService.groupRewards(reverse)));
        var validator=new PromotionContractValidator(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());for(Object rows:groups.values())for(var row:maps(rows))validator.validate("MetricRewardGroupRow",row);
    }
    private Map<String,Object> fact(String asset,String gift,String beneficiary,String role,String amount){return values("purchaseProductNo","purchase-a","template","DIRECT_REFERRAL","beneficiaryId",beneficiary,"beneficiaryRole",role,"asset",asset,"giftProductNo",gift,"orderNo","order-1","amount",amount,"issued",amount,"pending","0.000000","reversed","0.000000","unrecoverable","0.000000","cancelled","0.000000");}
}
