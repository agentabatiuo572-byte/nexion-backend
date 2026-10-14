package ffdd.opsconsole.promotion.application;

import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.util.*;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import org.junit.jupiter.api.Test;

class PromotionDevicePowerTest {
    private static Map<String,Object> product(String type) {
        return values("id",3012,"product_no","box-s1","product_type",type,"status","ACTIVE","vram_total_gb",8,
                "name","StellarBox S1","tier",1,"generation",1,"gpu_model","GPU","hashrate",1,"estimated_daily_usdt",1,"daily_nex",1);
    }
    private static Map<String,Object> sku(String power) {
        return values("name","StellarBox S1","power_text",power,"datacenter","dc");
    }
    private static final Map<String,String> VALID = Map.of(
            "1200","1200", "1200.25","1200.25", "1200w","1200", " 1,200W TDP ","1200",
            "1,200.125 W tdp","1200.125", "0.000001W","0.000001", "999,999,999,999.999999 W TDP","999999999999.999999");
    private static final List<String> INVALID = List.of("", "unknown", "0", "0 W TDP", "-1200W", "1e3W",
            "1000000000000W", "1,000,000,000,000W", "1200.1234567W", "1,20W TDP", "12,34,567W", "1200WW",
            "W1200", "1,200WW TDP", "1200W garbage", "1200TDP", "1200 W TDP TDP");
    private static final String[][] FORMAT_BOUNDARIES = {
        {"1200W\u0000",null},{"1200W\u0001",null},{"1200W\u001f",null},
        {"\t1200W",null},{"1200W\t",null},{"\n1200W",null},{"1200W\n",null},
        {"1200W TDP\t",null},{"1200W TDP\n",null},{"\u00a01200W",null},{"1200W\u00a0",null},
        {"\u20031200W",null},{"1200W\u2003",null},{"1200W\u00a0TDP","1200"},{"1200W\u2003TDP","1200"},
        {"1200\u00a0W","1200"},{" 1,200W TDP ","1200"},{"1,200 W TDP","1200"},{"1200","1200"},{"1200\n","1200"}
    };

