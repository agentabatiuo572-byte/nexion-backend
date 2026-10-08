package ffdd.opsconsole.promotion;

import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.promotion.application.*;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="GROWTH_PROMOTION_RUNTIME", matches="1")
class PromotionNativeSnapshotMySqlTest {
    @Test void committedProductOrSkuChangeAfterPolicyReadCannotBecomeGiftSnapshot() throws Exception {
        var f=new PromotionLifecycleMySqlTest();f.setup();var h=f.h;
        var evidence=new ArrayList<Object>();
        for(String source:List.of("product","sku")){
            var policy=h.devicePolicy(f.gift);
            String activity=h.publish(h.contract(f.buy,values("rewardRuleId","reward-buyer","beneficiaryRole","BUYER","type","DEVICE","giftProductNo",f.gift,"quantity",1,"deviceRightsProfile",policy),h.commonPolicies()));
            long buyer=f.user();var quote=f.quote(buyer,activity,1);
            var before=h.db.product(f.gift,false);
            var sku=h.db.requiredRow("SELECT * FROM nx_admin_device_sku WHERE sku_id=?",f.gift);
            var db=spy(h.db);var changed=new AtomicBoolean();
            doAnswer(call->{
                if(changed.compareAndSet(false,true))CompletableFuture.runAsync(()->{
                    if("product".equals(source))h.db.write("UPDATE nx_product SET hashrate=COALESCE(hashrate,0)+1 WHERE product_no=?",f.gift);
                    else h.db.write("UPDATE nx_admin_device_sku SET gpu=CONCAT(COALESCE(gpu,''),' race') WHERE sku_id=?",f.gift);
                }).get(10,TimeUnit.SECONDS);
                return call.callRealMethod();
            }).when(db).product(f.gift,true);
            f.orders=h.proxy(new PromotionOrderService(db,f.quotes,new PromotionEvaluationService(h.db),h.resolver,h.audit,null));
            try{
                var error=assertThrows(BizException.class,()->f.create(buyer,quote,1),source);
                assertTrue(changed.get());assertEquals("PROMOTION_NATIVE_CONTRACT_CHANGED",error.getMessage());
                assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_promotion_order_receipt WHERE quote_id=?",quote.get("quoteId")));
                assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_promotion_reservation WHERE activity_id=?",activity));
                assertEquals(0,number(h.db.activity(activity,false).get("reserved_orders")));
                evidence.add(values("source",source,"result",error.getMessage(),"reservations",0));
            }finally{
                h.db.write("UPDATE nx_product SET hashrate=? WHERE product_no=?",before.get("hashrate"),f.gift);
                h.db.write("UPDATE nx_admin_device_sku SET gpu=? WHERE sku_id=?",sku.get("gpu"),f.gift);
            }
        }
        Files.writeString(Path.of("target/promotion-native-snapshot-runtime.json"),json(values("status","PASS","run",h.run,"evidence",evidence)));
    }
}
