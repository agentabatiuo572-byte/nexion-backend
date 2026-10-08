package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.risk.application.RiskReleaseParamsService;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

/** Resolves references against the same authorities used by real order, E1, K1 and A2 executors. */
@Service
@RequiredArgsConstructor
public class PromotionNativeContractResolver {
    private final PromotionMapper db;
    private final PlatformConfigFacade config;
    private final RiskReleaseParamsService earnings;
    private final A2RuntimePolicy auditPolicy;
    private final Environment environment;
    @Value("${nexion.commerce.pending-order-ttl-minutes:30}") private int orderTtlMinutes;

    public void fixtureAllowed(Object run) {
        if(!text(run).isEmpty() && Arrays.stream(environment.getActiveProfiles()).noneMatch(p->Set.of("test","local","acceptance").contains(p)))
            throw new BizException(422,"PROMOTION_FIXTURE_NOT_ALLOWED");
    }
    public Map<String,Object> nativeContract(String system,String resource) {
        Map<String,Object> data;long revision;
        switch(system) {
            case "ORDER_CONTRACT" -> {
                valid("WALLET_ORDER_V1".equals(resource),"PROMOTION_NATIVE_ORDER_UNKNOWN");
                valid(orderTtlMinutes>0,"PROMOTION_NATIVE_ORDER_TTL_INVALID");
                revision=1;data=values("executor","WALLET_ORDER_V1","pendingOrderTtlMinutes",orderTtlMinutes,
                    "payment","WALLET","refund","WHOLE_ORDER_WALLET","maxQuantity",100,"maxSkuLines",8);
            }
            case "EARNINGS_RELEASE" -> {
                valid("risk.k1.release.version".equals(resource),"PROMOTION_NATIVE_EARNINGS_UNKNOWN");
                revision=number(config.activeValue(resource).orElseThrow(()->new BizException(503,"PROMOTION_NATIVE_EARNINGS_UNAVAILABLE")));
                data=values("rows",earnings.rows(),"manualOnly",earnings.manualOnly(),"trustedAttestationEnabled",earnings.trustedAttestationEnabled());
            }
            case "A2_POLICY" -> {
                valid("admin.a2.schema_version".equals(resource),"PROMOTION_NATIVE_A2_UNKNOWN");
                revision=A2RuntimePolicy.schemaVersionOrder(auditPolicy.schemaVersion());
                data=values("schema",auditPolicy.schemaVersion(),"reasonMinChars",auditPolicy.reasonMinChars(),
                    "retentionMonths",auditPolicy.retentionMonths(),"separateMakerChecker",true);
            }
            case "E1_PRODUCT" -> {
                var product=db.product(resource,false);
                var sku=db.requiredRow("SELECT * FROM nx_admin_device_sku WHERE sku_id=? AND is_deleted=0",resource);
                return productContract(resource,product,sku);
            }
            default -> throw new BizException(422,"PROMOTION_NATIVE_SYSTEM_UNKNOWN");
        }
        return values("system",system,"resourceId",resource,"revision",revision,"contentHash",hash(data),"content",data);
    }
    private Map<String,Object> productContract(String resource,Map<String,Object> product,Map<String,Object> sku){
        // Operational counters do not change the approved rights contract; all other source fields do.
        var p=new TreeMap<>(product);p.keySet().removeAll(Set.of("stock","sold_count","updated_at","created_at"));
        var s=new TreeMap<>(sku);s.keySet().removeAll(Set.of("stock_text","sold","updated_at","created_at"));
        var data=values("product",p,"sku",s);
        return values("system","E1_PRODUCT","resourceId",resource,"revision",Math.max(1,number(sku.get("purchase_gate_generation"))),"contentHash",hash(data),"content",data);
    }
    /** Caller already holds the ordered product/SKU locks; this method performs no stale database reads. */
    public void verifyLockedProduct(Map<String,Object> reference,Map<String,Object> product,Map<String,Object> sku){
        valid("E1_PRODUCT".equals(reference.get("system")),"PROMOTION_NATIVE_SYSTEM_MISMATCH");String resource=text(reference.get("resourceId"));
        valid(resource.equals(text(product.get("product_no")))&&resource.equals(text(sku.get("sku_id"))),"PROMOTION_DEVICE_PRODUCT_MISMATCH");
        var actual=productContract(resource,product,sku);
        valid(number(reference.get("revision"))==number(actual.get("revision"))&&Objects.equals(reference.get("contentHash"),actual.get("contentHash")),"PROMOTION_NATIVE_CONTRACT_CHANGED");
        valid(giftFailure(product,sku)==null,"PROMOTION_DEVICE_RIGHTS_EXECUTOR_UNSUPPORTED");
    }
    public Map<String,Object> verify(Map<String,Object> reference,String expectedSystem) {
        valid(expectedSystem.equals(reference.get("system")),"PROMOTION_NATIVE_SYSTEM_MISMATCH");
        var actual=nativeContract(expectedSystem,text(reference.get("resourceId")));
        valid(number(reference.get("revision"))==number(actual.get("revision"))&&Objects.equals(reference.get("contentHash"),actual.get("contentHash")),"PROMOTION_NATIVE_CONTRACT_CHANGED");
        return actual;
    }
    public Map<String,Object> catalogOption(String system,String resource,Map<String,Object> title,String unavailable){
        Map<String,Object> reference=null;
        try{var resolved=nativeContract(system,resource);reference=values("system",system,"resourceId",resource,"revision",resolved.get("revision"),"contentHash",resolved.get("contentHash"));}
        catch(BizException failure){if(!Set.of(404,422,503).contains(failure.getCode()))throw failure;unavailable="CONTRACT_UNAVAILABLE";}
        var reason=unavailable==null?null:values("zh","当前原合同无法用于此类政策，请核查配置或选择其他合同。","en","This contract is unavailable for this policy. Check its configuration or choose another contract.","vi","Hợp đồng hiện chưa dùng được cho chính sách này. Hãy kiểm tra cấu hình hoặc chọn hợp đồng khác.");
        return values("system",system,"resourceId",resource,"title",title,"reference",reference,"available",unavailable==null,"unavailabilityReason",reason);
    }
    public Map<String,Object> resolve(Map<String,Object> content) {
        List<Map<String,Object>> nativeContracts=new ArrayList<>();Map<String,Object> rights=null;
        switch(text(content.get("kind"))) {
            case "ASSET" -> nativeContracts.add(verify(map(content.get("nativeContract")),"EARNINGS_RELEASE"));
            case "SETTLEMENT" -> nativeContracts.add(verify(map(content.get("nativeContract")),"ORDER_CONTRACT"));
            case "REFUND" -> nativeContracts.add(verify(map(content.get("deviceRecoveryPolicy")),"ORDER_CONTRACT"));
            case "AUTHORIZATION" -> {
                var nativePolicy=verify(map(content.get("nativeContract")),"A2_POLICY");nativeContracts.add(nativePolicy);
                valid(Objects.equals(content.get("separateMakerChecker"),map(nativePolicy.get("content")).get("separateMakerChecker")),"PROMOTION_AUTHORIZATION_POLICY_MISMATCH");
            }
            case "QUOTE" -> valid(number(content.get("ttlSeconds"))<=orderTtlMinutes*60L,"PROMOTION_QUOTE_EXCEEDS_ORDER_TTL");
            case "DEVICE_RIGHTS" -> {
                var ref=map(content.get("productContract"));var nativeProduct=verify(ref,"E1_PRODUCT");nativeContracts.add(nativeProduct);
                valid(content.get("productNo").equals(ref.get("resourceId")),"PROMOTION_DEVICE_PRODUCT_MISMATCH");
                var p=map(map(nativeProduct.get("content")).get("product"));var sku=map(map(nativeProduct.get("content")).get("sku"));
                valid(giftFailure(p,sku)==null,"PROMOTION_DEVICE_RIGHTS_EXECUTOR_UNSUPPORTED");
                valid("EXISTING_PRODUCT_RULES".equals(content.get("taskEligibility")),"PROMOTION_DEVICE_TASK_OVERRIDE_UNSUPPORTED");
                rights=values("productNo",content.get("productNo"),"productContract",ref,"activationMode","AUTO","effectiveOn","ISSUED",
                    "durationDays",null,"taskEnabled",true,"taskRule",ref,"earningsRule",ref,"countsAsDeviceHolding",true,
                    "countsForRank",false,"transferable",false,"exchangeable",false,"revocationMode","MANUAL_IF_USED");
            }
            case "STACKING" -> valid("ALLOW".equals(content.get("existingH8"))&&"ALLOW".equals(content.get("existingReferral")),"PROMOTION_STACKING_EXECUTOR_UNSUPPORTED");
            case "FIRST_PURCHASE","DEVICE_AUDIENCE" -> { }
            default -> throw new BizException(422,"PROMOTION_EXECUTOR_NOT_AVAILABLE");
        }
        return values("nativeContracts",nativeContracts,"resolvedDeviceRights",rights);
    }
    public static String giftFailure(Map<String,Object> product,Map<String,Object> sku){
        String type=text(product.get("product_type")).toUpperCase(Locale.ROOT);
        String identity=String.join(" ",type,text(product.get("device_type")),text(product.get("product_no")),text(sku.get("name"))).toUpperCase(Locale.ROOT);
        if(identity.contains("CLOUD-SHARE")||identity.contains("CLOUD_SHARE")||identity.matches(".*\\bSHARE\\b.*")||!Set.of("DEVICE","SERVER").contains(type))return "UNSUPPORTED_DEVICE_TYPE";
        if(!"ACTIVE".equals(product.get("status")))return "PRODUCT_UNAVAILABLE";
        String power=text(sku.get("power_text")).replaceAll("[Ww]","").trim();
        if(!power.matches("[0-9]+(\\.[0-9]+)?")||new java.math.BigDecimal(power).signum()<=0||text(sku.get("datacenter")).isBlank()||product.get("vram_total_gb")==null||number(product.get("vram_total_gb"))<=0)return "PRODUCT_RIGHTS_INCOMPLETE";
        return null;
    }
    private static void valid(boolean value,String message){if(!value)throw new BizException(422,message);}
}