    @Test void actualEligibilityCallerPreservesStrictControlAndUnicodeFormat() {
        for(var sample:FORMAT_BOUNDARIES) {
            String actual=PromotionNativeContractResolver.giftFailure(product("DEVICE"),sku(sample[0]));
            assertEquals(sample[1]==null?"PRODUCT_RIGHTS_INCOMPLETE":null,actual,
                    java.util.HexFormat.of().formatHex(sample[0].getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    @Test void actualRewardCallerPreservesStrictControlAndUnicodeFormat() throws Exception {
        for(var sample:FORMAT_BOUNDARIES) {
            var writes=new ArrayList<Object[]>();var db=mock(PromotionMapper.class);
            String label=java.util.HexFormat.of().formatHex(sample[0].getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(sample[1]==null) {
                var error=assertThrows(InvocationTargetException.class,()->invokeGift(db,sample[0],"DEVICE",writes),label);
                assertEquals("PROMOTION_GIFT_POWER_UNAVAILABLE",error.getCause().getMessage(),label);assertTrue(writes.isEmpty(),label);
            }else {
                invokeGift(db,sample[0],"DEVICE",writes);assertEquals(2,writes.size(),label);
                assertEquals(0,new BigDecimal(sample[1]).compareTo((BigDecimal)writes.get(0)[12]),label);
            }
        }
    }

    @Test void resolverChecksWholePowerTextAndKeepsRightsGuards() {
        VALID.forEach((power,watts)->assertNull(PromotionNativeContractResolver.giftFailure(product("DEVICE"),sku(power)),power));
        assertNull(PromotionNativeContractResolver.giftFailure(product("SERVER"),sku("1,200W TDP")));
    }

    @Test void resolverRejectsMalformedPowerAndKeepsRightsGuards() {
        INVALID.forEach(power->assertEquals("PRODUCT_RIGHTS_INCOMPLETE",PromotionNativeContractResolver.giftFailure(product("DEVICE"),sku(power)),power));
        assertEquals("PRODUCT_RIGHTS_INCOMPLETE",PromotionNativeContractResolver.giftFailure(product("DEVICE"),sku(null)));
        for(String identity:List.of("SHARE","Cloud Share","cloud-share","cloud_share")) {
            var p=product("DEVICE");p.put("device_type",identity);
            assertEquals("UNSUPPORTED_DEVICE_TYPE",PromotionNativeContractResolver.giftFailure(p,sku("1,200W TDP")));
        }
        var p=product("SHARE");assertEquals("UNSUPPORTED_DEVICE_TYPE",PromotionNativeContractResolver.giftFailure(p,sku("0W")));
        p=product("DEVICE");p.put("status","PAUSED");assertEquals("PRODUCT_UNAVAILABLE",PromotionNativeContractResolver.giftFailure(p,sku("1,200W TDP")));
        p=product("DEVICE");p.put("vram_total_gb",0);assertEquals("PRODUCT_RIGHTS_INCOMPLETE",PromotionNativeContractResolver.giftFailure(p,sku("1,200W TDP")));
        var s=sku("1,200W TDP");s.put("datacenter","");assertEquals("PRODUCT_RIGHTS_INCOMPLETE",PromotionNativeContractResolver.giftFailure(product("DEVICE"),s));
    }

    @Test void actualRewardCallerWritesExactWattsAndKeepsGiftSource() throws Exception {
        for(var entry:VALID.entrySet()) {
            var db=mock(PromotionMapper.class);var writes=new ArrayList<Object[]>();
            invokeGift(db,entry.getKey(),"DEVICE",writes);
            assertEquals(2,writes.size());
            assertEquals(0,new BigDecimal(entry.getValue()).compareTo((BigDecimal)writes.get(0)[1+11]),entry.getKey());
            assertTrue(writes.get(0)[0].toString().contains("'PROMOTION_GIFT','PRODUCTION'"));
            assertTrue(writes.get(1)[0].toString().contains("nx_promotion_device_receipt"));
        }
        var writes=new ArrayList<Object[]>();invokeGift(mock(PromotionMapper.class),"1,200W TDP","SERVER",writes);assertEquals(2,writes.size());
    }

    @Test void actualRewardCallerRejectsMalformedPowerAndShareBeforeAnyWrite() throws Exception {
        for(String power:INVALID) {
            var writes=new ArrayList<Object[]>();var db=mock(PromotionMapper.class);
            var error=assertThrows(InvocationTargetException.class,()->invokeGift(db,power,"DEVICE",writes));
            assertEquals("PROMOTION_GIFT_POWER_UNAVAILABLE",error.getCause().getMessage(),power);assertTrue(writes.isEmpty());
        }
        var writes=new ArrayList<Object[]>();
        var missing=assertThrows(InvocationTargetException.class,()->invokeGift(mock(PromotionMapper.class),null,"DEVICE",writes));
        assertEquals("PROMOTION_GIFT_POWER_UNAVAILABLE",missing.getCause().getMessage());assertTrue(writes.isEmpty());
        var error=assertThrows(InvocationTargetException.class,()->invokeGift(mock(PromotionMapper.class),"0W","SHARE",writes));
        assertEquals("PROMOTION_GIFT_PRODUCT_UNSUPPORTED",error.getCause().getMessage());assertTrue(writes.isEmpty());
    }

    private static void invokeGift(PromotionMapper db,String power,String type,List<Object[]> writes) throws Exception {
        when(db.deviceSlotCap()).thenReturn(10L);when(db.occupiedDeviceSlots(anyLong())).thenReturn(0L);
        doReturn(values("id",1)).when(db).requiredRow(anyString(),any());
        doAnswer(invocation->{writes.add(invocation.getArguments());return 1;}).when(db).write(anyString(),any(Object[].class));
        var service=new PromotionRewardService(db,null,null,null,null,null);
        var rights=values("productNo","box-s1","activationMode","AUTO","effectiveOn","ISSUED","durationDays",null);
        var profile=values("policyId","rights","version",1,"resolvedDeviceRights",rights);
        var snapshot=values("policies",values("rights:1",profile),"products",values("box-s1",values("product",product(type),"sku",sku(power))));
        var spec=values("giftProductNo","box-s1","quantity",1,"deviceRightsProfile",values("policyId","rights","version",1));
        var method=PromotionRewardService.class.getDeclaredMethod("issueDevices",Map.class,Map.class,Map.class);method.setAccessible(true);
        method.invoke(service,values("beneficiary_id",1,"order_no","order","obligation_id","obligation"),snapshot,spec);
    }
}
