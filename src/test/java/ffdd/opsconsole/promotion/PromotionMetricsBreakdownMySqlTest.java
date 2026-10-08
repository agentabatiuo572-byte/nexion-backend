package ffdd.opsconsole.promotion;

import ffdd.opsconsole.promotion.application.PromotionMetricsService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.*;
import io.minio.MinioClient;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.core.context.SecurityContextHolder;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real governance, MySQL reward facts and S3-protocol test storage; commerce source facts use the lifecycle fixture. */
@EnabledIfEnvironmentVariable(named="GROWTH_PROMOTION_RUNTIME",matches="1")
class PromotionMetricsBreakdownMySqlTest {
    @Test void allAssetsAllDimensionsStablePagesAndCompleteStoredExport() throws Exception {
        var rt=new PromotionLifecycleMySqlTest();rt.setup();var h=rt.h;var checks=new ArrayList<String>();
        try{
            var common=h.commonPolicies();String third=h.run+"-metrics-third";
            rt.cloneCatalogRow("nx_product","product_no",rt.buy,third);rt.cloneCatalogRow("nx_admin_device_sku","sku_id",rt.buy,third);
            var usdt=values("rewardRuleId","reward-usdt","beneficiaryRole","BUYER","type","USDT","calculation","FIXED","amount","2.125001","assetPolicy",h.assetPolicy("USDT"));
            var nex=values("rewardRuleId","reward-nex","beneficiaryRole","BUYER","type","NEX","calculation","FIXED","amount","3.000002","assetPolicy",h.assetPolicy("NEX"));
            var device=values("rewardRuleId","reward-device","beneficiaryRole","BUYER","type","DEVICE","giftProductNo",rt.gift,"quantity",1,"deviceRightsProfile",h.devicePolicy(rt.gift));
            var contract=h.contract(rt.buy,usdt,common);contract.put("template","MULTI_PRODUCT");
            var rules=new ArrayList<Map<String,Object>>(maps(contract.get("rules")));
            var r2=copy(rules.get(0));r2.put("ruleId","r2");r2.put("productNo",rt.gift);r2.put("buyerReward",nex);r2.put("priority",2);rules.add(r2);
            var r3=copy(rules.get(0));r3.put("ruleId","r3");r3.put("productNo",third);r3.put("buyerReward",device);r3.put("priority",3);rules.add(r3);contract.put("rules",rules);
            contract.put("budgets",List.of(values("asset","USDT","total","10000.000000"),values("asset","NEX","total","10000.000000"),values("asset","DEVICE","productNo",rt.gift,"total",100)));
            String activity=h.publish(contract);long buyer1=rt.user(),buyer2=rt.user(),buyer3=rt.user();
            String order1=rt.create(buyer1,rt.quote(buyer1,activity,2),2);rt.pay(buyer1,order1);var firstIds=rt.obligations(order1);
            assertEquals(2,firstIds.size());assertEquals("ISSUED",rt.rewards.issue(firstIds.get(0),id("GROUPISSUE")).get("state"));
            String order2=order(rt,buyer2,activity,rt.gift);String order3=order(rt,buyer3,activity,third);
            assertEquals("ISSUED",rt.rewards.issue(rt.obligations(order2).get(0),id("GROUPISSUE")).get("state"));
            assertEquals("ISSUED",rt.rewards.issue(rt.obligations(order3).get(0),id("GROUPISSUE")).get("state"));
            checks.add("real-approved-multi-sku-contract-three-paid-source-orders-four-obligations-wallet-usdt-nex-and-device-receipts");

            var privateConfig=parse(Files.readString(Path.of("C:/Users/jason/.codex/workflow-runs/growth-promotions-20261007/services.private.json")));
            var properties=new StorageProperties();properties.setEndpoint("http://127.0.0.1:9139");properties.setBucket("nexion");
            var storage=new ObjectStorageService(MinioClient.builder().endpoint(properties.getEndpoint()).credentials(text(privateConfig.get("storageAccess")),text(privateConfig.get("storageSecret"))).region("us-east-1").build(),properties);
            var metrics=h.proxy(new PromotionMetricsService(h.db,h.validator,storage,h.audit));h.authenticate(h.maker);
            String from=Instant.now().minusSeconds(3600).toString(),to=Instant.now().plusSeconds(3600).toString();
            var first=metrics.metrics(activity,1L,from,to,"Asia/Tokyo","SKU",null,null,1);String snapshot=text(first.get("querySnapshot"));
            assertEquals(3,number(first.get("orders")));assertEquals(3,number(first.get("paidOrders")));assertEquals(1,number(first.get("giftDevices")));
            var sales=maps(first.get("salesBySku"));assertEquals(3,sales.size());
            assertEquals(decimal(first.get("grossPaidUsdt")),sales.stream().map(r->decimal(r.get("grossPaidUsdt"))).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add));
            assertTrue(sales.stream().allMatch(r->number(r.get("paidOrders"))==1&&"0.000000".equals(r.get("refundUsdt"))));
            assertEquals("2.125001",asset(first,"USDT").get("issued"));assertEquals("2.125001",asset(first,"USDT").get("pending"));assertEquals("3.000002",asset(first,"NEX").get("issued"));
            assertNull(first.get("giftCostUsdt"));assertNull(first.get("roi"));assertNull(first.get("conversionRate"));
            var firstPage=map(first.get("breakdown"));assertEquals(3,number(firstPage.get("total")));assertEquals(1,maps(firstPage.get("rows")).size());assertEquals("1",firstPage.get("nextCursor"));
            var stored=h.db.requiredRow("SELECT * FROM nx_promotion_report_snapshot WHERE snapshot_id=?",snapshot);var originalManifest=parse(stored.get("row_manifest_json"));
            assertEquals(4,maps(originalManifest.get("rewards")).size());
            assertEquals("ISSUED",rt.rewards.issue(firstIds.get(1),id("GROUPISSUELATER")).get("state"));
            var page2=metrics.metrics(activity,1L,from,to,"Asia/Tokyo","SKU",snapshot,"1",1);
            var page3=metrics.metrics(activity,1L,from,to,"Asia/Tokyo","SKU",snapshot,"2",1);
            assertEquals(first.get("asOf"),page2.get("asOf"));assertEquals(first.get("assets"),page2.get("assets"));assertEquals(first.get("assets"),page3.get("assets"));
            assertEquals(sales,maps(page2.get("salesBySku")));assertEquals(sales,maps(page3.get("salesBySku")));
            var combined=new ArrayList<Map<String,Object>>();combined.addAll(maps(firstPage.get("rows")));combined.addAll(maps(map(page2.get("breakdown")).get("rows")));combined.addAll(maps(map(page3.get("breakdown")).get("rows")));
            assertEquals(hash(map(originalManifest.get("groupings")).get("SKU")),hash(combined));assertEquals(3,combined.stream().map(r->r.get("groupKey")).distinct().count());assertEquals(false,map(page3.get("breakdown")).get("hasMore"));
            for(String group:List.of("TEMPLATE","BENEFICIARY")){
                var page=metrics.metrics(activity,1L,from,to,"Asia/Tokyo",group,snapshot,null,100);assertEquals(3,number(map(page.get("breakdown")).get("total")));assertEquals(first.get("asOf"),page.get("asOf"));
                assertEquals(hash(map(originalManifest.get("groupings")).get(group)),hash(map(page.get("breakdown")).get("rows")));
                assertEquals(sales,maps(page.get("salesBySku")));
                for(var row:maps(map(page.get("breakdown")).get("rows")))assertEquals(group.equals("TEMPLATE")?"MULTI_PRODUCT":"BUYER",row.get(group.equals("TEMPLATE")?"template":"beneficiaryRole"));
            }
            var fresh=metrics.metrics(activity,1L,from,to,"Asia/Tokyo","SKU",null,null,100);assertEquals("4.250002",asset(fresh,"USDT").get("issued"));assertEquals("0.000000",asset(fresh,"USDT").get("pending"));
            checks.add("all-three-groupings-and-assets-have-server-totals-stable-three-pages-despite-new-issuance-fresh-query-sees-new-facts");
            rt.refund(buyer2,order2);h.authenticate(h.maker);
            var afterRefund=metrics.metrics(activity,1L,from,to,"Asia/Tokyo","SKU",null,null,100);
            var refundedSku=maps(afterRefund.get("salesBySku")).stream().filter(r->rt.gift.equals(r.get("purchaseProductNo"))).findFirst().orElseThrow();
            assertEquals(refundedSku.get("grossPaidUsdt"),refundedSku.get("refundUsdt"));assertEquals("0.000000",refundedSku.get("netReceivedUsdt"));
            assertEquals(decimal(afterRefund.get("netReceivedUsdt")),maps(afterRefund.get("salesBySku")).stream().map(r->decimal(r.get("netReceivedUsdt"))).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add));
            assertEquals(sales,maps(metrics.metrics(activity,1L,from,to,"Asia/Tokyo","SKU",snapshot,null,100).get("salesBySku")));
            checks.add("per-sku-sales-join-real-purchased-lines-and-refund-receipts-while-old-snapshot-stays-frozen");

