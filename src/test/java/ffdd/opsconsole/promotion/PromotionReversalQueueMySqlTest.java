package ffdd.opsconsole.promotion;

import ffdd.opsconsole.promotion.application.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="GROWTH_PROMOTION_RUNTIME",matches="1")
class PromotionReversalQueueMySqlTest {
    @Test void unknownFirstFiftyDoNotStarveHealthyRecovery() throws Exception {
        var life=new PromotionLifecycleMySqlTest();life.setup();var h=life.h;
        String activity=h.publish(h.contract(life.buy,life.coinSpec(),h.commonPolicies()));
        Map<String,Long> buyers=new TreeMap<>();Map<String,String> refunds=new HashMap<>();
        for(int i=0;i<51;i++){
            long buyer=life.user();String order=life.create(buyer,life.quote(buyer,activity,1),1);life.pay(buyer,order);
            String id=life.obligations(order).get(0);buyers.put(id,buyer);
            assertEquals("ISSUED",life.rewards.issue(id,id("ISSUE")).get("state"));life.refund(buyer,order);
            refunds.put(id,text(h.db.requiredRow("SELECT refund_no FROM nx_promotion_refund_hold WHERE order_no=? AND status='EXECUTED'",order).get("refund_no")));
        }
        List<String> ids=new ArrayList<>(buyers.keySet());Map<String,Long> keys=new HashMap<>();
        for(String id:ids.subList(0,50)){
            long buyer=buyers.get(id);String refund=refunds.get(id);
            var row=h.db.requiredRow("SELECT r.*,s.amount FROM nx_promotion_reward r JOIN nx_promotion_reservation s ON s.reservation_id=r.reservation_id WHERE r.obligation_id=?",id);
            String key="PROMOTION-RECOVER-"+sha256(id+":WHOLE_ORDER_REFUND:"+refund);keys.put(key,buyer);
            String payload=List.of(String.valueOf(buyer),text(row.get("original_earnings_entry_no")),"PROMOTION_REWARD",id,"NEX","PRODUCTION",decimal(row.get("amount")).setScale(6).toPlainString(),"Confirmed whole-order wallet refund","PROMOTION_ENGINE")
                .stream().map(v->v.length()+":"+v).collect(java.util.stream.Collectors.joining());
            h.db.write("INSERT INTO nx_admin_idempotency_record(scope,idempotency_key,request_hash,status,expires_at) VALUES(?,?,?,'PROCESSING',DATE_ADD(NOW(),INTERVAL 1 DAY))","EARNINGS_RECOVERY:PRODUCTION:"+buyer,key,sha256(payload));
        }
        String healthy=ids.get(50);
        // Reproduce the old first-page behavior before exercising the scheduler: unknown fences persist.
        for(String id:ids.subList(0,50)){
            var failure=assertThrows(ffdd.opsconsole.shared.exception.BizException.class,()->life.rewards.reverseRefund(id,id("BLOCKED")));
            assertEquals("IDEMPOTENCY_REQUEST_IN_PROGRESS",failure.getMessage());
        }
        var scheduler=new PromotionRewardScheduler(life.rewards,h.proxy(new PromotionAvailabilityService(h.db,h.audit)));
        long queued=h.db.count("SELECT COUNT(*) FROM nx_promotion_reward WHERE status='REVERSAL_PENDING'");
        long maxDispatches=(queued+49)/50+2;
        long dispatches=0;
        while(dispatches<maxDispatches&&!"REVERSED".equals(life.rewards.get(buyers.get(healthy),healthy,false).get("state"))){
            scheduler.dispatch();dispatches++;
        }
        assertEquals("REVERSED",life.rewards.get(buyers.get(healthy),healthy,false).get("state"));
        for(String id:ids.subList(0,50)){
            assertEquals("REVERSAL_PENDING",life.rewards.get(buyers.get(id),id,false).get("state"));
            assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_promotion_reversal WHERE obligation_id=?",id));
        }
        for(var key:keys.entrySet())assertEquals("PROCESSING",h.db.requiredRow("SELECT status FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?","EARNINGS_RECOVERY:PRODUCTION:"+key.getValue(),key.getKey()).get("status"));
        assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_promotion_reversal WHERE obligation_id=?",healthy));
        assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no=?","PROMOTION-REVERSE-"+healthy));
        life.budget(activity,"NEX","reversed",amount("1.000001"));
        var report=values("completed",true,"run",h.run,"activity",activity,"healthyObligation",healthy,"blockedCount",50,"dispatches",dispatches,"queued",queued,"maxDispatches",maxDispatches,"unknownFencesPreserved",true,"singleActualRecovery",true);
        Files.writeString(Path.of("C:/Users/jason/.codex/workflow-runs/growth-promotions-20261007/promotion-reversal-queue-runtime.json"),json(report));
    }
}
