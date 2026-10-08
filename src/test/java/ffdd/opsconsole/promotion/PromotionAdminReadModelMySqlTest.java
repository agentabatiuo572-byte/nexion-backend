package ffdd.opsconsole.promotion;

import ffdd.opsconsole.promotion.application.*;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.core.context.SecurityContextHolder;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="GROWTH_PROMOTION_RUNTIME",matches="1")
class PromotionAdminReadModelMySqlTest {
    @Test void realNonzeroFactsStayAssetSeparatedAndIncludeOffPageActivities() throws Exception {
        var rt=new PromotionLifecycleMySqlTest();rt.setup();var h=rt.h;
        org.springframework.test.util.ReflectionTestUtils.setField(h.admin,"evaluator",new PromotionEvaluationService(h.db));
        org.springframework.test.util.ReflectionTestUtils.setField(h.admin,"quotes",rt.quotes);
        var admin=h.proxy(h.admin);var evidence=new ArrayList<Map<String,Object>>();
        try{
            var common=h.commonPolicies();
            for(String asset:List.of("USDT","NEX","DEVICE")){
                var reward="DEVICE".equals(asset)?values("rewardRuleId","reward-buyer","beneficiaryRole","BUYER","type","DEVICE","giftProductNo",rt.gift,"quantity",1,"deviceRightsProfile",h.devicePolicy(rt.gift)):values("rewardRuleId","reward-buyer","beneficiaryRole","BUYER","type",asset,"calculation","FIXED","amount","2.125001","assetPolicy",h.assetPolicy(asset));
                var contract=h.contract(rt.buy,reward,common);String activity=h.publish(contract);long recipient=rt.user();
                String paid=rt.create(recipient,rt.quote(recipient,activity,1),1);rt.pay(recipient,paid);String obligation=rt.obligations(paid).get(0);assertEquals("ISSUED",rt.rewards.issue(obligation,id("READMODELISSUE")).get("state"));
                long retryAccount=rt.user();String failedOrder=rt.create(retryAccount,rt.quote(retryAccount,activity,1),1);rt.pay(retryAccount,failedOrder);String failed=rt.obligations(failedOrder).get(0),fault=id("READMODELFAULT");
                assertThrows(IllegalStateException.class,()->rt.tx.executeWithoutResult(s->{rt.rewards.issue(failed,fault);throw new IllegalStateException("READ_MODEL_INJECTED_POSTING_ROLLBACK");}));rt.rewards.recordFailure(failed,fault,"READ_MODEL_INJECTED_POSTING_ROLLBACK");
                long waiting=rt.user();String unpaid=rt.create(waiting,rt.quote(waiting,activity,1),1);
                var read=admin.get(activity);var impact=map(read.get("impact"));h.validator.validate("Promotion",read);
                assertEquals(1,number(impact.get("reserved")));assertEquals(1,number(impact.get("issued")));assertEquals(1,number(impact.get("unresolved")));assertEquals(1,number(impact.get("unpaidOrders")));assertEquals(1,number(impact.get("failedRewards")));
                assertEquals(unpaid,maps(impact.get("reservedOrders")).get(0).get("orderNo"));assertEquals(1,number(maps(impact.get("reservedOrders")).get(0).get("version")));assertTrue(Instant.parse(text(maps(impact.get("reservedOrders")).get(0).get("payBy"))).isAfter(Instant.now()));
                var rewards=rt.rewards.page(null,true,null,1,activity,null,null,null,asset);h.validator.validate("RewardAdminPage",rewards);assertEquals(2,number(rewards.get("total")));assertEquals(1,maps(rewards.get("items")).size());assertTrue((Boolean)rewards.get("hasMore"));
                assertEquals(1,number(rt.rewards.page(null,true,null,20,activity,"ISSUED",paid,recipient,asset).get("total")));assertEquals(0,number(rt.rewards.page(null,true,null,20,activity,null,null,waiting,asset).get("total")));
                long quoteCount=h.db.count("SELECT COUNT(*) FROM nx_promotion_quote"),reservationCount=h.db.count("SELECT COUNT(*) FROM nx_promotion_reservation");
                var sampleRequest=values("draft",contract,"items",List.of(values("productNo",rt.buy,"quantity",1)),"sampleAccountId",String.valueOf(recipient));
                h.authenticate(h.maker,"growth_promotion_simulate");
                assertEquals(403,assertThrows(BizException.class,()->admin.simulate(activity,sampleRequest)).getCode());
                var restricted=admin.audiencePreview(activity,values("draft",values("category","PROMOTION","template","SKU_GIFT","buyerAudience",contract.get("buyerAudience"))));
                assertTrue(maps(restricted.get("samples")).isEmpty());assertTrue(number(restricted.get("total"))>0);
                var permissions=new ArrayList<>(h.db.list("SELECT permission_code FROM nx_admin_permission WHERE permission_code LIKE 'growth_promotion_%' AND status=1 AND is_deleted=0").stream().map(r->text(r.get("permission_code"))).toList());permissions.add("user_c1_read");h.authenticate(h.maker,permissions.toArray(String[]::new));
                var simulation=admin.simulate(activity,values("draft",contract,"items",List.of(values("productNo",rt.buy,"quantity",1)),"sampleAccountId",String.valueOf(recipient)));
                assertEquals("ELIGIBLE",simulation.get("eligibility"));assertEquals(1,maps(simulation.get("rewardUnits")).size());assertTrue(maps(simulation.get("resources")).stream().anyMatch(r->"BUDGET".equals(r.get("kind"))&&asset.equals(r.get("asset"))));assertFalse((Boolean)simulation.get("reserved"));assertNull(simulation.get("giftCostUsdt"));
                var activityQuota=maps(simulation.get("quotaImpact")).stream().filter(q->"ACTIVITY_ORDER".equals(q.get("scope"))).findFirst().orElseThrow();assertEquals(3,number(activityQuota.get("usedAndReserved")));assertEquals(1,number(activityQuota.get("required")));
                assertEquals(quoteCount,h.db.count("SELECT COUNT(*) FROM nx_promotion_quote"));assertEquals(reservationCount,h.db.count("SELECT COUNT(*) FROM nx_promotion_reservation"));
                var partial=values("category","PROMOTION","template","SKU_GIFT","buyerAudience",contract.get("buyerAudience"));var preview=admin.audiencePreview(activity,values("draft",partial));assertEquals("COMPLETE",preview.get("completeness"));assertEquals(0,number(preview.get("unknown")));assertTrue(number(preview.get("matched"))>0);assertEquals(number(preview.get("total")),number(preview.get("matched"))+number(preview.get("rejected")));
                assertFalse(maps(preview.get("samples")).isEmpty());
                var metrics=h.proxy(new PromotionMetricsService(h.db,h.validator,null,h.audit));var report=metrics.metrics(activity,null,Instant.now().minusSeconds(3600).toString(),Instant.now().plusSeconds(3600).toString(),"Asia/Tokyo");assertEquals(3,number(report.get("orders")));assertEquals(2,number(report.get("paidOrders")));assertEquals(0,number(report.get("refundedOrders")));assertNull(report.get("conversionRate"));
                evidence.add(values("asset",asset,"activity",activity,"impact",impact,"rewardTotal",rewards.get("total"),"simulatedUnits",simulation.get("rewardUnits"),"reportOrders",report.get("orders"),"reportPaid",report.get("paidOrders")));
            }
            var query=values("query",h.run,"state","ACTIVE");var first=admin.page(query,null,null,1);var summary=map(first.get("summary"));assertEquals(3,number(first.get("total")));for(String key:List.of("reserved","issued","unresolved","unpaidOrders"))assertEquals(3,number(summary.get(key)),key);assertEquals(3,maps(summary.get("budgets")).size());assertEquals(first.get("asOf"),summary.get("asOf"));
            var second=admin.page(query,text(first.get("querySnapshot")),text(first.get("nextCursor")),1);assertEquals(summary,second.get("summary"));assertEquals(1,maps(second.get("items")).size());
            Files.writeString(Path.of("C:/Users/jason/.codex/workflow-runs/growth-promotions-20261007/governance-nonzero-read-model-runtime.json"),json(values("status","PASS","run",h.run,"scope","Real promotion approvals/reservations/asset receipts; canonical purchase/payment facts are explicit isolated fixtures, not HTTP evidence","summary",summary,"facts",evidence)));
        }finally{SecurityContextHolder.clearContext();}
    }
    @Test void nameFiltersAndCrossPageSnapshotRemainOneQueryAfterWrites() throws Exception {
        var h=new PromotionRuntimeHarness();var admin=h.proxy(h.admin);var verified=new ArrayList<String>();
        try{
            String a=create(h,h.run+" Alpha","SKU_GIFT"),b=create(h,h.run+" Beta","SKU_GIFT");create(h,h.run+" Gamma","REPURCHASE");
            var query=values("query",h.run,"category","PROMOTION","template","SKU_GIFT","state","DRAFT","sort","NAME_ASC");
            var first=admin.page(query,null,null,1);h.validator.validate("PromotionPage",first);
            assertEquals(2,number(first.get("total")));assertEquals(a,maps(first.get("items")).get(0).get("activityId"));
            assertEquals(h.run+" Alpha",maps(first.get("items")).get(0).get("name"));
            assertEquals(0,number(map(first.get("summary")).get("reserved")));assertEquals(0,number(map(first.get("summary")).get("issued")));
            String snapshot=text(first.get("querySnapshot"));
            var edit=values("expectedRevision",1,"reason","内部活动名称修改和快照隔离验证","draft",values("name",h.run+" Changed"));
            h.admin.command("saveDraft",b,h.run+"-rename",edit,()->h.admin.save(b,edit));
            create(h,h.run+" Another","SKU_GIFT");
            var next=admin.page(query,snapshot,text(first.get("nextCursor")),1);assertEquals(first.get("asOf"),next.get("asOf"));assertEquals(first.get("summary"),next.get("summary"));assertEquals(2,number(next.get("total")));assertEquals(h.run+" Beta",maps(next.get("items")).get(0).get("name"));
            assertEquals(3,number(admin.page(query,null,null,100).get("total")));verified.add("internal-name-persisted-and-filtered-list-pages-retain-original-rows-total-summary-and-time");
            var mismatched=copy(query);mismatched.put("state","ACTIVE");assertEquals(409,assertThrows(BizException.class,()->admin.page(mismatched,snapshot,null,1)).getCode());
            h.authenticate(h.checker);assertEquals(404,assertThrows(BizException.class,()->admin.page(query,snapshot,null,1)).getCode());
            h.authenticate(h.maker,"growth_promotion_edit");assertEquals(403,assertThrows(BizException.class,()->admin.page(query,snapshot,null,1)).getCode());h.authenticate(h.maker);
            h.jdbc.update("UPDATE nx_promotion_list_snapshot SET as_of=DATE_SUB(NOW(6),INTERVAL 1 HOUR),expires_at=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE snapshot_id=?",snapshot);assertEquals(409,assertThrows(BizException.class,()->admin.page(query,snapshot,null,1)).getCode());verified.add("query-mismatch-cross-actor-revoked-read-permission-and-expired-snapshot-reject");
            var timeQuery=values("query",h.run,"from",Instant.now().minusSeconds(3600).toString(),"to",Instant.now().plusSeconds(3600).toString(),"timezone","Asia/Tokyo");assertEquals(0,number(admin.page(timeQuery,null,null,20).get("total")));
            assertThrows(BizException.class,()->admin.page(values("from",Instant.now().toString()),null,null,20));verified.add("activity-window-intersection-excludes-undated-drafts-and-rejects-incomplete-window");
            Files.writeString(Path.of("C:/Users/jason/.codex/workflow-runs/growth-promotions-20261007/governance-read-model-runtime.json"),json(values("status","PASS","run",h.run,"checks",verified)));
        }finally{SecurityContextHolder.clearContext();}
    }
    private String create(PromotionRuntimeHarness h,String name,String template){
        var request=values("reason","隔离列表和内部活动名称验收说明","draft",values("category","PROMOTION","template",template,"name",name));
        return text(map(h.admin.command("createPromotion","promotions",h.run+id("list"),request,()->h.admin.create(request)).get("resource")).get("id"));
    }
}
