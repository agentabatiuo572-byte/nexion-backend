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
        Map<String,Map<String,Object>> originalFences=new HashMap<>();String healthy=ids.get(50);
        for(String id:ids){
            long buyer=buyers.get(id);String refund=refunds.get(id);
            var row=h.db.requiredRow("SELECT r.*,s.amount FROM nx_promotion_reward r JOIN nx_promotion_reservation s ON s.reservation_id=r.reservation_id WHERE r.obligation_id=?",id);
            String key="PROMOTION-RECOVER-"+sha256(id+":WHOLE_ORDER_REFUND:"+refund);keys.put(key,buyer);
            String payload=List.of(String.valueOf(buyer),text(row.get("original_earnings_entry_no")),"PROMOTION_REWARD",id,"NEX","PRODUCTION",decimal(row.get("amount")).setScale(6).toPlainString(),"Confirmed whole-order wallet refund","PROMOTION_ENGINE")
                .stream().map(v->v.length()+":"+v).collect(java.util.stream.Collectors.joining());
            String state=id.equals(healthy)?"FAILED":ids.indexOf(id)<25?"PROCESSING":"UNKNOWN";
            int deleted=ids.indexOf(id)==49?1:0;
            h.db.write("INSERT INTO nx_admin_idempotency_record(scope,idempotency_key,request_hash,status,is_deleted,expires_at) VALUES(?,?,?,?,?,IF(?='PROCESSING',DATE_ADD(NOW(),INTERVAL 1 DAY),DATE_SUB(NOW(),INTERVAL 1 DAY)))","EARNINGS_RECOVERY:PRODUCTION:"+buyer,key,sha256(payload),state,deleted,state);
            if(!id.equals(healthy))originalFences.put(key,h.db.requiredRow("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?","EARNINGS_RECOVERY:PRODUCTION:"+buyer,key));
        }
        // All fifty owned unresolved obligations sort before healthy; filtering must happen before LIMIT.
        var firstPage=life.rewards.reversalsAfter("",50);
        assertTrue(firstPage.contains(healthy),"A matching unresolved recovery fence must not consume the healthy refund's batch slot");
        for(String id:ids.subList(0,50))assertFalse(firstPage.contains(id));
        String scope="EARNINGS_RECOVERY:PRODUCTION:"+buyers.get(healthy);
        String key="PROMOTION-RECOVER-"+sha256(healthy+":WHOLE_ORDER_REFUND:"+refunds.get(healthy));
        String cursor=text(h.db.requiredRow("SELECT MAX(obligation_id) AS previous FROM nx_promotion_reward WHERE obligation_id<?",healthy).get("previous"));
        String order=text(h.db.requiredRow("SELECT order_no FROM nx_promotion_reward WHERE obligation_id=?",healthy).get("order_no"));
        String laterRefund=id("LATER");
        h.db.write("INSERT INTO nx_promotion_refund_hold(refund_request_id,order_no,source_type,source_id,source_event_id,status,refund_no,refund_ledger_biz_no,evidence_json,reason) SELECT ?,order_no,'E4_REFUND',?,?,'EXECUTED',?,refund_ledger_biz_no,evidence_json,'Isolated later basis' FROM nx_promotion_refund_hold WHERE order_no=? AND status='EXECUTED' ORDER BY refund_request_id LIMIT 1","ZZ-"+laterRefund,laterRefund,laterRefund,laterRefund,order);
        assertEquals(refunds.get(healthy),text(h.db.requiredRow("SELECT refund_no FROM nx_promotion_refund_hold WHERE order_no=? AND status='EXECUTED' ORDER BY refund_request_id LIMIT 1",order).get("refund_no")));
        h.db.write("INSERT INTO nx_admin_idempotency_record(scope,idempotency_key,request_hash,status,expires_at) VALUES(?,?,?,'UNKNOWN',DATE_SUB(NOW(),INTERVAL 1 DAY))","EARNINGS_RECOVERY:PRODUCTION:"+buyers.get(ids.get(0)),key,sha256("different buyer"));
        h.db.write("INSERT INTO nx_admin_idempotency_record(scope,idempotency_key,request_hash,status,expires_at) VALUES(?,?,?,'UNKNOWN',DATE_SUB(NOW(),INTERVAL 1 DAY))",scope,"PROMOTION-RECOVER-"+sha256(healthy+":APPROVED_CORRECTION:"+laterRefund),sha256("different command"));
        h.db.write("INSERT INTO nx_admin_idempotency_record(scope,idempotency_key,request_hash,status,expires_at) VALUES(?,?,?,'UNKNOWN',DATE_SUB(NOW(),INTERVAL 1 DAY))",scope,"PROMOTION-RECOVER-"+sha256(healthy+":WHOLE_ORDER_REFUND:"+laterRefund),sha256("unselected refund"));
        var unrelated=h.db.list("SELECT * FROM nx_admin_idempotency_record WHERE idempotency_key IN (?,?,?)",key,"PROMOTION-RECOVER-"+sha256(healthy+":APPROVED_CORRECTION:"+laterRefund),"PROMOTION-RECOVER-"+sha256(healthy+":WHOLE_ORDER_REFUND:"+laterRefund));
        for(var variant:List.of(values("state","FAILED","deleted",0,"eligible",true),values("state","SUCCEEDED","deleted",1,"eligible",true),values("state","PROCESSING","deleted",1,"eligible",true),values("state","PROCESSING","deleted",0,"eligible",false),values("state","UNKNOWN","deleted",0,"eligible",false),values("state","UNKNOWN","deleted",1,"eligible",false))){
            h.db.write("UPDATE nx_admin_idempotency_record SET status=?,is_deleted=?,expires_at=DATE_SUB(NOW(),INTERVAL 1 DAY) WHERE scope=? AND idempotency_key=?",variant.get("state"),variant.get("deleted"),scope,key);
            var before=h.db.requiredRow("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?",scope,key);
            assertEquals(variant.get("eligible"),life.rewards.reversalsAfter(cursor,1).contains(healthy),json(variant));
            assertEquals(before,h.db.requiredRow("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?",scope,key),"Eligibility reads must not mutate retained fences");
        }
        Object originalEntry=h.db.requiredRow("SELECT original_earnings_entry_no FROM nx_promotion_reward WHERE obligation_id=?",healthy).get("original_earnings_entry_no");
        h.db.write("UPDATE nx_promotion_reward SET original_earnings_entry_no=NULL WHERE obligation_id=?",healthy);
        assertTrue(life.rewards.reversalsAfter(cursor,1).contains(healthy),"Null-entry rewards, including device receipts, do not use earnings recovery fences");
        h.db.write("UPDATE nx_promotion_reward SET original_earnings_entry_no=? WHERE obligation_id=?",originalEntry,healthy);
        h.db.write("UPDATE nx_admin_idempotency_record SET status='FAILED',is_deleted=0 WHERE scope=? AND idempotency_key=?",scope,key);
        unrelated.removeIf(row->scope.equals(row.get("scope"))&&key.equals(row.get("idempotency_key")));
        // Reproduce the old first-page behavior before exercising the scheduler: unknown fences persist.
        for(String id:ids.subList(0,50)){
            var failure=assertThrows(ffdd.opsconsole.shared.exception.BizException.class,()->life.rewards.reverseRefund(id,id("BLOCKED")));
            assertEquals(ids.indexOf(id)<25?"IDEMPOTENCY_REQUEST_IN_PROGRESS":"IDEMPOTENCY_RESULT_UNKNOWN",failure.getMessage());
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
        for(var fence:originalFences.entrySet())assertEquals(fence.getValue(),h.db.requiredRow("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?","EARNINGS_RECOVERY:PRODUCTION:"+keys.get(fence.getKey()),fence.getKey()));
        for(var fence:unrelated)assertEquals(fence,h.db.requiredRow("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?",fence.get("scope"),fence.get("idempotency_key")));
        assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_promotion_reversal WHERE obligation_id=?",healthy));
        assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no=?","PROMOTION-REVERSE-"+healthy));
        life.budget(activity,"NEX","reversed",amount("1.000001"));
        var report=values("completed",true,"run",h.run,"activity",activity,"healthyObligation",healthy,"blockedCount",50,"dispatches",dispatches,"queued",queued,"maxDispatches",maxDispatches,"unknownFencesPreserved",true,"singleActualRecovery",true);
        Files.writeString(Path.of("C:/Users/jason/.codex/workflow-runs/growth-promotions-20261007/promotion-reversal-queue-runtime.json"),json(report));
    }
}