            h.authenticate(h.checker);assertEquals(404,assertThrows(BizException.class,()->metrics.metrics(activity,1L,from,to,"Asia/Tokyo","SKU",snapshot,"1",1)).getCode());
            h.authenticate(h.maker,"growth_promotion_read");assertEquals(403,assertThrows(BizException.class,()->metrics.metrics(activity,1L,from,to,"Asia/Tokyo","SKU",snapshot,"1",1)).getCode());
            h.authenticate(h.maker);
            assertEquals(409,assertThrows(BizException.class,()->metrics.metrics(activity,null,from,to,"Asia/Tokyo","SKU",snapshot,"1",1)).getCode());
            assertEquals(409,assertThrows(BizException.class,()->metrics.metrics(activity,1L,from,to,"UTC","SKU",snapshot,"1",1)).getCode());
            assertEquals(409,assertThrows(BizException.class,()->metrics.metrics(activity,1L,Instant.parse(from).minusSeconds(1).toString(),to,"Asia/Tokyo","SKU",snapshot,"1",1)).getCode());
            assertEquals(422,assertThrows(BizException.class,()->metrics.metrics(activity,1L,from,to,"Asia/Tokyo","SKU",null,"1",1)).getCode());
            assertEquals(422,assertThrows(BizException.class,()->metrics.metrics(activity,1L,from,to,"Asia/Tokyo","ROI",snapshot,null,1)).getCode());
            assertEquals(422,assertThrows(BizException.class,()->metrics.metrics(activity,1L,from,to,"Asia/Tokyo","SKU",snapshot,"4",1)).getCode());
            checks.add("snapshot-current-authority-owner-version-window-timezone-and-cursor-enforced");

