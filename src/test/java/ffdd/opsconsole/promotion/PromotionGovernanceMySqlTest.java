package ffdd.opsconsole.promotion;

import ffdd.opsconsole.promotion.application.*;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.core.context.SecurityContextHolder;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="GROWTH_PROMOTION_RUNTIME",matches="1")
class PromotionGovernanceMySqlTest {
    @Test void realDraftApprovalPublishingAndRetainedCommandRecovery() throws Exception {
        var h=new PromotionRuntimeHarness();List<String> passed=new ArrayList<>();
        try{
            var incomplete=values("reason","隔离草稿真实持久化验证说明","draft",values("category","PROMOTION","template","SKU_GIFT","rules",List.of(),"budgets",List.of()));String key=h.run+"-draft";
            var first=h.admin.command("createPromotion","promotions",key,incomplete,()->h.admin.create(incomplete));
            var replay=h.admin.command("createPromotion","promotions",key,incomplete,()->{throw new AssertionError("replay must not execute");});assertEquals(hash(first),hash(replay));
            String activity=text(map(first.get("resource")).get("id"));var actual=h.admin.get(activity);assertEquals(List.of(),map(map(actual.get("current")).get("draft")).get("rules"));
            assertEquals(hash(first),hash(h.admin.recover(key,"createPromotion","promotions")));passed.add("incomplete-draft-written-read-back-and-original-command-replayed");
            var changed=copy(incomplete);changed.put("reason","相同幂等号不同内容必须拒绝说明");assertThrows(BizException.class,()->h.admin.command("createPromotion","promotions",key,changed,()->h.admin.create(changed)));
            h.authenticate(h.checker);assertEquals("NOT_FOUND",h.admin.recover(key,"createPromotion","promotions").get("status"));
            h.authenticate(h.maker,"growth_promotion_read");assertThrows(BizException.class,()->h.admin.recover(key,"createPromotion","promotions"));passed.add("cross-actor-and-revoked-operation-permission-cannot-recover-command");
            h.authenticate(h.maker);assertThrows(BizException.class,()->h.transition(activity,"submit",h.maker,1));assertEquals("DRAFT",h.admin.version(activity,1).get("state"));passed.add("incomplete-submit-rejected-without-changing-draft");

            String product=h.run+"-sku";
            h.jdbc.update("INSERT INTO nx_product(product_no,name,product_type,tier,status,price_usdt,hashrate,estimated_daily_usdt,daily_nex,stock,inventory_mode,store_visible,generation,gpu_model,vram_total_gb) VALUES(?,?,'DEVICE','Entry','ACTIVE',50,10,1,2,100,'FINITE',1,1,'fixture-gpu',8)",product,product);
            h.jdbc.update("INSERT INTO nx_admin_device_sku(sku_id,name,power_text,datacenter,price,daily_earn,daily_earn_nex,purchase_gate_generation,status) VALUES(?,?,'100W','isolated-test',50,1,2,1,'active')",product,product);
            var common=h.commonPolicies();var device=h.devicePolicy(product);
            var reward=values("rewardRuleId","gift1","beneficiaryRole","BUYER","type","DEVICE","giftProductNo",product,"quantity",1,"deviceRightsProfile",device);
            var pendingContent=copy(map(h.policies.get(text(device.get("policyId")),1).get("content")));
            var pendingRequest=values("reason","未批准政策必须阻断提交验收说明","content",pendingContent,"evidenceRefs",List.of(h.evidence));
            var pending=h.admin.command("createPolicy","promotion-policies",h.run+"-unapproved",pendingRequest,()->h.policies.create(null,pendingRequest));
            String pendingId=text(map(pending.get("resource")).get("id")).split(":")[0];
            var unapprovedReward=copy(reward);unapprovedReward.put("deviceRightsProfile",values("policyId",pendingId,"version",1,"contentHash",hash(pendingContent)));
            var rejectedDraft=values("reason","未批准政策草稿允许存储验收说明","draft",h.contract(product,unapprovedReward,common));
            var rejectedActivity=h.admin.command("createPromotion","promotions",h.run+"-unapproved-draft",rejectedDraft,()->h.admin.create(rejectedDraft));
            String rejectedId=text(map(rejectedActivity.get("resource")).get("id"));
            var failure=assertThrows(BizException.class,()->h.transition(rejectedId,"submit",h.maker,1));assertEquals(422,failure.getCode());assertTrue(failure.getMessage().startsWith("PROMOTION_FIELD_INVALID:policies"));assertEquals("DRAFT",h.admin.version(rejectedId,1).get("state"));passed.add("unapproved-real-policy-persists-in-draft-but-submit-returns-field-422");
            var contract=h.contract(product,reward,common);String published=h.publish(contract);passed.add("native-e1-rights-and-five-real-policies-approved-then-activity-published");
            var root=h.admin.get(published);assertEquals(1,number(root.get("activeVersion")));assertEquals("PUBLISHED",h.admin.version(published,1).get("state"));
            var action=values("expectedRevision",root.get("revision"),"reason","暂停后发布新版不得自动恢复说明","evidenceRefs",List.of(h.evidence));
            h.admin.command("pausePromotion",published,h.run+"-pause",action,()->h.admin.state(published,"pause",action));
            var newVersion=values("expectedRevision",h.admin.get(published).get("revision"),"reason","暂停期间新建草稿验证隔离说明","evidenceRefs",List.of(h.evidence));
            h.admin.command("createDraftVersion",published,h.run+"-new",newVersion,()->h.admin.newVersion(published,newVersion,false));
            assertEquals("PAUSED",h.admin.get(published).get("state"));assertEquals("PUBLISHED",h.admin.version(published,1).get("state"));assertEquals(2,number(h.admin.get(published).get("draftVersion")));passed.add("paused-new-draft-preserves-active-version-and-state");
            var resume=values("expectedRevision",h.admin.get(published).get("revision"),"reason","恢复必须复核实际库存与预算说明","evidenceRefs",List.of(h.evidence));
            h.jdbc.update("UPDATE nx_product SET stock=0 WHERE product_no=?",product);
            assertThrows(BizException.class,()->h.admin.command("resumePromotion",published,h.run+"-no-stock",resume,()->h.admin.state(published,"resume",resume)));
            assertEquals("PAUSED",h.admin.get(published).get("state"));h.jdbc.update("UPDATE nx_product SET stock=100 WHERE product_no=?",product);
            h.jdbc.update("UPDATE nx_promotion_budget SET reserved=total WHERE activity_id=?",published);
            assertThrows(BizException.class,()->h.admin.command("resumePromotion",published,h.run+"-no-budget",resume,()->h.admin.state(published,"resume",resume)));
            h.jdbc.update("UPDATE nx_promotion_budget SET reserved=0 WHERE activity_id=?",published);
            h.admin.command("resumePromotion",published,h.run+"-resumed",resume,()->h.admin.state(published,"resume",resume));assertEquals("ACTIVE",h.admin.get(published).get("state"));passed.add("resume-rejects-real-exhausted-stock-and-budget-without-resetting-state");

            var submit2=values("expectedRevision",1,"version",2,"reason","新版提交和独立撤回原状态验收","evidenceRefs",List.of(h.evidence));h.authenticate(h.maker);
            h.admin.command("submitPromotion",published,h.run+"-submit2",submit2,()->h.admin.versionAction(published,"submit",submit2));
            var editPending=values("expectedRevision",2,"reason","待审快照不得直接修改验收说明","draft",values("name","should-not-save"));
            assertThrows(BizException.class,()->h.admin.command("saveDraft",published,h.run+"-pending-edit",editPending,()->h.admin.save(published,editPending)));
            var withdraw=values("expectedRevision",2,"version",2,"reason","独立撤回待审配置后继续编辑说明","evidenceRefs",List.of(h.evidence));h.authenticate(h.maker,"growth_promotion_read");
            assertEquals(403,assertThrows(BizException.class,()->h.admin.command("withdrawPromotion",published,h.run+"-withdraw",withdraw,()->h.admin.versionAction(published,"withdraw",withdraw))).getCode());
            h.authenticate(h.maker,"growth_promotion_submit","growth_promotion_read");
            var withdrew=h.admin.command("withdrawPromotion",published,h.run+"-withdraw",withdraw,()->h.admin.versionAction(published,"withdraw",withdraw));
            assertEquals(hash(withdrew),hash(h.admin.command("withdrawPromotion",published,h.run+"-withdraw",withdraw,()->{throw new AssertionError("withdraw replay");})));
            assertEquals("DRAFT",h.admin.version(published,2).get("state"));assertEquals("ACTIVE",h.admin.get(published).get("state"));assertEquals(1,number(h.admin.get(published).get("activeVersion")));
            h.authenticate(h.maker);var renamed=values("expectedRevision",3,"reason","撤回后修改内部名称实际回读说明","draft",values("name",h.run+" candidate"));h.admin.command("saveDraft",published,h.run+"-candidate-name",renamed,()->h.admin.save(published,renamed));
            assertEquals(h.run,map(h.admin.version(published,1).get("draft")).get("name"));assertEquals(h.run+" candidate",map(h.admin.version(published,2).get("draft")).get("name"));assertEquals(h.run,h.admin.get(published).get("name"));
            var resubmit=values("expectedRevision",4,"version",2,"reason","撤回改稿后重新独立提交和批准","evidenceRefs",List.of(h.evidence));h.admin.command("submitPromotion",published,h.run+"-resubmit",resubmit,()->h.admin.versionAction(published,"submit",resubmit));
            h.authenticate(h.checker);var approve=copy(resubmit);approve.put("expectedRevision",5);h.admin.command("approvePromotion",published,h.run+"-approve2",approve,()->h.admin.versionAction(published,"approve",approve));
            h.authenticate(h.maker);var approvedEdit=copy(renamed);approvedEdit.put("expectedRevision",6);assertThrows(BizException.class,()->h.admin.command("saveDraft",published,h.run+"-approved-edit",approvedEdit,()->h.admin.save(published,approvedEdit)));
            var approvedWithdraw=copy(withdraw);approvedWithdraw.put("expectedRevision",6);h.admin.command("withdrawPromotion",published,h.run+"-approved-withdraw",approvedWithdraw,()->h.admin.versionAction(published,"withdraw",approvedWithdraw));
            assertNull(h.admin.version(published,2).get("approvedBy"));assertNull(h.admin.version(published,2).get("approvedAt"));assertEquals("PUBLISHED",h.admin.version(published,1).get("state"));
            assertEquals(2,h.db.count("SELECT COUNT(*) FROM nx_audit_log WHERE action='WITHDRAWPROMOTION' AND resource_id=?",published));passed.add("submit-withdraw-edit-and-approved-withdraw-use-submit-permission-clear-approval-and-preserve-live-version");

            var storage=mock(ObjectStorageService.class);var metrics=h.proxy(new PromotionMetricsService(h.db,h.validator,storage,h.audit));
            var metric=metrics.metrics(published,null,Instant.now().minusSeconds(3600).toString(),Instant.now().plusSeconds(3600).toString(),"Asia/Tokyo");
            assertNull(metric.get("impressions"));assertNull(metric.get("giftCostUsdt"));assertEquals("0.000000",metric.get("grossPaidUsdt"));
            var exportRequest=values("querySnapshot",metric.get("querySnapshot"),"reason","固定快照导出失败不可伪造成功说明");
            var export=h.admin.command("createExport",published,h.run+"-export",exportRequest,()->metrics.createExport(published,exportRequest));String exportId=text(map(export.get("resource")).get("id"));
            doThrow(new BizException(503,"TEST_STORAGE_UNAVAILABLE")).when(storage).put(anyString(),anyString(),any(),anyLong());metrics.generateExports();
            var job=metrics.export(exportId,false);assertEquals("FAILED",job.get("state"));assertNull(job.get("downloadUrl"));assertThrows(BizException.class,()->metrics.export(exportId,true));passed.add("fixed-report-snapshot-persists-and-storage-failure-never-claims-ready");
            assertTrue(h.db.count("SELECT COUNT(*) FROM nx_audit_log WHERE resource_id=? AND action='PROMOTION_COMMAND_SNAPSHOT'",published)>0);passed.add("before-after-full-audit-snapshots-persisted");
            Files.writeString(Path.of("C:/Users/jason/.codex/workflow-runs/growth-promotions-20261007/governance-runtime.json"),json(values("status","PASS","run",h.run,"activityId",published,"checks",passed,"approvedPolicies",common,"approvedDevicePolicy",device,"limitations",List.of("storage failure is injected; live MinIO unavailable test still required","authentication context is isolated test; live RBAC HTTP test still required"))));
        }finally{SecurityContextHolder.clearContext();}
    }
}
