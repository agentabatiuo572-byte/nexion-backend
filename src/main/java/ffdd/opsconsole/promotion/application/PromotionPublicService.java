package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.promotion.domain.PromotionRules;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

@Service
@RequiredArgsConstructor
public class PromotionPublicService {
    private final PromotionMapper db;
    private final PromotionEvaluationService evaluator;
    private final PromotionRewardService rewards;
    public Map<String,Object> page(Long user,String cursor,int limit,String activity,String placement){
        require(text(placement).isEmpty()||"home.purchase-promotion".equals(placement),"PROMOTION_PLACEMENT_INVALID");
        int size=Math.min(100,Math.max(1,limit));
        List<Map<String,Object>> rows=db.list("""
            SELECT p.*,v.contract_json,v.resolved_policies_json FROM nx_promotion p
            JOIN nx_promotion_version v ON v.activity_id=p.activity_id AND v.version=p.active_version
            WHERE p.status IN ('ACTIVE','SCHEDULED') AND v.status='PUBLISHED' AND v.starts_at<=NOW(6) AND v.ends_at>NOW(6)
              AND (?='' OR p.activity_id>?) AND (?='' OR p.activity_id=?)
              AND (?='' OR JSON_UNQUOTE(JSON_EXTRACT(v.contract_json,'$.placement'))=?)
            ORDER BY p.activity_id LIMIT ?
            """,text(cursor),text(cursor),text(activity),text(activity),text(placement),text(placement),size+1);
        boolean more=rows.size()>size;if(more)rows=rows.subList(0,size);
        return values("items",rows.stream().map(r->view(r,user)).toList(),"nextCursor",more?rows.get(rows.size()-1).get("activity_id"):null,"hasMore",more);
    }
    public Map<String,Object> get(Long user,String activity){
        Map<String,Object> row=db.requiredRow("""
            SELECT p.*,v.contract_json,v.resolved_policies_json FROM nx_promotion p
            JOIN nx_promotion_version v ON v.activity_id=p.activity_id AND v.version=p.active_version
            WHERE p.activity_id=? AND v.status='PUBLISHED' AND p.status<>'ARCHIVED'
            """,activity);
        return view(row,user);
    }
    private Map<String,Object> view(Map<String,Object> row,Long user){
        Map<String,Object> contract=parse(row.get("contract_json"));Instant now=Instant.now();
        String state=text(row.get("status"));
        if("PAUSED".equals(state)&&!now.isBefore(instant(contract.get("endsAt"))))state="ENDED";
        if(Set.of("ACTIVE","SCHEDULED").contains(state))state=now.isBefore(instant(contract.get("startsAt")))?"SCHEDULED":now.isBefore(instant(contract.get("endsAt")))?"ACTIVE":"ENDED";
        String eligibility=user==null?"SIGN_IN_REQUIRED":"UNKNOWN";
        if(user!=null){
            Map<String,Object> policies=parse(row.get("resolved_policies_json"));
            var account=evaluator.account(user,map(contract.get("buyerAudience")),policies,null);
            Map<String,Object> first=PromotionPolicyResolver.content(policies,"firstPurchase");
            boolean restores=first!=null&&Boolean.TRUE.equals(first.get("restoreAfterRefund"));
            boolean matches=PromotionRules.eligible(map(contract.get("buyerAudience")),account,now,restores);
            if(Set.of("FIRST_PURCHASE","DIRECT_REFERRAL").contains(text(contract.get("template"))))
                matches=matches&&first!=null&&(restores?account.firstPurchasePaid():account.everPaid())==0;
            if("REPURCHASE".equals(contract.get("template")))matches=matches&&account.validPaid()>0;
            if("DIRECT_REFERRAL".equals(contract.get("template")))matches=matches&&account.sponsorId()!=null&&account.sponsorId()!=user;
            eligibility="ACTIVE".equals(state)&&matches?"ESTIMATE_ELIGIBLE":"INELIGIBLE";
        }
        List<Map<String,Object>> publicRules=new ArrayList<>();
        for(Map<String,Object> rule:maps(contract.get("rules"))){
            Map<String,Object> visible=new LinkedHashMap<>();
            for(String key:List.of("ruleId","productNo","minBuyQty","repeatMode","maxGroups","maxGroupsPerPerson"))visible.put(key,rule.get(key));
            for(String role:List.of("buyerReward","inviterReward")){
                if(rule.get(role)==null){visible.put(role,null);continue;}
                Map<String,Object> spec=map(rule.get(role)),reward=copy(spec);
                reward.put("disclosure",disclosure(db,contract,parse(row.get("resolved_policies_json")),spec));
                reward.remove("deviceRightsProfile");reward.remove("assetPolicy");visible.put(role,reward);
            }
            publicRules.add(visible);
        }
        return values("activityId",row.get("activity_id"),"version",number(row.get("active_version")),"template",contract.get("template"),"state",state,
            "startsAt",contract.get("startsAt"),"endsAt",contract.get("endsAt"),"serverTime",now.toString(),"title",contract.get("title"),"terms",contract.get("terms"),
            "placement",contract.get("placement"),"target","STORE","productNos",publicRules.stream().map(r->r.get("productNo")).distinct().toList(),
            "minimumClientCapabilities",contract.get("minimumClientCapabilities"),"eligibility",eligibility,"eligibilityMessage",null,"rewardRules",publicRules);
    }
    /** Only public benefits are projected; the supplied policy map is the original approved snapshot. */
    public static Map<String,Object> disclosure(PromotionMapper db,Map<String,Object> contract,Map<String,Object> policies,Map<String,Object> spec){
        boolean device="DEVICE".equals(spec.get("type"));
        var policy=PromotionPolicyResolver.resolveReference(policies,map(spec.get(device?"deviceRightsProfile":"assetPolicy")));
        var content=map(policy.get("content"));var refund=PromotionPolicyResolver.content(policies,"refund");
        require(refund!=null,"PROMOTION_POLICY_SNAPSHOT_MISSING");
        Map<String,Object> rights=null;String deviceName=null;
        if(device){
            var source=map(policy.get("resolvedDeviceRights"));rights=new LinkedHashMap<>();
            for(String field:List.of("activationMode","effectiveOn","durationDays","taskEnabled","countsAsDeviceHolding","countsForRank","transferable","exchangeable","revocationMode"))rights.put(field,source.get(field));
            var evidence=db.requiredRow("SELECT evidence_json FROM nx_promotion_policy WHERE policy_id=? AND version=? AND content_hash=?",policy.get("policyId"),policy.get("version"),policy.get("contentHash"));
            var reference=map(content.get("productContract"));
            var nativeProduct=maps(parse(evidence.get("evidence_json")).get("nativeContracts")).stream().filter(p->"E1_PRODUCT".equals(p.get("system"))&&spec.get("giftProductNo").equals(p.get("resourceId"))&&number(reference.get("revision"))==number(p.get("revision"))&&reference.get("contentHash").equals(p.get("contentHash"))).findFirst();
            require(nativeProduct.isPresent(),"PROMOTION_POLICY_SNAPSHOT_MISSING");var frozen=map(nativeProduct.orElseThrow().get("content"));
            // This is the approved evidence, not a fresh JDBC projection whose numeric JSON encoding may differ.
            require(spec.get("giftProductNo").equals(map(frozen.get("product")).get("product_no")),"PROMOTION_POLICY_SNAPSHOT_MISSING");
            deviceName=required(map(frozen.get("product")).get("name"),"deviceName");
        }
        return values("title",contract.get("title"),"terms",contract.get("terms"),"refundTerms",refund.get("terms"),
            "benefitDescription",content.get(device?"rightsDescription":"availabilityDescription"),"deviceName",deviceName,"deviceRights",rights);
    }
    public Map<String,Object> referral(long owner,String activity){
        Map<String,Object> root=db.activity(activity,false);require("DIRECT_REFERRAL".equals(root.get("template")),"PROMOTION_NOT_REFERRAL");
        long orders=db.count("""
            SELECT COUNT(DISTINCT r.order_no) FROM nx_promotion_reward r JOIN nx_order o ON o.order_no=r.order_no
            WHERE r.activity_id=? AND r.beneficiary_id=? AND r.beneficiary_role='DIRECT_INVITER'
              AND o.payment_status='PAID' AND o.order_status<>'REFUNDED' AND o.is_deleted=0
            """,activity,owner);
        return values("activityId",activity,"qualifyingOrders",orders,"ownRewards",rewards.page(owner,false,null,100,activity,null,null).get("items"),"serverTime",Instant.now().toString());
    }
}
