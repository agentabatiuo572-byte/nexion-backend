package ffdd.opsconsole.promotion;

import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;

import ffdd.opsconsole.commerce.application.AppOrderCommandService;
import ffdd.opsconsole.commerce.mapper.AppOrderCommandMapper;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.finance.application.FundsSandboxProperties;
import ffdd.opsconsole.promotion.application.PromotionCommandQueryService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;

@EnabledIfEnvironmentVariable(named="GROWTH_PROMOTION_RUNTIME",matches="1")
class PromotionCommandQueryMySqlTest {
    @Test void canonicalExpiredAndUnlistedPaymentResponsesRecoverAsFailedWithoutAssets() throws Exception {
        var f=new PromotionLifecycleMySqlTest();f.setup();var h=f.h;
        h.session.getConfiguration().addMapper(AppOrderCommandMapper.class);
        var env=new MockEnvironment();env.setActiveProfiles("dev");
        var pay=h.proxy(new AppOrderCommandService(h.session.getMapper(AppOrderCommandMapper.class),h.idempotency,h.audit,
            new FundsSandboxProfileGuard(new FundsSandboxProperties(),env),null,null,null,null,30,f.orders));
        var query=new PromotionCommandQueryService(h.db,h.idempotency,f.orders);
        String activity=h.publish(h.contract(f.buy,values("rewardRuleId","reward-buyer","beneficiaryRole","BUYER","type","USDT","calculation","FIXED","amount","1.000000","assetPolicy",h.assetPolicy("USDT")),h.commonPolicies()));
        var evidence=new ArrayList<Object>();
        for(String reason:List.of("ORDER_PAYMENT_EXPIRED","ORDER_PRODUCT_NOT_PAYABLE")){
            long buyer=f.user();String order=f.create(buyer,f.quote(buyer,activity,1),1);String key=h.run+reason;
            var before=h.db.requiredRow("SELECT usdt_available FROM nx_user_wallet WHERE user_id=?",buyer);
            Object status=h.db.product(f.buy,false).get("status");
            if(reason.endsWith("EXPIRED"))h.db.write("UPDATE nx_order SET created_at=DATE_SUB(NOW(),INTERVAL 2 HOUR) WHERE order_no=?",order);
            else h.db.write("UPDATE nx_product SET status='INACTIVE' WHERE product_no=?",f.buy);
            try{
                var failed=pay.pay(buyer,order,key);assertEquals(409,failed.getCode());assertEquals(reason,failed.getMessage());
                var receipt=query.own(buyer,"payOrder",order,key);
                assertEquals("FAILED",receipt.get("status"));assertEquals(key,receipt.get("idempotencyKey"));
                assertEquals(reason,map(receipt.get("error")).get("message"));assertNull(receipt.get("order"));
                assertEquals(before,h.db.requiredRow("SELECT usdt_available FROM nx_user_wallet WHERE user_id=?",buyer));
                assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_promotion_reward WHERE order_no=?",order));
                assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=? AND biz_no=? AND biz_type='ORDER_PURCHASE'",buyer,order));
                assertEquals(reason,pay.pay(buyer,order,key).getMessage());
                assertEquals("FAILED",query.own(buyer,"payOrder",order,key).get("status"));
                evidence.add(values("reason",reason,"order",order,"status",receipt.get("status")));
            }finally{h.db.write("UPDATE nx_product SET status=? WHERE product_no=?",status,f.buy);}
        }
        Files.writeString(Path.of("target/promotion-command-query-runtime.json"),json(values("status","PASS","run",h.run,"evidence",evidence)));
    }
}
