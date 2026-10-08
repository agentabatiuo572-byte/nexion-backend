package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.promotion.domain.PromotionRules;
import ffdd.opsconsole.promotion.domain.PromotionRules.Account;
import ffdd.opsconsole.promotion.domain.PromotionRules.Award;
import ffdd.opsconsole.promotion.domain.PromotionRules.Selection;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

@Service
@RequiredArgsConstructor
public class PromotionEvaluationService {
    private final PromotionMapper db;
    public Account account(long id,Map<String,Object> audience,Map<String,Object> policies,String excludedOrder) {
        return account(id,audience,policies,excludedOrder,false);
    }
    private Account account(long id,Map<String,Object> audience,Map<String,Object> policies,String excludedOrder,boolean currentRead) {
        Map<String,Object> user=db.user(id,currentRead);
        // A fully redeemed voucher is still a settled purchase; cash amount is not a purchase predicate.
        List<Map<String,Object>> history=db.list("""
            SELECT order_no,payment_status,order_status,paid_at
            FROM nx_order WHERE user_id=? AND is_deleted=0
              AND (paid_at IS NOT NULL OR payment_status IN ('PAID','REFUNDED')) AND (? IS NULL OR order_no<>?)
            """+(currentRead?" FOR UPDATE":""),id,excludedOrder,excludedOrder);
        List<Map<String,Object>> valid=history.stream().filter(r->"PAID".equals(r.get("payment_status"))&&!Set.of("REFUNDED","CANCELLED").contains(text(r.get("order_status")))).toList();
        Instant lastPaid=valid.stream().filter(r->r.get("paid_at")!=null).map(r->instant(r.get("paid_at"))).max(Comparator.naturalOrder()).orElse(null);
        long devices=0;
        if(!"ANY".equals(audience.get("devicePresence"))) {
            require(audience.get("deviceAudiencePolicy")!=null,"PROMOTION_DEVICE_AUDIENCE_POLICY_REQUIRED");
            Map<String,Object> p=map(PromotionPolicyResolver.resolveReference(policies,map(audience.get("deviceAudiencePolicy"))).get("content"));
            require("DEVICE_AUDIENCE".equals(p.get("kind")),"PROMOTION_DEVICE_AUDIENCE_POLICY_INVALID");
            for(Map<String,Object> device:db.list("""
                SELECT d.*,r.rights_snapshot_json FROM nx_user_device d
                LEFT JOIN nx_promotion_device_receipt r ON r.device_id=d.id
                WHERE d.user_id=? AND d.is_deleted=0 AND d.ownership_status='OWNED'
                """+(currentRead?" FOR UPDATE":""),id)) {
                if(!((List<?>)p.get("sources")).contains(text(device.get("source_channel")))||
                   !((List<?>)p.get("states")).contains(text(device.get("status")))||
                   Set.of("PHONE","TRIAL").contains(text(device.get("device_type"))))continue;
                if("PROMOTION_GIFT".equals(device.get("source_channel"))){
                    if(device.get("rights_snapshot_json")==null)continue;
                    Map<String,Object> rights=parse(device.get("rights_snapshot_json"));
                    if(!Boolean.TRUE.equals(rights.get("countsAsDeviceHolding")))continue;
                    Object days=rights.get("durationDays");
                    if(days!=null){Object start=device.get("ACTIVATED".equals(rights.get("effectiveOn"))?"activated_at":"purchased_at");
                        if(start==null||!instant(start).plus(Duration.ofDays(number(days))).isAfter(Instant.now()))continue;}
                }
                devices++;
            }
        }
        return new Account(id,instant(user.get("created_at")),text(user.get("v_rank")),text(user.get("region")),
            user.get("sponsor_user_id")==null?null:number(user.get("sponsor_user_id")),"ACTIVE".equals(user.get("status")),
            history.size(),valid.size(),lastPaid,devices,history.stream().filter(r->consumesFirstPurchase(r,currentRead)).count());
    }
    public List<Award> evaluate(String activity,Map<String,Object> contract,Map<String,Object> policies,
                                long buyerId,List<Selection> items,Instant now,String excludedOrder) {
        return evaluate(activity,contract,policies,buyerId,items,now,excludedOrder,false);
    }
    public List<Award> evaluateCurrent(String activity,Map<String,Object> contract,Map<String,Object> policies,
                                      long buyerId,List<Selection> items,Instant now,String excludedOrder) {
        return evaluate(activity,contract,policies,buyerId,items,now,excludedOrder,true);
    }
    public Map<String,Object> qualificationSnapshot(Map<String,Object> contract,Map<String,Object> policies,long buyerId,Instant now){
        Account buyer=account(buyerId,map(contract.get("buyerAudience")),policies,null,true),inviter=null;
        if("DIRECT_REFERRAL".equals(contract.get("template"))&&buyer.sponsorId()!=null&&buyer.sponsorId()!=buyerId)
            inviter=account(buyer.sponsorId(),map(contract.get("inviterAudience")),policies,null,true);
        return values("capturedAt",now.toString(),"buyer",accountFacts(buyer),"directInviter",inviter==null?null:accountFacts(inviter));
    }
    private Map<String,Object> accountFacts(Account a){
        return values("accountId",a.id(),"registeredAt",a.registeredAt().toString(),"rank",a.rank(),"market",a.market(),
            "sponsorId",a.sponsorId(),"active",a.active(),"everPaid",a.everPaid(),"validPaid",a.validPaid(),
            "lastValidPaid",a.lastValidPaid()==null?null:a.lastValidPaid().toString(),"devices",a.devices(),"firstPurchasePaid",a.firstPurchasePaid());
    }
    /** Payment preserves the reserved audience and recipients; only first-purchase facts are arbitrated again. */
    public boolean firstPurchaseStillValid(Map<String,Object> contract,Map<String,Object> policies,long buyer,
                                           List<Map<String,Object>> awards,String excludedOrder){
        Map<String,Object> first=PromotionPolicyResolver.content(policies,"firstPurchase");
        boolean restore=first!=null&&Boolean.TRUE.equals(first.get("restoreAfterRefund"));
        Map<String,Object> buyerAudience=map(contract.get("buyerAudience"));
        if((Set.of("FIRST_PURCHASE","DIRECT_REFERRAL").contains(text(contract.get("template")))||requiresFirst(buyerAudience))
            &&hasPriorPayment(buyer,excludedOrder,restore))return false;
        if(contract.get("inviterAudience")!=null&&requiresFirst(map(contract.get("inviterAudience"))))
            for(Map<String,Object> award:awards)if("DIRECT_INVITER".equals(award.get("beneficiaryRole"))
                &&hasPriorPayment(number(award.get("beneficiaryId")),null,restore))return false;
        return true;
    }
    private boolean requiresFirst(Map<String,Object> audience){return "NEVER_PAID".equals(audience.get("purchaseHistory"))||Boolean.TRUE.equals(audience.get("neverPurchased"));}
    private boolean hasPriorPayment(long account,String excludedOrder,boolean restore){
        return db.list("""
            SELECT order_no,payment_status,order_status FROM nx_order WHERE user_id=? AND is_deleted=0
              AND (paid_at IS NOT NULL OR payment_status IN ('PAID','REFUNDED')) AND (? IS NULL OR order_no<>?) FOR UPDATE
            """,account,excludedOrder,excludedOrder).stream().anyMatch(r->!restore||consumesFirstPurchase(r,true));
    }
    private boolean consumesFirstPurchase(Map<String,Object> order,boolean currentRead){
        if("PAID".equals(order.get("payment_status"))&&!Set.of("REFUNDED","CANCELLED").contains(text(order.get("order_status"))))return true;
        if(!"REFUNDED".equals(order.get("payment_status"))&&!"REFUNDED".equals(order.get("order_status")))return false;
        if(db.reservations(text(order.get("order_no")),currentRead).isEmpty())return false;
        var receipt=db.one("SELECT quota_restored_at FROM nx_promotion_order_receipt WHERE order_no=?"+(currentRead?" FOR UPDATE":""),order.get("order_no"));
        return receipt==null||receipt.get("quota_restored_at")==null;
    }
    private List<Award> evaluate(String activity,Map<String,Object> contract,Map<String,Object> policies,
                                long buyerId,List<Selection> items,Instant now,String excludedOrder,boolean currentRead) {
        Account buyer=account(buyerId,map(contract.get("buyerAudience")),policies,excludedOrder,currentRead),inviter=null;
        if("DIRECT_REFERRAL".equals(contract.get("template"))&&buyer.sponsorId()!=null&&buyer.sponsorId()!=buyerId)
            inviter=account(buyer.sponsorId(),map(contract.get("inviterAudience")),policies,null,currentRead);
        Map<String,Long> groups=new HashMap<>(),orders=new HashMap<>();
        for(Map<String,Object> usage:db.list("SELECT * FROM nx_promotion_usage WHERE activity_id=?"+(currentRead?" FOR UPDATE":""),activity)){
            String key=number(usage.get("account_id"))+":"+text(usage.get("beneficiary_role"));
            String rule=text(usage.get("rule_id"));
            if(rule.isEmpty())orders.put(key,number(usage.get("reserved_orders"))+number(usage.get("used_orders")));
            else groups.put(key+":"+rule,number(usage.get("reserved_groups"))+number(usage.get("used_groups")));
        }
        if(excludedOrder!=null)for(Map<String,Object> r:db.reservations(excludedOrder,false)){
            if(!"RESERVED".equals(r.get("status")))continue;
            String key=number(r.get("beneficiary_id"))+":"+text(r.get("beneficiary_role"));
            groups.computeIfPresent(key+":"+text(r.get("rule_id")),(k,v)->Math.max(0,v-1));
        }
        if(excludedOrder!=null){
            Set<String> counted=new HashSet<>();
            for(Map<String,Object> r:db.reservations(excludedOrder,false))if("RESERVED".equals(r.get("status"))){
                String key=number(r.get("beneficiary_id"))+":"+text(r.get("beneficiary_role"));
                if(counted.add(key))orders.computeIfPresent(key,(k,v)->Math.max(0,v-1));
            }
        }
        return PromotionRules.evaluate(contract,items,buyer,inviter,PromotionPolicyResolver.content(policies,"firstPurchase"),now,groups,orders);
    }
    public static Map<String,Object> award(Award award) {
        return values("productNo",award.productNo(),"ruleId",award.ruleId(),"rewardRuleId",award.rewardRuleId(),
            "beneficiaryId",award.beneficiaryId(),"beneficiaryRole",award.role(),"unitSeq",award.unitSeq(),"reward",award.reward());
    }
}
