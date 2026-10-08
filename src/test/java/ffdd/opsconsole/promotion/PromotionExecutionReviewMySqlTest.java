package ffdd.opsconsole.promotion;

import ffdd.opsconsole.promotion.application.*;
import ffdd.opsconsole.commerce.application.AppOrderCommandService;
import ffdd.opsconsole.commerce.mapper.AppOrderCommandMapper;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.finance.application.FundsSandboxProperties;
import ffdd.opsconsole.device.mapper.DeviceCatalogMapper;
import ffdd.opsconsole.device.infrastructure.MybatisDeviceCatalogRepository;
import ffdd.opsconsole.growth.application.AppGrowthLifecyclePublisher;
import ffdd.opsconsole.growth.application.GrowthRhythmFacadeAdapter;
import ffdd.opsconsole.shared.canonical.*;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL/services; commerce paid/refund source facts are explicitly isolated fixtures. */
@EnabledIfEnvironmentVariable(named="GROWTH_PROMOTION_RUNTIME",matches="1")
class PromotionExecutionReviewMySqlTest {
    final List<Map<String,Object>> evidence=new ArrayList<>();
    String evidenceFile="promotion-execution-review-runtime.json";
    PromotionLifecycleMySqlTest life;PromotionRuntimeHarness h;
    @Test void frozenAudienceFairDispatchAndRefundCountRestoration() throws Exception {
        life=new PromotionLifecycleMySqlTest();life.setup();h=life.h;
        assertEquals("YES",h.db.requiredRow("SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_promotion_order_receipt' AND COLUMN_NAME='quota_restored_at'").get("IS_NULLABLE"));
        Map<String,Object> common=h.commonPolicies();
        assertAll("independent execution review regressions",()->rankSnapshot(common),()->dispatchPastBlockedFifty(common),
            ()->quotaRestoration(common,true),()->quotaRestoration(common,false),()->unrecoveredCountsStayConsumed(common),
            ()->usedGiftCurrentRead(common),()->reconcileCurrentRead(common),()->adminRevisionCurrentRead(common),()->scanBeyondHealthyHundred(common));
        write(true);
    }
    @Test void canonicalVoucherZeroPaymentConsumesFirstPurchase() throws Exception{
        evidenceFile="promotion-zero-voucher-runtime.json";life=new PromotionLifecycleMySqlTest();life.setup();h=life.h;
        zeroVoucherFirstPurchase(h.commonPolicies());write(true);
    }
    Map<String,Object> firstPolicy(Map<String,Object> common,boolean restore){var policies=copy(common);policies.put("firstPurchase",h.approvePolicy(values("kind","FIRST_PURCHASE","executorCode","PROMOTION_FIRST_PURCHASE_V1","mode","FIRST_ELIGIBLE_ORDER","restoreAfterRefund",restore)));return policies;}
    void zeroVoucherFirstPurchase(Map<String,Object> common){
        h.session.getConfiguration().addMapper(AppOrderCommandMapper.class);h.session.getConfiguration().addMapper(DeviceCatalogMapper.class);
        var env=new MockEnvironment();env.setActiveProfiles("dev");var guard=new FundsSandboxProfileGuard(new FundsSandboxProperties(),env);
        var outbox=(EventOutboxService)ReflectionTestUtils.getField(life.orders,"outbox");
        var vouchers=(AppGrowthLifecyclePublisher)ReflectionTestUtils.getField(life.quotes,"vouchers");
        var rhythm=new GrowthRhythmFacadeAdapter(h.config,OpsReadTimeSeedPolicy.disabledForDirectConstruction());
        var release=new StorefrontProductReleasePolicy(new MybatisDeviceCatalogRepository(h.session.getMapper(DeviceCatalogMapper.class),new ObjectMapper()),rhythm);
        var create=h.proxy(new AppCanonicalBoundaryService(life.canonical,null,h.idempotency,outbox,vouchers,rhythm,h.audit,null,guard,null,release,new StorefrontPurchaseGatePolicy(),env,life.orders));
        var pay=h.proxy(new AppOrderCommandService(h.session.getMapper(AppOrderCommandMapper.class),h.idempotency,h.audit,guard,null,null,null,null,30,life.orders));
        var contract=h.contract(life.buy,life.coinSpec(),firstPolicy(common,false));contract.put("template","FIRST_PURCHASE");
        var audience=copy(map(contract.get("buyerAudience")));audience.put("purchaseHistory","NEVER_PAID");contract.put("buyerAudience",audience);
        String first=h.publish(contract);var another=copy(contract);another.put("name",h.run+" second voucher activity");String second=h.publish(another);long buyer=life.user();
        BigDecimal balance=decimal(h.db.requiredRow("SELECT usdt_available FROM nx_user_wallet WHERE user_id=?",buyer).get("usdt_available"));
        List<String> orders=new ArrayList<>();
        for(String activity:List.of(first,second)){
            String voucher=id("FULLVOUCHER"),grant=id("GRANT");
            // Only coupon ownership is a fixture; quote, canonical creation/redemption and payment are real services.
            h.db.write("INSERT INTO nx_growth_voucher(voucher_id,voucher_name,voucher_type,amount_usd,audience,status) VALUES(?,?,'FIXED',?,'ALL','ACTIVE')",voucher,h.run+" isolated full discount",h.db.product(life.buy,false).get("price_usdt"));
            h.db.write("INSERT INTO nx_growth_voucher_grant(grant_id,grant_key,voucher_id,user_id,source_type,source_id,operator,reason) VALUES(?,?,?,?,'ISOLATED_FIXTURE',?,'isolated-test','Explicit coupon ownership fixture')",grant,grant,voucher,buyer,grant);
            var quote=life.quotes.quote(buyer,values("items",List.of(values("productNo",life.buy,"quantity",1)),"voucherId",voucher,"activityId",activity,"clientCapabilities",List.of("PROMOTION_QUOTE_V1","ORDER_ITEM_QUANTITIES_V1","PROMOTION_REWARDS_V1")));
            assertEquals(0,amount(quote.get("amountUsdt")).signum());
            var created=create.createOrder(buyer,null,null,life.buy,1,voucher,text(quote.get("quoteId")),id("CREATE"));assertEquals(0,created.getCode(),created.getMessage());
            String order=text(created.getData().get("orderNo"));orders.add(order);assertEquals("USED",h.db.requiredRow("SELECT status FROM nx_growth_voucher_grant WHERE grant_id=? AND used_order_no=?",grant,order).get("status"));
        }
        var settled=pay.pay(buyer,orders.get(0),id("PAY"));assertEquals(0,settled.getCode(),settled.getMessage());
        var actual=h.db.order(orders.get(0),false);assertEquals("PAID",actual.get("payment_status"));assertEquals(0,decimal(actual.get("amount_usdt")).signum());assertTrue(text(actual.get("payment_no")).startsWith("PAY-VOUCHER-"));
        assertEquals(0,balance.compareTo(decimal(h.db.requiredRow("SELECT usdt_available FROM nx_user_wallet WHERE user_id=?",buyer).get("usdt_available"))));
        assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_user_device WHERE user_id=? AND source_order_no=?",buyer,orders.get(0)));
        assertEquals(1,life.obligations(orders.get(0)).size());assertEquals("ISSUED",life.rewards.issue(life.obligations(orders.get(0)).get(0),id("VOUCHERREWARD")).get("state"));
        assertThrows(RuntimeException.class,()->life.quote(buyer,second,1),"Zero-cash successful settlement consumes first purchase at quote");
        var rejected=assertThrows(ffdd.opsconsole.shared.exception.BizException.class,()->pay.pay(buyer,orders.get(1),id("SECONDVOUCHERPAY")),"Previously reserved second activity must re-arbitrate first purchase");
        assertEquals("PROMOTION_PAYMENT_ELIGIBILITY_LOST",rejected.getMessage());
        assertEquals("PENDING",h.db.order(orders.get(1),false).get("payment_status"));assertTrue(life.obligations(orders.get(1)).isEmpty());
        record("canonical-zero-voucher-cross-activity-first-purchase",values("buyer",buyer,"settledOrder",orders.get(0),"blockedOrder",orders.get(1),"paymentRail","VOUCHER","principalDebit","0.000000","canonicalCreateAndPayment",true));
    }
    Map<String,Object> referral(Map<String,Object> common,boolean restore){
        var contract=h.contract(life.buy,life.coinSpec(),firstPolicy(common,restore));contract.put("category","REFERRAL");contract.put("template","DIRECT_REFERRAL");
        var audience=copy(map(contract.get("buyerAudience")));contract.put("inviterAudience",copy(audience));audience.put("purchaseHistory","NEVER_PAID");contract.put("buyerAudience",audience);
        var rule=copy(maps(contract.get("rules")).get(0));var inviter=copy(map(rule.get("buyerReward")));inviter.put("rewardRuleId","inviter");inviter.put("beneficiaryRole","DIRECT_INVITER");rule.put("inviterReward",inviter);contract.put("rules",List.of(rule));return contract;
    }
    void rankSnapshot(Map<String,Object> common){
        var contract=referral(common,false);
        var buyerAudience=copy(map(contract.get("buyerAudience")));buyerAudience.put("rankIds",List.of("V1"));contract.put("buyerAudience",buyerAudience);
        var inviterAudience=copy(map(contract.get("inviterAudience")));inviterAudience.put("rankIds",List.of("V1"));contract.put("inviterAudience",inviterAudience);
        String activity=h.publish(contract);long inviter=life.user(),buyer=life.user();
        h.db.write("UPDATE nx_user SET v_rank='V1' WHERE id IN (?,?)",buyer,inviter);h.db.write("UPDATE nx_user SET sponsor_user_id=? WHERE id=?",inviter,buyer);
        String order=life.create(buyer,life.quote(buyer,activity,1),1);
        var snapshot=parse(h.db.reservations(order,false).get(0).get("snapshot_json"));var qualification=map(snapshot.get("qualification"));
        assertEquals("V1",map(qualification.get("buyer")).get("rank"));assertEquals("V1",map(qualification.get("directInviter")).get("rank"));
        h.db.write("UPDATE nx_user SET v_rank='V2' WHERE id IN (?,?)",buyer,inviter);
        life.pay(buyer,order);assertEquals(2,life.obligations(order).size());
        for(String id:life.obligations(order))assertEquals("ISSUED",life.rewards.issue(id,id("RANK")).get("state"));
        assertEquals(2,h.db.count("SELECT COUNT(DISTINCT beneficiary_id) FROM nx_promotion_reward WHERE order_no=?",order));
        record("buyer-and-inviter-rank-v1-to-v2-preserves-reserved-awards",values("order",order,"buyer",buyer,"inviter",inviter));
    }
    void dispatchPastBlockedFifty(Map<String,Object> common){
        var contract=h.contract(life.buy,life.coinSpec(),common);contract.put("activityLimit",values("mode","LIMITED","value",100));String activity=h.publish(contract);
        List<String> blocked=new ArrayList<>();int[] counts=new int[3];long[] healthyAccount=new long[1];String[] healthyReward=new String[1];
        life.tx.executeWithoutResult(tx->{
            for(int i=0;i<50;i++){
                long user=life.user();String order=life.create(user,life.quote(user,activity,1),1);life.pay(user,order);String reward=life.obligations(order).get(0);blocked.add(reward);
                if(i%3==0)h.db.write("UPDATE nx_user SET status='FROZEN' WHERE id=?",user);
                else if(i%3==1){String ticket=id("QHOLD");
                    h.db.write("INSERT INTO nx_audit_operation_ticket(operation_id,action,object_text,before_value,after_value,operator_name,operator_role,operation_type,amplifies,sos,time_label,mine,role_gate,reason,status,command_json,source_domain) VALUES(?,'refund',?,'PAID','REFUNDED','fixture','fixture','e4_order_refund',0,0,'fixture',0,'fixture','Explicit isolated refund request','pending',?,'E')",ticket,order,json(values("domain","E","op","e4_order_refund","params",values("orderNo",order))));
                    life.orders.holdA2Refund(ticket,order);
                }else h.db.write("UPDATE nx_promotion_reward SET ready_at=DATE_ADD(NOW(6),INTERVAL 1 DAY) WHERE obligation_id=?",reward);
                counts[i%3]++;
            }
            long healthy=life.user();String order=life.create(healthy,life.quote(healthy,activity,1),1);life.pay(healthy,order);String id=life.obligations(order).get(0);
            healthyAccount[0]=healthy;healthyReward[0]=id;
            assertEquals(51,h.db.count("SELECT COUNT(*) FROM nx_promotion_reward WHERE activity_id=?",activity));
            List<String> selected=life.rewards.pending(50);assertTrue(selected.contains(id),"The healthy 51st obligation must fit after blocked rows are excluded");assertTrue(Collections.disjoint(selected,blocked));
        });
        new PromotionRewardScheduler(life.rewards,h.proxy(new PromotionAvailabilityService(h.db,h.audit))).dispatch();
        assertEquals("ISSUED",life.rewards.get(healthyAccount[0],healthyReward[0],false).get("state"));
        for(String blockedId:blocked)assertEquals("PENDING",life.rewards.get(null,blockedId,false).get("state"));
        assertEquals(counts[1],h.db.count("SELECT COUNT(*) FROM nx_promotion_refund_hold h JOIN nx_promotion_reward r ON r.order_no=h.order_no WHERE r.activity_id=? AND h.status='HELD'",activity));
        record("fifty-blocked-ahead-of-one-healthy-automatic-dispatch",values("activity",activity,"frozen",counts[0],"held",counts[1],"notMature",counts[2],"healthyReward",healthyReward[0]));
    }
    void quotaRestoration(Map<String,Object> common,boolean restore){
        var contract=referral(common,restore);var one=values("mode","LIMITED","value",1);contract.put("activityLimit",one);contract.put("perPersonLimit",values("buyer",one,"directInviter",one));
        var rule=copy(maps(contract.get("rules")).get(0));rule.put("maxGroupsPerPerson",one);contract.put("rules",List.of(rule));
        String activity=h.publish(contract);long inviter=life.user(),buyer=life.user();h.db.write("UPDATE nx_user SET sponsor_user_id=? WHERE id=?",inviter,buyer);
        String order=life.create(buyer,life.quote(buyer,activity,1),1);life.pay(buyer,order);
        var rows=h.db.list("SELECT obligation_id,beneficiary_role FROM nx_promotion_reward WHERE order_no=? ORDER BY beneficiary_role",order);
        String issued=text(rows.stream().filter(r->"BUYER".equals(r.get("beneficiary_role"))).findFirst().orElseThrow().get("obligation_id"));
        assertEquals("ISSUED",life.rewards.issue(issued,id("QUOTAISSUE")).get("state"));life.refund(buyer,order);
        assertEquals(1,h.db.count("SELECT used_orders FROM nx_promotion WHERE activity_id=?",activity));assertNull(h.db.requiredRow("SELECT quota_restored_at FROM nx_promotion_order_receipt WHERE order_no=?",order).get("quota_restored_at"));
        assertThrows(RuntimeException.class,()->life.quote(buyer,activity,1));
        assertEquals("REVERSED",life.rewards.reverseRefund(issued,id("QUOTAREVERSE")).get("state"));
        for(int repeat=0;repeat<2;repeat++){
            life.rewards.reverseRefund(issued,id("REPLAY"));
            life.tx.executeWithoutResult(tx->{life.orders.lockOrderParticipants(order);h.db.order(order,true);life.orders.confirmE4Refund(order,"E4-REFUND-"+order);life.orders.restoreUsageAfterRefund(order);});
        }
        long used=restore?0:1;assertEquals(used,h.db.count("SELECT used_orders FROM nx_promotion WHERE activity_id=?",activity));
        assertEquals(2*used,h.db.count("SELECT COALESCE(SUM(used_orders),0) FROM nx_promotion_usage WHERE activity_id=? AND rule_id=''",activity));
        assertEquals(2*used,h.db.count("SELECT COALESCE(SUM(used_groups),0) FROM nx_promotion_usage WHERE activity_id=? AND rule_id<>''",activity));
        assertEquals(restore,h.db.requiredRow("SELECT quota_restored_at FROM nx_promotion_order_receipt WHERE order_no=?",order).get("quota_restored_at")!=null);
        assertEquals(restore?1:0,h.db.count("SELECT COUNT(*) FROM nx_audit_log WHERE resource_id=? AND action='PROMOTION_REFUND_QUOTA_RESTORED'",order));
        if(restore){String second=life.create(buyer,life.quote(buyer,activity,1),1);assertEquals(2,h.db.count("SELECT COUNT(*) FROM nx_promotion_reservation WHERE order_no=?",second));}
        else assertThrows(RuntimeException.class,()->life.quote(buyer,activity,1));
        record("whole-refund-restoration-policy-"+restore,values("order",order,"activity",activity,"usedOrdersAfterRefund",used,"duplicateCommands",4));
    }
    void unrecoveredCountsStayConsumed(Map<String,Object> common){
        var contract=h.contract(life.buy,life.coinSpec(),firstPolicy(common,true));contract.put("template","FIRST_PURCHASE");var audience=copy(map(contract.get("buyerAudience")));audience.put("purchaseHistory","NEVER_PAID");contract.put("buyerAudience",audience);contract.put("activityLimit",values("mode","LIMITED","value",1));
        String activity=h.publish(contract);long buyer=life.user();String order=life.create(buyer,life.quote(buyer,activity,1),1);life.pay(buyer,order);String id=life.obligations(order).get(0);life.rewards.issue(id,id("CONSUME"));
        h.db.write("UPDATE nx_user_wallet SET nex_available=nex_available-1,version=version+1 WHERE user_id=? AND nex_available>=1",buyer);
        life.refund(buyer,order);assertEquals("MANUAL_REVIEW",life.rewards.reverseRefund(id,id("PARTIAL")).get("state"));
        assertNull(h.db.requiredRow("SELECT quota_restored_at FROM nx_promotion_order_receipt WHERE order_no=?",order).get("quota_restored_at"));assertEquals(1,h.db.count("SELECT used_orders FROM nx_promotion WHERE activity_id=?",activity));assertThrows(RuntimeException.class,()->life.quote(buyer,activity,1));
        var unrelated=copy(contract);unrelated.put("name",h.run+" later unlimited first purchase");unrelated.put("activityLimit",values("mode","UNLIMITED"));String another=h.publish(unrelated);
        assertThrows(RuntimeException.class,()->life.quote(buyer,another,1),"Unrecovered old reward must not restore first-purchase through a fresh activity");
        record("unrecovered-reward-does-not-restore-quota",values("order",order,"reward",id,"state","MANUAL_REVIEW"));
    }
    Map<String,Object> raceAfterOldSnapshot(long buyer,String reward,Runnable writer,Supplier<Map<String,Object>> reader) throws Exception{
        CountDownLatch snapshot=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();var pending=new AtomicReference<Future<Map<String,Object>>>();
        try{
            life.tx.executeWithoutResult(tx->{
                h.db.user(buyer,true);
                pending.set(executor.submit(()->life.tx.execute(inner->{h.db.requiredRow("SELECT status FROM nx_promotion_reward WHERE obligation_id=?",reward);snapshot.countDown();return reader.get();})));
                try{assertTrue(snapshot.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}
                writer.run();
            });
            return pending.get().get(30,TimeUnit.SECONDS);
        }finally{executor.shutdownNow();}
    }
    Map<String,Object> deviceSpec(){return values("rewardRuleId","gift","beneficiaryRole","BUYER","type","DEVICE","giftProductNo",life.gift,"quantity",1,"deviceRightsProfile",h.devicePolicy(life.gift));}
    void usedGiftCurrentRead(Map<String,Object> common) throws Exception{
        String activity=h.publish(h.contract(life.buy,deviceSpec(),common));long buyer=life.user();String order=life.create(buyer,life.quote(buyer,activity,1),1);life.pay(buyer,order);String reward=life.obligations(order).get(0);
        life.rewards.issue(reward,id("GIFTISSUE"));long device=number(h.db.requiredRow("SELECT device_id FROM nx_promotion_device_receipt WHERE obligation_id=?",reward).get("device_id"));life.refund(buyer,order);
        BigDecimal stock=decimal(h.db.product(life.gift,false).get("stock"));
        var result=raceAfterOldSnapshot(buyer,reward,()->h.db.write("INSERT INTO nx_compute_task(task_no,user_id,user_device_id,task_type,client_name,status,started_at,created_at,updated_at) VALUES(?,?,?,'COMPUTE','isolated committed task fact','RUNNING',NOW(6),NOW(6),NOW(6))",id("TASK"),buyer,device),()->life.rewards.reverseRefund(reward,id("USEDRACE")));
        assertEquals("MANUAL_REVIEW",result.get("state"));assertEquals("OWNED",h.db.requiredRow("SELECT ownership_status FROM nx_user_device WHERE id=?",device).get("ownership_status"));assertEquals(0,stock.compareTo(decimal(h.db.product(life.gift,false).get("stock"))));
        record("rr-task-commits-before-gift-recovery-lock",values("order",order,"reward",reward,"device",device,"retainedOwnership",true,"inventoryReturned",false));
    }
    void reconcileCurrentRead(Map<String,Object> common) throws Exception{
        for(String asset:List.of("USDT","NEX","DEVICE")){
            var spec="DEVICE".equals(asset)?deviceSpec():values("rewardRuleId","coin","beneficiaryRole","BUYER","type",asset,"calculation","FIXED","amount","1.000001","assetPolicy",h.assetPolicy(asset));
            String activity=h.publish(h.contract(life.buy,spec,common));long buyer=life.user();String order=life.create(buyer,life.quote(buyer,activity,1),1);life.pay(buyer,order);String reward=life.obligations(order).get(0);
            var result=raceAfterOldSnapshot(buyer,reward,()->life.rewards.issue(reward,id("ISSUERACE")),()->life.rewards.reconcile(reward,id("RECONCILERACE")));
            assertEquals("ISSUED",result.get("state"));assertNotNull(result.get("assetReceipt"));
            if("DEVICE".equals(asset))assertEquals(1,((List<?>)map(result.get("assetReceipt")).get("deviceIds")).size());
            else assertNotNull(map(result.get("assetReceipt")).get("earningsEntryNo"));
            assertEquals("ISSUED",life.rewards.get(buyer,reward,false).get("state"));
            record("rr-issued-before-reconcile-lock-"+asset,values("order",order,"reward",reward,"state","ISSUED"));
        }
    }
    void adminRevisionCurrentRead(Map<String,Object> common) throws Exception{
        for(String asset:List.of("USDT","NEX","DEVICE")){
            var spec="DEVICE".equals(asset)?deviceSpec():values("rewardRuleId","coin","beneficiaryRole","BUYER","type",asset,"calculation","FIXED","amount","1.000001","assetPolicy",h.assetPolicy(asset));
            String activity=h.publish(h.contract(life.buy,spec,common));long buyer=life.user();String order=life.create(buyer,life.quote(buyer,activity,1),1);life.pay(buyer,order);String reward=life.obligations(order).get(0);
            long oldRevision=number(life.rewards.get(buyer,reward,false).get("revision"));String command=id("STALEADMIN");
            var request=values("expectedRevision",oldRevision,"reason","隔离事务等待后校验原奖励版本，不能核账过时状态","evidenceRefs",List.of(h.evidence));
            var snapshot=new CountDownLatch(1);var db=org.mockito.Mockito.spy(h.db);
            org.mockito.Mockito.doAnswer(call->{var result=call.callRealMethod();snapshot.countDown();return result;}).when(db).requiredRow("SELECT order_no FROM nx_promotion_reward WHERE obligation_id=?",reward);
            var admin=new PromotionAdminService(db,h.validator,h.resolver,h.policies,h.natives,new PromotionEvaluationService(h.db),life.quotes,life.rewards,life.orders,h.idempotency,h.audit,h.a2,null);
            var executor=Executors.newSingleThreadExecutor();var pending=new AtomicReference<Future<Map<String,Object>>>();ExecutionException failure;
            try{
                life.tx.executeWithoutResult(tx->{h.db.user(buyer,true);pending.set(executor.submit(()->{h.authenticate(h.checker);return admin.command("reconcileReward",reward,command,request,()->admin.rewardAction(reward,"reconcile",request,command));}));
                    try{assertTrue(snapshot.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}life.rewards.issue(reward,id("ADMINISSUERACE"));});
                failure=assertThrows(ExecutionException.class,()->pending.get().get(30,TimeUnit.SECONDS));
            }finally{executor.shutdownNow();}
            assertInstanceOf(ffdd.opsconsole.shared.exception.BizException.class,failure.getCause());assertEquals("PROMOTION_CONCURRENT_CHANGE",failure.getCause().getMessage());
            assertEquals("ISSUED",life.rewards.get(buyer,reward,false).get("state"));assertEquals(oldRevision+2,number(life.rewards.get(buyer,reward,false).get("revision")));
            record("rr-admin-revision-rejects-stale-reconcile-"+asset,values("order",order,"reward",reward,"oldRevision",oldRevision,"state","ISSUED"));
        }
    }
    void scanBeyondHealthyHundred(Map<String,Object> common){
        var contract=h.contract(life.buy,life.coinSpec(),common);contract.put("shortagePolicy","ACTIVITY_PAUSE");contract.put("activityLimit",values("mode","LIMITED","value",1));
        List<String> activities=new ArrayList<>();for(int i=0;i<101;i++){var draft=copy(contract);draft.put("name",h.run+" scan "+i);activities.add(h.publish(draft));}
        activities.sort(String::compareTo);String last=activities.get(activities.size()-1);var availability=h.proxy(new PromotionAvailabilityService(h.db,h.audit));
        assertFalse(availability.candidates().contains(last),"Regression requires the shortage activity to be beyond the first page");
        long buyer=life.user();life.create(buyer,life.quote(buyer,last,1),1);new PromotionRewardScheduler(life.rewards,availability).dispatch();assertEquals("PAUSED",h.db.activity(last,false).get("status"));
        for(String id:activities.subList(0,100))assertEquals("ACTIVE",h.db.activity(id,false).get("status"));
        record("capacity-scan-after-one-hundred-healthy-activities",values("approvedActivities",activities.size(),"lastActivity",last,"lastState","PAUSED","healthyRetained",100));
    }
    void record(String scenario,Map<String,Object> facts){evidence.add(values("scenario",scenario,"facts",facts));write(false);}
    void write(boolean completed){try{Files.writeString(Path.of("C:/Users/jason/.codex/workflow-runs/growth-promotions-20261007/"+evidenceFile),json(values("completed",completed,"scope","Real promotion service/SQL; isolated canonical source fixtures","run",h.run,"evidence",evidence)));}catch(Exception e){throw new IllegalStateException(e);}}
}
