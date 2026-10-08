package ffdd.opsconsole.promotion;

import ffdd.opsconsole.promotion.application.*;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="GROWTH_PROMOTION_RUNTIME",matches="1")
class PromotionDisclosureMySqlTest {
    @Test void historicalBenefitsAndActionOptionsUseOriginalApprovedFacts() throws Exception {
        var f=new PromotionLifecycleMySqlTest();f.setup();var h=f.h;var common=h.commonPolicies();
        ReflectionTestUtils.setField(h.admin,"orders",f.orders);ReflectionTestUtils.setField(h.admin,"rewards",f.rewards);
        var publicApi=new PromotionPublicService(h.db,new PromotionEvaluationService(h.db),f.rewards);
        var evidence=new ArrayList<Object>();
        for(String asset:List.of("USDT","NEX","DEVICE")){
            var spec="DEVICE".equals(asset)?values("rewardRuleId","reward-buyer","beneficiaryRole","BUYER","type",asset,"giftProductNo",f.gift,"quantity",1,"deviceRightsProfile",h.devicePolicy(f.gift))
                :values("rewardRuleId","reward-buyer","beneficiaryRole","BUYER","type",asset,"calculation","FIXED","amount","1.000001","assetPolicy",h.assetPolicy(asset));
            var contract=h.contract(f.buy,spec,common);String activity=h.publish(contract);long buyer=f.user();
            var visible=publicApi.get(buyer,activity);h.validator.validate("PublicPromotion",visible);
            var expected=map(map(maps(visible.get("rewardRules")).get(0).get("buyerReward")).get("disclosure"));
            assertEquals(contract.get("terms"),expected.get("terms"));assertNotNull(expected.get("benefitDescription"));
            assertEquals("DEVICE".equals(asset),expected.get("deviceRights")!=null);
            assertEquals("DEVICE".equals(asset)?h.db.product(f.gift,false).get("name"):null,expected.get("deviceName"));
            assertFalse(json(expected).contains("contentHash"));assertFalse(json(expected).contains("approvedBy"));
            var quote=f.quote(buyer,activity,1);h.validator.validate("Quote",quote);
            assertEquals(expected,maps(quote.get("expectedRewards")).get(0).get("disclosure"));
            String order=f.create(buyer,quote,1);f.pay(buyer,order);String reward=f.obligations(order).get(0);
            var purchased=maps(f.orders.orderProjection(buyer,order).get("items"));assertEquals(1,purchased.size());
            assertEquals(f.buy,purchased.get(0).get("productNo"));assertEquals(1,number(purchased.get(0).get("quantity")));
            assertEquals(h.db.product(f.buy,false).get("name"),purchased.get(0).get("productName"));
            assertEquals(maps(f.orders.orderProjection(buyer,order).get("rewards")).get(0).get("lineId"),purchased.get(0).get("lineId"));
            assertEquals(expected,maps(f.orders.orderProjection(buyer,order).get("rewards")).get(0).get("disclosure"));
            assertEquals(expected,f.rewards.get(buyer,reward,false).get("disclosure"));
            // Publish a genuinely approved newer version; historical reads must not use its changed terms.
            h.authenticate(h.maker);var action=values("expectedRevision",h.db.activity(activity,false).get("revision"),"reason","建立新的条款版本验证历史订单","evidenceRefs",List.of(h.evidence));
            h.admin.command("createDraftVersion",activity,id("VERSION"),action,()->h.admin.newVersion(activity,action,false));
            var edit=values("expectedRevision",1,"reason","调整未来活动条款验证隔离","draft",values("title",PromotionRuntimeHarness.localized("New activity title"),"terms",PromotionRuntimeHarness.localized("New activity terms")));
            h.admin.command("saveDraft",activity,id("EDIT"),edit,()->h.admin.save(activity,edit));
            long revision=2;
            for(String stage:List.of("submit","approve","publish")){
                h.authenticate("submit".equals(stage)?h.maker:"approve".equals(stage)?h.checker:h.publisher);
                var transition=values("expectedRevision",revision++,"version",2,"reason","批准新条款的真实版本验证","evidenceRefs",List.of(h.evidence));
                h.admin.command(stage+"Promotion",activity,id("STAGE"),transition,()->h.admin.versionAction(activity,stage,transition));
            }
            assertNotEquals(expected.get("terms"),publicApi.get(buyer,activity).get("terms"));
            assertEquals(expected,maps(f.orders.orderProjection(buyer,order).get("rewards")).get(0).get("disclosure"));
            assertEquals(expected,f.rewards.get(buyer,reward,false).get("disclosure"));
            // Pre-addition receipts are enriched only from original reservation snapshots, without rewriting history.
            if("DEVICE".equals(asset)){
                var original=h.db.product(f.gift,false);h.db.write("UPDATE nx_product SET name='Changed catalog name',store_visible=0 WHERE product_no=?",f.gift);
                try{
                    assertEquals(expected.get("deviceName"),map(map(maps(publicApi.get(buyer,activity).get("rewardRules")).get(0).get("buyerReward")).get("disclosure")).get("deviceName"));
                    assertEquals(expected,f.rewards.get(buyer,reward,false).get("disclosure"));
                    assertEquals(expected,maps(f.orders.orderProjection(buyer,order).get("rewards")).get(0).get("disclosure"));
                }finally{h.db.write("UPDATE nx_product SET name=?,store_visible=? WHERE product_no=?",original.get("name"),original.get("store_visible"),f.gift);}
            }
            String persisted=text(h.db.requiredRow("SELECT projection_json FROM nx_promotion_order_receipt WHERE order_no=?",order).get("projection_json"));
            var old=parse(persisted);old.remove("items");var oldRewards=maps(old.get("rewards"));oldRewards.forEach(r->r.remove("disclosure"));old.put("rewards",oldRewards);
            h.db.write("UPDATE nx_promotion_order_receipt SET projection_json=? WHERE order_no=?",json(old),order);
            assertEquals(expected,maps(f.orders.orderProjection(buyer,order).get("rewards")).get(0).get("disclosure"));
            assertEquals(purchased,maps(f.orders.orderProjection(buyer,order).get("items")));
            assertEquals(old,parse(h.db.requiredRow("SELECT projection_json FROM nx_promotion_order_receipt WHERE order_no=?",order).get("projection_json")));
            h.db.write("UPDATE nx_promotion_order_receipt SET projection_json=? WHERE order_no=?",persisted,order);
            assertTrue(maps(f.rewards.get(null,reward,true).get("dispositionOptions")).isEmpty());
            f.rewards.issue(reward,id("ISSUE"));
            var issuedReceipt=map(f.rewards.get(buyer,reward,false).get("assetReceipt"));
            var row=h.db.requiredRow("SELECT * FROM nx_promotion_reward WHERE obligation_id=?",reward);
            var params=values("obligationId",reward,"expectedRevision",number(row.get("revision")),"snapshotHash",row.get("snapshot_hash"),"action","REVERSE","asset",asset);
            String approval=id("CORRECTION");var command=values("domain","H","op","promotion_reward_correction","params",params);
            // The A2 ticket is an explicit isolated approval fixture; the domain approval/audit is executed below.
            h.db.write("INSERT INTO nx_audit_operation_ticket(operation_id,action,object_text,before_value,after_value,operator_name,operator_role,operation_type,amplifies,sos,time_label,mine,role_gate,reason,status,command_json,source_domain,decided_at) VALUES(?,'correction',?,'ISSUED','REVERSED','fixture','fixture','promotion_reward_correction',0,0,'fixture',0,'fixture','Isolated approved correction fixture','approved',?,'H',NOW(6))",approval,reward,json(command));
            assertTrue(maps(f.rewards.get(null,reward,true).get("dispositionOptions")).isEmpty(),"Ticket alone is not an approved domain fact");
            h.authenticate(h.checker);f.tx.executeWithoutResult(tx->assertEquals(0,h.admin.approveCorrection(params,new AuditReplayContext("checker","独立批准奖励调整的审计事实",id("APPROVE"))).getCode()));
            var adminRead=f.rewards.get(null,reward,true);h.validator.validate("RewardAdmin",adminRead);
            var options=maps(adminRead.get("dispositionOptions"));assertEquals(1,options.size());
            var basis=map(options.get(0).get("basis"));assertEquals(approval,basis.get("approvalOperationId"));assertEquals(hash(command),basis.get("approvedPayloadHash"));
            f.rewards.reconcile(reward,id("RECONCILE"));assertTrue(maps(f.rewards.get(null,reward,true).get("dispositionOptions")).isEmpty(),"Stale revision cannot remain selectable");
            assertEquals(issuedReceipt,f.rewards.get(buyer,reward,false).get("assetReceipt"),"Reconciliation must preserve original issuance time and receipt");
            f.refund(buyer,order);var refunded=f.rewards.get(null,reward,true);h.validator.validate("RewardAdmin",refunded);
            assertEquals(issuedReceipt,refunded.get("assetReceipt"),"Refund bookkeeping must not change original issuance time");
            var refundBasis=map(maps(refunded.get("dispositionOptions")).get(0).get("basis"));assertEquals("WHOLE_ORDER_REFUND",refundBasis.get("type"));
            assertEquals(h.db.requiredRow("SELECT refund_no FROM nx_promotion_refund_hold WHERE order_no=? AND status='EXECUTED'",order).get("refund_no"),refundBasis.get("refundNo"));
            assertFalse(f.rewards.get(buyer,reward,false).containsKey("dispositionOptions"));
            evidence.add(values("asset",asset,"order",order,"reward",reward,"oldVersion",1,"currentVersion",2,"historicalDisclosureUnchanged",true,"approvalRequiresAuditAndCurrentRevision",true,"refundBasisUsesActualRefundNo",true));
        }
        Files.writeString(Path.of("C:/Users/jason/.codex/workflow-runs/growth-promotions-20261007/promotion-disclosure-runtime.json"),json(values("completed",true,"run",h.run,"checks",evidence)));
    }
}
