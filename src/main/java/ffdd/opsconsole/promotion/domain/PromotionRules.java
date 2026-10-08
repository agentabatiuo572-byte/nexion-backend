package ffdd.opsconsole.promotion.domain;

import ffdd.opsconsole.shared.exception.BizException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

/** Deterministic eligibility/group arithmetic; never writes accounts, quota or money. */
public final class PromotionRules {
    private PromotionRules() {}
    public record Selection(String productNo,int quantity) {}
    public record Account(long id,Instant registeredAt,String rank,String market,Long sponsorId,
                          boolean active,long everPaid,long validPaid,Instant lastValidPaid,long devices,long firstPurchasePaid) {
        public Account(long id,Instant registeredAt,String rank,String market,Long sponsorId,boolean active,long everPaid,long validPaid,Instant lastValidPaid,long devices){
            this(id,registeredAt,rank,market,sponsorId,active,everPaid,validPaid,lastValidPaid,devices,validPaid);
        }
    }
    public record Award(String productNo,String ruleId,String rewardRuleId,long beneficiaryId,
                        String role,int unitSeq,Map<String,Object> reward) {}
    public static long limit(Object value) {
        Map<String,Object> config=map(value);
        return "UNLIMITED".equals(config.get("mode"))?Long.MAX_VALUE:number(config.get("value"));
    }
    public static void selections(List<Selection> items) {
        if(items==null||items.isEmpty()||items.size()>100)throw new BizException(422,"PROMOTION_ITEMS_INVALID");
        Set<String> nos=new HashSet<>();long quantity=0;
        for(Selection item:items){if(item.productNo()==null||item.productNo().isBlank()||item.quantity()<1||!nos.add(item.productNo()))
            throw new BizException(422,"PROMOTION_ITEMS_INVALID");quantity+=item.quantity();}
        if(quantity>100)throw new BizException(422,"PROMOTION_ORDER_QUANTITY_LIMIT");
    }
    public static boolean eligible(Map<String,Object> audience,Account account,Instant now,boolean restoreRefunds) {
        if(account==null||!account.active())return false;
        String history=text(audience.get("purchaseHistory"));
        long paid=restoreRefunds?account.firstPurchasePaid():account.everPaid();
        if("NEVER_PAID".equals(history)&&paid>0)return false;
        if("HAS_VALID_PURCHASE".equals(history)&&account.validPaid()==0)return false;
        if(Boolean.TRUE.equals(audience.get("neverPurchased"))&&paid>0)return false;
        if(!days(audience.get("registrationAge"),account.registeredAt(),now))return false;
        Map<String,Object> last=map(audience.get("lastValidPurchaseAge"));
        if((last.get("minDays")!=null||last.get("maxDays")!=null)&&!days(last,account.lastValidPaid(),now))return false;
        if(!member(audience.get("rankIds"),account.rank())||!member(audience.get("markets"),account.market()))return false;
        String sponsor=text(audience.get("sponsor"));
        if("REQUIRED".equals(sponsor)&&account.sponsorId()==null)return false;
        if("ABSENT".equals(sponsor)&&account.sponsorId()!=null)return false;
        String presence=text(audience.get("devicePresence"));
        if("NONE".equals(presence)&&account.devices()!=0)return false;
        return !"HAS_QUALIFYING_DEVICE".equals(presence)||account.devices()>0;
    }
    private static boolean member(Object selected,String fact) {
        if(!(selected instanceof List<?> values))return false;
        return values.isEmpty()||(fact!=null&&!fact.isBlank()&&values.contains(fact));
    }
    private static boolean days(Object value,Instant occurred,Instant now) {
        Map<String,Object> range=map(value);
        if(range.get("minDays")==null&&range.get("maxDays")==null)return true;
        if(occurred==null||occurred.isAfter(now))return false;
        long days=ChronoUnit.DAYS.between(occurred,now);
        return (range.get("minDays")==null||days>=number(range.get("minDays")))&&(range.get("maxDays")==null||days<=number(range.get("maxDays")));
    }
    public static List<Award> evaluate(Map<String,Object> contract,List<Selection> items,Account buyer,Account inviter,
                                       Map<String,Object> firstPurchase,Instant now,Map<String,Long> usedGroups,
                                       Map<String,Long> usedOrders) {
        selections(items);String template=text(contract.get("template"));
        boolean restore=firstPurchase!=null&&Boolean.TRUE.equals(firstPurchase.get("restoreAfterRefund"));
        boolean buyerEligible=eligible(map(contract.get("buyerAudience")),buyer,now,restore);
        if(!buyerEligible)return List.of();
        if("DIRECT_REFERRAL".equals(template)&&(inviter==null||buyer.sponsorId()==null||buyer.sponsorId()!=inviter.id()||buyer.id()==inviter.id()))return List.of();
        if(Set.of("FIRST_PURCHASE","DIRECT_REFERRAL").contains(template)&&(firstPurchase==null||(restore?buyer.firstPurchasePaid():buyer.everPaid())>0))return List.of();
        if("REPURCHASE".equals(template)&&buyer.validPaid()==0)return List.of();
        Map<String,Integer> quantities=new LinkedHashMap<>();items.forEach(i->quantities.put(i.productNo(),i.quantity()));
        List<Map<String,Object>> rules=new ArrayList<>(maps(contract.get("rules")));
        rules.sort(Comparator.comparingLong((Map<String,Object> r)->number(r.get("priority"))).thenComparing(r->text(r.get("ruleId"))));
        if("ALL".equals(contract.get("combinationMatch"))&&rules.stream().anyMatch(r->quantities.getOrDefault(text(r.get("productNo")),0)<number(r.get("minBuyQty"))))return List.of();
        List<Award> result=new ArrayList<>();long maxUnits=limit(contract.get("maxRewardUnitsPerOrder"));
        boolean firstUnit=firstPurchase!=null&&"FIRST_ELIGIBLE_UNIT".equals(firstPurchase.get("mode"))&&Set.of("FIRST_PURCHASE","DIRECT_REFERRAL").contains(template);
        boolean selectedFirst=false;
        for(Map<String,Object> rule:rules){
            int qty=quantities.getOrDefault(text(rule.get("productNo")),0);long minimum=number(rule.get("minBuyQty"));
            long groups=qty/minimum;if("ONCE_PER_ORDER".equals(rule.get("repeatMode")))groups=Math.min(groups,1);
            groups=Math.min(groups,limit(rule.get("maxGroups")));
            if(firstUnit){groups=selectedFirst?0:Math.min(groups,1);if(groups>0)selectedFirst=true;}
            for(String role:List.of("BUYER","DIRECT_INVITER")){
                Account account="BUYER".equals(role)?buyer:inviter;
                Object spec=rule.get("BUYER".equals(role)?"buyerReward":"inviterReward");
                if(spec==null||account==null)continue;
                if("DIRECT_INVITER".equals(role)&&(!"DIRECT_REFERRAL".equals(template)||!eligible(map(contract.get("inviterAudience")),account,now,restore)))continue;
                Map<String,Object> roleLimits=map(contract.get("perPersonLimit"));
                if(usedOrders.getOrDefault(account.id()+":"+role,0L)>=limit(roleLimits.get("BUYER".equals(role)?"buyer":"directInviter")))continue;
                String ruleId=text(rule.get("ruleId"));long remaining=limit(rule.get("maxGroupsPerPerson"))-usedGroups.getOrDefault(account.id()+":"+role+":"+ruleId,0L);
                long count=Math.min(groups,Math.max(0,remaining));
                count=Math.min(count,Math.max(0,maxUnits-result.size()));
                Map<String,Object> reward=copy(map(spec));
                for(int unit=0;unit<count;unit++)result.add(new Award(text(rule.get("productNo")),ruleId,text(reward.get("rewardRuleId")),account.id(),role,unit,reward));
            }
        }
        return List.copyOf(result);
    }
}
