package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.growth.application.AppGrowthLifecyclePublisher;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.promotion.domain.PromotionRules;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.canonical.BundleDiscountPolicy;
import java.math.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

@Service
@RequiredArgsConstructor
public class PromotionQuoteService {
    private final PromotionMapper db;
    private final PromotionContractValidator validator;
    private final PromotionPolicyResolver resolver;
    private final PromotionEvaluationService evaluator;
    private final PlatformConfigFacade config;
    private final AppGrowthLifecyclePublisher vouchers;

    @Transactional
    public Map<String,Object> quote(Long userId,Map<String,Object> request) {
        validator.validate("QuoteInput",request);
        require(userId!=null,"PROMOTION_AUTHENTICATION_REQUIRED");
        db.user(userId,false);
        List<PromotionRules.Selection> items=selections(request);
        require(items.size()<=8,"BUNDLE_PRODUCT_COUNT_INVALID");
        Map<String,Object> price=prices(userId,items,text(request.get("voucherId")));
        Instant now=Instant.now();
        List<Map<String,Object>> candidates=db.list("""
            SELECT p.activity_id,p.active_version FROM nx_promotion p JOIN nx_promotion_version v
              ON v.activity_id=p.activity_id AND v.version=p.active_version
            WHERE p.status IN ('ACTIVE','SCHEDULED') AND v.status='PUBLISHED'
              AND v.starts_at<=? AND v.ends_at>? ORDER BY p.activity_id
            """,timestamp(now),timestamp(now));
        String selected=text(request.get("activityId"));
        List<Map<String,Object>> awarded=new ArrayList<>();
        Map<String,Object> chosen=null,contract=Map.of(),policies=Map.of();
        for(Map<String,Object> row:candidates) {
            if(!selected.isEmpty()&&!selected.equals(row.get("activity_id")))continue;
            Map<String,Object> version=db.version(text(row.get("activity_id")),number(row.get("active_version")),false);
            Map<String,Object> c=parse(version.get("contract_json"));validator.validate("PromotionContract",c);
            Map<String,Object> ps=resolver.resolve(c,false);
            require(((List<?>)request.get("clientCapabilities")).containsAll((List<?>)c.get("minimumClientCapabilities")),"PROMOTION_CLIENT_UPDATE_REQUIRED");
            List<Map<String,Object>> a=evaluator.evaluate(text(row.get("activity_id")),c,ps,userId,items,now,null).stream().map(PromotionEvaluationService::award).toList();
            if(a.isEmpty())continue;
            Map<String,Object> stacking=PromotionPolicyResolver.content(ps,"stacking");
            if(!text(request.get("voucherId")).isEmpty()&&!"ALLOW".equals(stacking.get("voucher")))continue;
            if(items.size()>1&&decimal(price.get("discountUsdt")).signum()>0&&!"ALLOW".equals(stacking.get("bundleDiscount")))continue;
            a=applyShortage(text(row.get("activity_id")),c,a);
            if(a.isEmpty())continue;
            require(chosen==null,"PROMOTION_EXCLUSIVE_ACTIVITY_CONFLICT");
            chosen=row;contract=c;policies=ps;awarded=new ArrayList<>(a);
        }
        if(!selected.isEmpty())require(chosen!=null,"PROMOTION_NOT_ELIGIBLE_OR_UNAVAILABLE");
        String quoteId=id("PQ");long ttl=chosen==null?60:number(PromotionPolicyResolver.content(policies,"quote").get("ttlSeconds"));
        Instant expiry=now.plusSeconds(ttl);
        if(chosen!=null&&expiry.isAfter(instant(contract.get("endsAt"))))expiry=instant(contract.get("endsAt"));
        String activity=chosen==null?null:text(chosen.get("activity_id"));
        Long version=chosen==null?null:number(chosen.get("active_version"));
        Map<String,Object> publicQuote=values("quoteId",quoteId,"quoteHash","0".repeat(64),"expiresAt",expiry.toString(),"serverTime",now.toString(),
            "itemCount",items.size(),"quantity",items.stream().mapToInt(PromotionRules.Selection::quantity).sum(),
            "items",price.get("items"),"subtotalUsdt",price.get("subtotalUsdt"),"discountUsdt",price.get("discountUsdt"),
            "amountUsdt",price.get("amountUsdt"),"bundlePolicyVersion",price.get("bundlePolicyVersion"),
            "activityId",activity,"activityVersion",version,"eligibility",chosen==null?"INELIGIBLE":"ELIGIBLE",
            "expectedRewards",expected(awarded,activity,version),"warnings",List.of(),"reserved",false);
        var expected=maps(publicQuote.get("expectedRewards"));
        for(var reward:expected)reward.put("disclosure",PromotionPublicService.disclosure(db,contract,policies,map(reward.get("reward"))));
        publicQuote.put("expectedRewards",expected);
        Map<String,Object> stored=values("public",publicQuote,"request",copy(request),"contract",contract,"policies",policies,"awards",awarded);
        String quoteHash=hash(stored);publicQuote.put("quoteHash",quoteHash);
        validator.validate("Quote",publicQuote);
        changed(db.write("INSERT INTO nx_promotion_quote(quote_id,user_id,request_hash,quote_hash,quote_json,expires_at) VALUES(?,?,?,?,?,?)",
            quoteId,userId,hash(request),quoteHash,json(stored),timestamp(expiry)));
        return publicQuote;
    }
    public Map<String,Object> prices(long user,List<PromotionRules.Selection> items,String voucher) {
        return prices(user,items,voucher,false);
    }
    public Map<String,Object> currentPrices(long user,List<PromotionRules.Selection> items,String voucher) {
        return prices(user,items,voucher,true);
    }
    private Map<String,Object> prices(long user,List<PromotionRules.Selection> items,String voucher,boolean currentRead) {
        PromotionRules.selections(items);require(items.size()<=8,"BUNDLE_PRODUCT_COUNT_INVALID");
        BigDecimal subtotal=BigDecimal.ZERO;
        List<Map<String,Object>> lines=new ArrayList<>();
        for(PromotionRules.Selection item:items){
            Map<String,Object> product=db.product(item.productNo(),currentRead);
            require("ACTIVE".equals(product.get("status"))&&number(product.get("store_visible"))==1&&decimal(product.get("price_usdt")).signum()>0,"PRODUCT_NOT_AVAILABLE");
            BigDecimal unit=decimal(product.get("price_usdt")).setScale(6,RoundingMode.UNNECESSARY),line=unit.multiply(BigDecimal.valueOf(item.quantity()));
            subtotal=subtotal.add(line);
            lines.add(values("lineId",item.productNo(),"productNo",item.productNo(),"quantity",item.quantity(),"unitPriceUsdt",money(unit),"subtotalUsdt",money(line)));
        }
        BigDecimal discount=BigDecimal.ZERO;long policyVersion=1;
        if(items.size()>1){
            require(voucher.isEmpty(),"BUNDLE_VOUCHER_UNSUPPORTED");
            discount=subtotal.multiply(BundleDiscountPolicy.require(config::activeValue).rateFor(items.size())).setScale(6,RoundingMode.HALF_UP);
            policyVersion=Long.parseLong(config.activeValue(BundleDiscountPolicy.VERSION_KEY).orElseThrow());
        }else if(!voucher.isEmpty())discount=vouchers.prepareVoucher(user,voucher,items.get(0).productNo(),subtotal).discountUsdt();
        BigDecimal allocated=BigDecimal.ZERO;
        for(int i=0;i<lines.size();i++){
            Map<String,Object> line=lines.get(i);BigDecimal lineSub=amount(line.get("subtotalUsdt"));
            BigDecimal part=i==lines.size()-1?discount.subtract(allocated):discount.multiply(lineSub).divide(subtotal,6,RoundingMode.DOWN);
            allocated=allocated.add(part);line.put("discountUsdt",money(part));line.put("payableUsdt",money(lineSub.subtract(part)));
        }
        return values("items",lines,"subtotalUsdt",money(subtotal),"discountUsdt",money(discount),"amountUsdt",money(subtotal.subtract(discount)),"bundlePolicyVersion",policyVersion);
    }
    public boolean available(String activity,Map<String,Object> contract,List<Map<String,Object>> awards) {
        return available(activity,contract,awards,false);
    }
    public boolean currentlyAvailable(String activity,Map<String,Object> contract,List<Map<String,Object>> awards) {
        return available(activity,contract,awards,true);
    }
    private boolean available(String activity,Map<String,Object> contract,List<Map<String,Object>> awards,boolean currentRead) {
        Map<String,Object> root=db.activity(activity,currentRead);
        if(number(root.get("reserved_orders"))+number(root.get("used_orders"))>=PromotionRules.limit(contract.get("activityLimit")))return false;
        Map<String,BigDecimal> sums=new TreeMap<>();
        for(Map<String,Object> award:awards){Map<String,Object> r=map(award.get("reward"));String asset=text(r.get("type")),product="DEVICE".equals(asset)?text(r.get("giftProductNo")):"";
            sums.merge(asset+":"+product,rewardAmount(r),BigDecimal::add);}
        for(var e:sums.entrySet()){
            String[] key=e.getKey().split(":",2);
            Map<String,Object> b=db.one("SELECT * FROM nx_promotion_budget WHERE activity_id=? AND asset=? AND product_no=?"+(currentRead?" FOR UPDATE":""),activity,key[0],key[1]);
            if(b==null||decimal(b.get("total")).subtract(decimal(b.get("reserved"))).subtract(decimal(b.get("committed"))).subtract(decimal(b.get("issued"))).add(decimal(b.get("reversed"))).compareTo(e.getValue())<0)return false;
            if("DEVICE".equals(key[0])){Map<String,Object> p=db.product(key[1],currentRead);if(!"UNLIMITED".equals(p.get("inventory_mode"))&&decimal(p.get("stock")).compareTo(e.getValue())<0)return false;}
        }
        return true;
    }
    private List<Map<String,Object>> applyShortage(String activity,Map<String,Object> contract,List<Map<String,Object>> awards){
        if(available(activity,contract,awards))return awards;
        if("ACTIVITY_PAUSE".equals(contract.get("shortagePolicy")))return List.of();
        Map<String,List<Map<String,Object>>> rules=new LinkedHashMap<>();
        for(Map<String,Object> award:awards)rules.computeIfAbsent(text(award.get("ruleId")),k->new ArrayList<>()).add(award);
        List<Map<String,Object>> accepted=new ArrayList<>();
        for(List<Map<String,Object>> rule:rules.values()){
            List<Map<String,Object>> proposed=new ArrayList<>(accepted);proposed.addAll(rule);
            if(available(activity,contract,proposed))accepted=proposed;
        }
        return accepted;
    }
    public static BigDecimal rewardAmount(Map<String,Object> r){return "DEVICE".equals(r.get("type"))?BigDecimal.valueOf(number(r.get("quantity"))):amount(r.get("amount"));}
    public static List<PromotionRules.Selection> selections(Map<String,Object> request){return maps(request.get("items")).stream().map(i->new PromotionRules.Selection(text(i.get("productNo")),Math.toIntExact(number(i.get("quantity"))))).toList();}
    public static List<Map<String,Object>> expected(List<Map<String,Object>> awards,String activity,Long version){
        if(activity==null)return List.of();
        Map<String,Map<String,Object>> grouped=new LinkedHashMap<>();
        for(Map<String,Object> a:awards){String key=text(a.get("productNo"))+":"+text(a.get("rewardRuleId"));
            Map<String,Object> item=grouped.computeIfAbsent(key,k->values("lineId",a.get("productNo"),"activityId",activity,"version",version,"ruleId",a.get("ruleId"),"rewardRuleId",a.get("rewardRuleId"),
                "beneficiaryRole",a.get("beneficiaryRole"),"groups",0,"reward",a.get("reward"),"eligibility","ELIGIBLE","message",null));
            item.put("groups",number(item.get("groups"))+1);}
        return new ArrayList<>(grouped.values());
    }
}
