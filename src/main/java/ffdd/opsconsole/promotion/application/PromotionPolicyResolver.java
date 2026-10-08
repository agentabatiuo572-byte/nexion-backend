package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

@Service
@RequiredArgsConstructor
public class PromotionPolicyResolver {
    private final PromotionMapper db;
    private final PromotionContractValidator validator;
    private final PromotionNativeContractResolver nativeContracts;
    /** Rechecks this order's already-locked products; the order boundary includes every actual gift in that set. */
    public void verifyLockedProducts(Map<String,Object> resolved,Map<String,Object> lockedProducts){
        for(var entry:resolved.entrySet()){
            if(entry.getKey().startsWith("$"))continue;var content=map(map(entry.getValue()).get("content"));
            if(!"DEVICE_RIGHTS".equals(content.get("kind")))continue;
            String product=text(content.get("productNo"));if(!lockedProducts.containsKey(product))continue;var locked=map(lockedProducts.get(product));
            nativeContracts.verifyLockedProduct(map(content.get("productContract")),map(locked.get("product")),map(locked.get("sku")));
        }
    }
    /** Step-two preview resolves only the policies needed to interpret its audience. */
    public Map<String,Object> resolveAudience(Map<String,Object> contract) {
        var result=new LinkedHashMap<String,Object>();
        var refs=contract.get("policies")==null?Map.<String,Object>of():map(contract.get("policies"));
        Object first=refs.get("firstPurchase");
        require(first!=null||!Set.of("FIRST_PURCHASE","DIRECT_REFERRAL").contains(text(contract.get("template"))),"PROMOTION_FIRST_PURCHASE_POLICY_REQUIRED");
        if(first!=null){var policy=approved(map(first),false);require("FIRST_PURCHASE".equals(map(policy.get("content")).get("kind")),"PROMOTION_POLICY_KIND_MISMATCH");result.put("$firstPurchase",policy);}
        for(String role:List.of("buyerAudience","inviterAudience"))if(contract.get(role)!=null){
            var audience=map(contract.get(role));if("ANY".equals(audience.get("devicePresence")))continue;
            require(audience.get("deviceAudiencePolicy")!=null,"PROMOTION_DEVICE_AUDIENCE_POLICY_REQUIRED");
            var ref=map(audience.get("deviceAudiencePolicy"));var policy=approved(ref,false);
            require("DEVICE_AUDIENCE".equals(map(policy.get("content")).get("kind")),"PROMOTION_POLICY_KIND_MISMATCH");
            result.put(text(ref.get("policyId"))+":"+number(ref.get("version")),policy);
        }
        return result;
    }
    private Map<String,Object> approved(Map<String,Object> ref,boolean lock){
        validator.validate("PolicyRef",ref);
        var row=db.requiredRow("SELECT * FROM nx_promotion_policy WHERE policy_id=? AND version=?"+(lock?" FOR UPDATE":""),ref.get("policyId"),number(ref.get("version")));
        require("APPROVED".equals(row.get("status"))&&ref.get("contentHash").equals(row.get("content_hash")),"PROMOTION_POLICY_NOT_APPROVED");
        var policy=view(row);require(hash(map(policy.get("content"))).equals(row.get("content_hash")),"PROMOTION_POLICY_CONTENT_CHANGED");
        nativeContracts.fixtureAllowed(row.get("fixture_run_id"));var resolved=nativeContracts.resolve(map(policy.get("content")));
        require(Objects.equals(resolved.get("resolvedDeviceRights"),policy.get("resolvedDeviceRights")),"PROMOTION_DEVICE_RIGHTS_CHANGED");
        validator.validate("ResolvedApprovedPolicy",policy);return policy;
    }
    public Map<String,Object> resolve(Map<String,Object> contract,boolean lock) {
        Map<String,Map<String,Object>> requested=new TreeMap<>();
        collect(contract,requested);
        Map<String,Object> result=new LinkedHashMap<>();
        for(var e:requested.entrySet()) {
            Map<String,Object> ref=e.getValue();
            result.put(e.getKey(),approved(ref,lock));
        }
        Map<String,Object> refs=map(contract.get("policies"));
        for(String name:List.of("settlement","stacking","refund","quote","authorization")) {
            Map<String,Object> policy=resolveReference(result,map(refs.get(name)));
            require(name.toUpperCase(Locale.ROOT).equals(map(policy.get("content")).get("kind")),"PROMOTION_POLICY_KIND_MISMATCH");
            result.put("$"+name,policy);
        }
        if(refs.get("firstPurchase")!=null){
            Map<String,Object> p=resolveReference(result,map(refs.get("firstPurchase")));
            require("FIRST_PURCHASE".equals(map(p.get("content")).get("kind")),"PROMOTION_POLICY_KIND_MISMATCH");result.put("$firstPurchase",p);
        }
        for(String role:List.of("buyerAudience","inviterAudience"))if(contract.get(role)!=null){
            var audience=map(contract.get(role));
            if(audience.get("deviceAudiencePolicy")!=null){
                var policy=resolveReference(result,map(audience.get("deviceAudiencePolicy")));
                require("DEVICE_AUDIENCE".equals(map(policy.get("content")).get("kind")),"PROMOTION_POLICY_KIND_MISMATCH");
            }
        }
        for(Map<String,Object> rule:maps(contract.get("rules")))for(String role:List.of("buyerReward","inviterReward")){
            if(rule.get(role)==null)continue;Map<String,Object> reward=map(rule.get(role));String type=text(reward.get("type"));
            Map<String,Object> policy=resolveReference(result,map(reward.get("DEVICE".equals(type)?"deviceRightsProfile":"assetPolicy")));
            Map<String,Object> content=map(policy.get("content"));
            if("DEVICE".equals(type))require("DEVICE_RIGHTS".equals(content.get("kind"))&&reward.get("giftProductNo").equals(content.get("productNo")),"PROMOTION_DEVICE_POLICY_MISMATCH");
            else require("ASSET".equals(content.get("kind"))&&type.equals(content.get("asset")),"PROMOTION_ASSET_POLICY_MISMATCH");
        }
        return result;
    }
    private void collect(Object value,Map<String,Map<String,Object>> refs) {
        if(value instanceof Map<?,?> raw) {
            Map<String,Object> m=map(raw);
            if(m.containsKey("policyId")&&m.containsKey("contentHash")&&m.containsKey("version")) {
                String key=text(m.get("policyId"))+":"+number(m.get("version"));
                Map<String,Object> previous=refs.putIfAbsent(key,m);
                require(previous==null||previous.equals(m),"PROMOTION_POLICY_REFERENCE_CONFLICT");
            }else m.values().forEach(v->collect(v,refs));
        }else if(value instanceof List<?> list)list.forEach(v->collect(v,refs));
    }
    public Map<String,Object> view(Map<String,Object> row) {
        Map<String,Object> evidence=parse(row.get("evidence_json"));
        return values("policyId",row.get("policy_id"),"version",number(row.get("version")),"revision",number(row.get("revision")),
            "contentHash",row.get("content_hash"),"state",row.get("status"),"content",parse(row.get("content_json")),
            "approvalRef",row.get("approval_ref"),"approvedBy",row.get("approved_by")==null?null:text(row.get("approved_by")),
            "approvedAt",row.get("approved_at")==null?null:instant(row.get("approved_at")).toString(),
            "fixture",!text(row.get("fixture_run_id")).isEmpty(),"fixtureRunId",text(row.get("fixture_run_id")).isEmpty()?null:row.get("fixture_run_id"),
            "resolvedDeviceRights",evidence.get("resolvedDeviceRights"));
    }
    public static Map<String,Object> resolveReference(Map<String,Object> resolved,Map<String,Object> ref) {
        Object policy=resolved.get(text(ref.get("policyId"))+":"+number(ref.get("version")));
        if(policy==null)throw new BizException(503,"PROMOTION_POLICY_SNAPSHOT_MISSING");
        return map(policy);
    }
    public static Map<String,Object> content(Map<String,Object> resolved,String name) {
        Object policy=resolved.get("$"+name);return policy==null?null:map(map(policy).get("content"));
    }
}
