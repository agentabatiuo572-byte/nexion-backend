package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.promotion.domain.PromotionRules;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

@Service
@RequiredArgsConstructor
public class PromotionAvailabilityService {
    private final PromotionMapper db;
    private final AuditLogService audit;
    public List<String> candidates(){
        return candidatesAfter("",100);
    }
    public List<String> candidatesAfter(String activityId,int limit){
        return db.list("""
            SELECT p.activity_id FROM nx_promotion p JOIN nx_promotion_version v
              ON v.activity_id=p.activity_id AND v.version=p.active_version
            WHERE p.status IN ('ACTIVE','SCHEDULED') AND v.status='PUBLISHED' AND v.starts_at<=NOW(6) AND v.ends_at>NOW(6)
              AND JSON_UNQUOTE(JSON_EXTRACT(v.contract_json,'$.shortagePolicy'))='ACTIVITY_PAUSE'
              AND p.activity_id>?
            ORDER BY p.activity_id LIMIT ?
            """,activityId,Math.min(100,Math.max(1,limit))).stream().map(r->text(r.get("activity_id"))).toList();
    }
    @Transactional(rollbackFor=Exception.class)
    public void pauseIfShort(String activity){
        Map<String,Object> root=db.activity(activity,true);
        if(!Set.of("ACTIVE","SCHEDULED").contains(text(root.get("status"))))return;
        Map<String,Object> version=db.version(activity,number(root.get("active_version")),false),contract=parse(version.get("contract_json"));
        if(!"ACTIVITY_PAUSE".equals(contract.get("shortagePolicy")))return;
        Map<String,Map<String,Object>> budgets=new HashMap<>();
        for(Map<String,Object> row:db.list("SELECT * FROM nx_promotion_budget WHERE activity_id=? ORDER BY asset,product_no FOR UPDATE",activity))
            budgets.put(text(row.get("asset"))+":"+text(row.get("product_no")),row);
        TreeSet<String> products=new TreeSet<>();
        for(Map<String,Object> rule:maps(contract.get("rules")))for(String role:List.of("buyerReward","inviterReward"))
            if(rule.get(role)!=null&&"DEVICE".equals(map(rule.get(role)).get("type")))products.add(text(map(rule.get(role)).get("giftProductNo")));
        Map<String,Map<String,Object>> inventory=new HashMap<>();for(String product:products)inventory.put(product,db.product(product,true));
        boolean shortfall=number(root.get("reserved_orders"))+number(root.get("used_orders"))>=PromotionRules.limit(contract.get("activityLimit"));
        for(Map<String,Object> rule:maps(contract.get("rules")))for(String role:List.of("buyerReward","inviterReward")){
            if(rule.get(role)==null)continue;Map<String,Object> reward=map(rule.get(role));String asset=text(reward.get("type")),product="DEVICE".equals(asset)?text(reward.get("giftProductNo")):"";
            BigDecimal amount=PromotionQuoteService.rewardAmount(reward);Map<String,Object> budget=budgets.get(asset+":"+product);
            if(budget==null||decimal(budget.get("total")).subtract(decimal(budget.get("reserved"))).subtract(decimal(budget.get("committed"))).subtract(decimal(budget.get("issued"))).add(decimal(budget.get("reversed"))).compareTo(amount)<0)shortfall=true;
            if(!product.isEmpty()){Map<String,Object> p=inventory.get(product);if(!"UNLIMITED".equals(p.get("inventory_mode"))&&decimal(p.get("stock")).compareTo(amount)<0)shortfall=true;}
        }
        if(!shortfall)return;
        changed(db.write("UPDATE nx_promotion SET status='PAUSED',revision=revision+1,updated_at=NOW(6) WHERE activity_id=? AND status IN ('ACTIVE','SCHEDULED')",activity));
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder().action("PROMOTION_CAPACITY_PAUSED").resourceType("PROMOTION").resourceId(activity)
            .actorUsername("PROMOTION_ENGINE").actorType("SYSTEM").result("SUCCESS").riskLevel("HIGH")
            .detail(values("policy","ACTIVITY_PAUSE","reason","APPROVED_CAPACITY_EXHAUSTED","previousStatus",root.get("status"))).build());
    }
}