            var request=values("querySnapshot",snapshot,"reason","同一固定报表跨页全部分项导出真实验收");
            var command=h.admin.command("createExport",activity,h.run+"-metrics-export",request,()->metrics.createExport(activity,request));String exportId=text(map(command.get("resource")).get("id"));
            metrics.generateExports();var export=metrics.export(exportId,true);assertEquals("READY",export.get("state"));
            var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(text(export.get("downloadUrl")))).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());
            var job=h.db.requiredRow("SELECT content_hash,row_count FROM nx_promotion_export_job WHERE export_id=?",exportId);assertEquals(job.get("content_hash"),sha256(response.body()));
            var payload=parse(response.body());assertEquals(hash(originalManifest),hash(payload.get("rows")));assertEquals(first.get("assets"),map(payload.get("metrics")).get("assets"));
            assertEquals(sales,maps(map(payload.get("metrics")).get("salesBySku")));
            assertEquals(3,maps(map(map(payload.get("metrics")).get("breakdown")).get("rows")).size());assertEquals(19,number(job.get("row_count")));
            checks.add("real-export-job-S3-protocol-upload-stat-signed-download-sha256-and-all-full-groupings-match-original-snapshot");
            h.db.write("UPDATE nx_promotion_report_snapshot SET expires_at=? WHERE snapshot_id=?",timestamp(Instant.now().minusSeconds(1)),snapshot);
            assertEquals(409,assertThrows(BizException.class,()->metrics.metrics(activity,1L,from,to,"Asia/Tokyo","SKU",snapshot,"1",1)).getCode());assertEquals("EXPIRED",metrics.export(exportId,false).get("state"));
            checks.add("expired-page-and-export-refuse-reuse");
            Files.writeString(Path.of("C:/Users/jason/.codex/workflow-runs/growth-promotions-20261007/governance-metrics-breakdown-runtime.json"),json(values("status","PASS","run",h.run,"activityId",activity,"snapshot",snapshot,"checks",checks,"groupCounts",values("SKU",3,"TEMPLATE",3,"BENEFICIARY",3),"exportRows",job.get("row_count"),"downloadHash",job.get("content_hash"),"scope","Isolated real governance/reward/MySQL and S3rver@3.7.1 protocol storage; canonical commerce source facts are explicit lifecycle fixtures; not HTTP commerce or production MinIO acceptance")));
        }finally{SecurityContextHolder.clearContext();}
    }
    private String order(PromotionLifecycleMySqlTest rt,long buyer,String activity,String product){
        var quote=rt.quotes.quote(buyer,values("items",List.of(values("productNo",product,"quantity",1)),"activityId",activity,"clientCapabilities",List.of("PROMOTION_QUOTE_V1","ORDER_ITEM_QUANTITIES_V1","PROMOTION_REWARDS_V1")));
        String order=rt.create(buyer,quote,1);rt.pay(buyer,order);return order;
    }
    private Map<String,Object> asset(Map<String,Object> metrics,String asset){return maps(metrics.get("assets")).stream().filter(r->asset.equals(r.get("asset"))).findFirst().orElseThrow();}
}
