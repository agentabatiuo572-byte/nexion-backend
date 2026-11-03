package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.growth.application.AppGrowthLifecyclePublisher;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.promotion.domain.PromotionRules;
import ffdd.opsconsole.promotion.domain.PromotionValues;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.canonical.BundleDiscountPolicy;
import ffdd.opsconsole.shared.canonical.AppCanonicalBoundaryService;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static ffdd.opsconsole.promotion.domain.PromotionLegacyAssetView.asset;

@Service
@RequiredArgsConstructor
public class PromotionQuoteService {
    private final PromotionMapper db;
    private final PromotionContractValidator validator;
    private final PromotionPolicyResolver resolver;
    private final PromotionEvaluationService evaluator;
    private final PlatformConfigFacade config;
    private final AppGrowthLifecyclePublisher vouchers;
    // Canonical commerce also depends on PromotionOrderService; resolve only when evaluating a purchase.
    private final ObjectProvider<AppCanonicalBoundaryService> purchaseEligibility;

    @Transactional
    public Map<String,Object> quote(Long userId,Map<String,Object> request) {
        validator.validate("QuoteInput",request);
        require(userId!=null,"PROMOTION_AUTHENTICATION_REQUIRED");
        db.user(userId,true);
        List<PromotionRules.Selection> items=selections(request);
        require(items.size()<=8,"BUNDLE_PRODUCT_COUNT_INVALID");
        String voucher = text(request.get("voucherId"));
        boolean spentVoucher = false;
        Map<String,Object> price;
        try {
            price=prices(userId,items,voucher);
        } catch (BizException rejected) {
            if (!"VOUCHER_NOT_AVAILABLE_OR_NOT_APPLICABLE".equals(rejected.getMessage()) || items.size()!=1) throw rejected;
            // The first creation redeemed this grant. Only its live order can recover this discount.
            var redeemed=db.one("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(r.response_json,'$.data.voucherRedemption.discountUsdt')) discount
                  FROM nx_growth_voucher_grant g JOIN nx_order o ON o.order_no=g.used_order_no AND o.user_id=g.user_id
                  JOIN nx_order_item i ON i.order_no=o.order_no AND i.product_id=o.product_id AND i.is_deleted=0
                  JOIN nx_product p ON p.id=i.product_id AND p.price_usdt=i.unit_price_usdt
                  JOIN nx_promotion_order_receipt pr ON pr.order_id=o.id AND pr.buyer_id=g.user_id
                  JOIN nx_admin_idempotency_record r ON r.scope=CONCAT('APP:ORDER_CREATE:USER:',g.user_id)
                    AND r.status='SUCCEEDED' AND r.is_deleted=0
                    AND JSON_UNQUOTE(JSON_EXTRACT(r.response_json,'$.data.orderNo'))=o.order_no
                    AND JSON_UNQUOTE(JSON_EXTRACT(r.response_json,'$.data.voucherRedemption.grantId'))=g.grant_id
                 WHERE g.user_id=? AND g.voucher_id=? AND g.status='USED' AND g.is_deleted=0
                   AND o.is_deleted=0 AND o.order_status='PENDING_PAYMENT' AND o.payment_status='PENDING'
                   AND o.activation_status='WAITING_PAYMENT' AND o.created_at>NOW()-INTERVAL 24 HOUR AND pr.pay_by>NOW(6)
                   AND o.created_at>TIMESTAMPADD(MINUTE,-?,NOW(6))
                   AND BINARY i.product_no=BINARY ? AND i.quantity=?
                 ORDER BY o.created_at,o.id,r.id LIMIT 1
                """,userId,voucher,purchaseEligibility.getObject().pendingOrderTtlMinutes(),items.get(0).productNo(),items.get(0).quantity());
            if (redeemed==null) throw rejected;
            price=prices(userId,items,voucher,false,decimal(redeemed.get("discount")));
            spentVoucher=true;
        }
        Instant now=Instant.now();
        List<Map<String,Object>> candidates=db.list("""
            SELECT p.activity_id,p.active_version FROM nx_promotion p JOIN nx_promotion_version v
              ON v.activity_id=p.activity_id AND v.version=p.active_version
            WHERE p.deleted_at IS NULL AND p.status IN ('ACTIVE','SCHEDULED') AND v.status='PUBLISHED'
              AND v.starts_at<=? AND v.ends_at>? ORDER BY p.activity_id
            """,timestamp(now),timestamp(now));
        String selected=text(request.get("activityId"));
        List<Map<String,Object>> awarded=new ArrayList<>();
        Map<String,Object> chosen=null,contract=Map.of(),policies=Map.of();
        var recent = reusableQuotes(userId,true);
        ReusableQuote reserved = null;
        for(Map<String,Object> row:candidates) {
            if(selected.isEmpty()&&Boolean.FALSE.equals(request.get("autoSelectPromotion")))break;
            if(!selected.isEmpty()&&!selected.equals(row.get("activity_id")))continue;
            Map<String,Object> version=db.version(text(row.get("activity_id")),number(row.get("active_version")),false);
            Map<String,Object> c=parse(version.get("contract_json"));
            require(hash(c).equals(version.get("content_hash")),"PROMOTION_VERSION_INVALID");
            validator.validateRecorded("PromotionContract",c);
            Map<String,Object> ps=resolver.resolve(c,false);
            require(((List<?>)request.get("clientCapabilities")).containsAll((List<?>)c.get("minimumClientCapabilities")),"PROMOTION_CLIENT_UPDATE_REQUIRED");
            Map<String,Object> stacking=PromotionPolicyResolver.content(ps,"stacking");
            if(!text(request.get("voucherId")).isEmpty()
                    &&(!"ALLOW".equals(stacking.get("voucher"))||!vouchers.allowsPromotionStacking(text(request.get("voucherId")))))continue;
            if(items.size()>1&&decimal(price.get("discountUsdt")).signum()>0&&!"ALLOW".equals(stacking.get("bundleDiscount")))continue;
            String activity=text(row.get("activity_id"));
            // The order already holds its share of the campaign budget and usage limits.
            // Restore only after the current quote matches its complete signed terms.
            for (var prior : recent) {
                if (!prior.pending() || !activity.equals(prior.quote().get("activityId"))
                        || !Objects.equals(text(prior.quote().get("activityVersion")),text(row.get("active_version")))
                        || !voucher.equals(text(map(prior.snapshot().get("request")).get("voucherId")))
                        || !new HashSet<>(items).equals(new HashSet<>(selections(map(prior.snapshot().get("request")))))) continue;
                var held = evaluator.evaluate(activity,c,ps,userId,items,now,prior.orderNo()).stream()
                        .map(PromotionEvaluationService::award).toList();
                if (held.isEmpty()) continue;
                var reservation = db.reservations(prior.orderNo(),false);
                if (reservation.size()!=held.size() || reservation.stream().anyMatch(r->!"RESERVED".equals(r.get("status")))) continue;
                var terms = quoteSnapshot(request,items,row,c,ps,price,held,now);
                if (checkoutTerms(terms).equals(checkoutTerms(prior.snapshot()))) {
                    require(chosen==null,"PROMOTION_EXCLUSIVE_ACTIVITY_CONFLICT");
                    chosen=row; reserved=prior;
                    break;
                }
            }
            if (reserved!=null && chosen==row) continue;
            var a=evaluator.evaluate(activity,c,ps,userId,items,now,null).stream().map(PromotionEvaluationService::award).toList();
            a=applyShortage(activity,c,a);
            if(a.isEmpty())continue;
            require(chosen==null,"PROMOTION_EXCLUSIVE_ACTIVITY_CONFLICT");
            chosen=row;contract=c;policies=ps;awarded=new ArrayList<>(a);
        }
        if(!selected.isEmpty())require(chosen!=null,"PROMOTION_NOT_ELIGIBLE_OR_UNAVAILABLE");
        if (reserved!=null) return reserved.at(now);
        var stored = quoteSnapshot(request,items,chosen,contract,policies,price,awarded,now);
        var reusable = equivalentQuote(recent, stored);
        if (reusable != null && reusable.pending()) return reusable.at(now);
        require(!spentVoucher,"VOUCHER_NOT_AVAILABLE_OR_NOT_APPLICABLE");
        requirePurchaseEligibility(userId,items);
        require(db.occupiedDeviceSlots(userId)+items.stream().mapToLong(PromotionRules.Selection::quantity).sum()
                <=db.deviceSlotCap(),"CAPACITY_REPLACEMENT_REQUIRED");
        if (reusable != null) return reusable.at(now);
        var publicQuote=map(stored.get("public"));
        String quoteHash=hash(stored);publicQuote.put("quoteHash",quoteHash);stored.put("public",publicQuote);
        validator.validate("Quote",publicQuote);
        changed(db.write("INSERT INTO nx_promotion_quote(quote_id,user_id,request_hash,quote_hash,quote_json,expires_at) VALUES(?,?,?,?,?,?)",
            publicQuote.get("quoteId"),userId,hash(request),quoteHash,json(stored),timestamp(instant(publicQuote.get("expiresAt")))));
        return publicQuote;
    }

    private Map<String,Object> quoteSnapshot(Map<String,Object> request,List<PromotionRules.Selection> items,
            Map<String,Object> chosen,Map<String,Object> contract,Map<String,Object> policies,
            Map<String,Object> basePrice,List<Map<String,Object>> awarded,Instant now) {
        var price=applyDiscounts(basePrice,awarded);
        String quoteId=id("PQ");long ttl=chosen==null?60:number(PromotionPolicyResolver.content(policies,"quote").get("ttlSeconds"));
        Instant expiry=now.plusSeconds(ttl);
        if(chosen!=null&&expiry.isAfter(instant(contract.get("endsAt"))))expiry=instant(contract.get("endsAt"));
        String activity=chosen==null?null:text(chosen.get("activity_id"));
        Long version=chosen==null?null:number(chosen.get("active_version"));
        // Ordinary purchases need no promotion; explicit activities must pass the selection guard above.
        boolean eligible=chosen!=null||text(request.get("activityId")).isEmpty();
        Map<String,Object> publicQuote=values("quoteId",quoteId,"quoteHash","0".repeat(64),"expiresAt",expiry.toString(),"serverTime",now.toString(),
            "itemCount",items.size(),"quantity",items.stream().mapToInt(PromotionRules.Selection::quantity).sum(),
            "items",price.get("items"),"subtotalUsdt",price.get("subtotalUsdt"),"discountUsdt",price.get("discountUsdt"),
            "promotionDiscountUsdt",price.get("promotionDiscountUsdt"),
            "amountUsdt",price.get("amountUsdt"),"bundlePolicyVersion",price.get("bundlePolicyVersion"),
            "activityId",activity,"activityVersion",version,"eligibility",eligible?"ELIGIBLE":"INELIGIBLE",
            "expectedRewards",expected(awarded,activity,version),"warnings",List.of(),"reserved",false);
        var expected=maps(publicQuote.get("expectedRewards"));
        for(var reward:expected)reward.put("disclosure",PromotionPublicService.disclosure(db,contract,policies,map(reward.get("reward"))));
        publicQuote.put("expectedRewards",expected);
        return values("public",publicQuote,"request",request,"contract",contract,"policies",policies,"awards",awarded);
    }

    /** The caller holds the buyer lock. A fresh device can confirm the same signed quote and order. */
    record ReusableQuote(Map<String,Object> snapshot, String orderNo, Instant payBy) {
        Map<String,Object> quote() { return map(snapshot.get("public")); }
        boolean pending() { return orderNo!=null; }
        Map<String,Object> at(Instant now) {
            var view=quote();
            view.put("serverTime",now.toString());
            if (pending()) view.put("expiresAt",payBy.toString());
            return view;
        }
    }

    List<ReusableQuote> reusableQuotes(long buyer, boolean includeUnused) {
        var rows = db.list("""
            SELECT q.quote_json,q.quote_hash,o.order_no,pr.pay_by FROM nx_promotion_quote q
             LEFT JOIN nx_promotion_order_receipt pr ON pr.quote_id=q.quote_id AND pr.buyer_id=q.user_id
             LEFT JOIN nx_order o ON o.id=pr.order_id AND o.user_id=q.user_id
             WHERE q.user_id=? AND ((o.created_at > NOW() - INTERVAL 24 HOUR
                     AND o.is_deleted=0 AND o.payment_status='PENDING' AND o.order_status='PENDING_PAYMENT'
                     AND o.activation_status='WAITING_PAYMENT' AND pr.pay_by>NOW(6))
                    OR (? AND pr.order_id IS NULL AND q.created_at > NOW() - INTERVAL 24 HOUR AND q.expires_at>NOW(6) AND NOT EXISTS (
                       SELECT 1 FROM nx_admin_idempotency_record r
                        WHERE r.scope IN (CONCAT('APP:ORDER_CREATE:USER:',q.user_id),CONCAT('APP:BUNDLE_ORDER_CREATE:USER:',q.user_id))
                          AND r.is_deleted=0 AND r.status='SUCCEEDED'
                          AND JSON_UNQUOTE(JSON_EXTRACT(r.response_json,'$.data.recoveredQuoteId'))=q.quote_id)))
             ORDER BY pr.order_id IS NULL,o.created_at,o.id,q.created_at,q.quote_id
            """,buyer,includeUnused);
        List<ReusableQuote> candidates=new ArrayList<>();
        for (var row : rows) {
            var snapshot=verifiedQuote(row);
            var candidate=new ReusableQuote(snapshot,(String)row.get("order_no"),
                    row.get("pay_by")==null?null:instant(row.get("pay_by")));
            if (candidate.pending()) {
                // Exactly the same receipt and deadline proof that POST /api/orders[/bundle] will read.
                var request=map(snapshot.get("request"));
                var items=selections(request);
                var pub=candidate.quote();
                String quoteId=text(pub.get("quoteId"));
                int ttl=purchaseEligibility.getObject().pendingOrderTtlMinutes();
                String receipt;
                if (items.size()==1) {
                    var item=items.get(0);
                    String voucher=text(request.get("voucherId"));
                    receipt=db.pendingSingleReceipt(buyer,null,item.productNo(),item.quantity(),
                            voucher.isBlank()?null:voucher.trim(),quoteId,ttl);
                } else {
                    Map<String,Integer> quantities=new LinkedHashMap<>();
                    for (var item : items) quantities.put(item.productNo(),item.quantity());
                    receipt=db.pendingBundleReceipt(buyer,quantities,quantities.size(),amount(pub.get("amountUsdt")),
                            number(pub.get("bundlePolicyVersion")),quoteId,ttl);
                }
                if (receipt==null) continue;
            }
            candidates.add(candidate);
        }
        return candidates;
    }

    ReusableQuote equivalentQuote(List<ReusableQuote> candidates, Map<String,Object> requested) {
        if (candidates.isEmpty()) return null;
        String terms = checkoutTerms(requested);
        for (var candidate : candidates)
            if (terms.equals(checkoutTerms(candidate.snapshot()))) return candidate;
        return null;
    }

    static Map<String,Object> verifiedQuote(Map<String,Object> row) {
        var snapshot = parse(row.get("quote_json"));
        var pub = map(snapshot.get("public"));
        Object savedHash = pub.put("quoteHash","0".repeat(64));
        snapshot.put("public",pub);
        require(Objects.equals(savedHash,row.get("quote_hash")) && hash(snapshot).equals(savedHash),"PROMOTION_QUOTE_CORRUPT");
        pub.put("quoteHash",savedHash);
        return snapshot;
    }

    private String checkoutTerms(Map<String,Object> snapshot) {
        var terms = map(snapshot.get("public"));
        for (String issued : List.of("quoteId","quoteHash","serverTime","expiresAt")) terms.remove(issued);
        // A reversed bundle is equivalent only if each line still has the same allocated discount.
        for (String array : List.of("items","expectedRewards"))
            terms.put(array,maps(terms.get(array)).stream().map(PromotionValues::hash).sorted().toList());
        return hash(values("terms",terms,"voucher",text(map(snapshot.get("request")).get("voucherId")),
                "contract",snapshot.get("contract"),"policies",snapshot.get("policies"),
                "awards",maps(snapshot.get("awards")).stream().map(PromotionValues::hash).sorted().toList()));
    }
    // Simulation catches unavailable facts; keep that failed read from marking its transaction rollback-only.
    @Transactional(readOnly=true,propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void requirePurchaseEligibility(long user,List<PromotionRules.Selection> items) {
        var products=items.stream().map(PromotionRules.Selection::productNo).toList();
        var result=purchaseEligibility.getObject().purchaseEligibilityBatch(user,products);
        if(result==null)throw new BizException(503,"PURCHASE_ELIGIBILITY_UNAVAILABLE");
        if(result.getCode()!=0)throw new BizException(result.getCode(),result.getMessage());
        for(String product:products){
            var decision=result.getData()==null?null:result.getData().get(product);
            if(decision==null)throw new BizException(503,"PURCHASE_ELIGIBILITY_UNAVAILABLE");
            if(!decision.eligible()){
                String code=decision.decisionCode();
                boolean rejected=Set.of("F4B_REQUIREMENTS_NOT_MET","F4B_MONTHLY_QUOTA_EXHAUSTED","PURCHASE_GATE_NOT_MET","PURCHASE_GATE_SOLD_OUT","PRODUCT_NOT_RELEASED").contains(code);
                throw new BizException(rejected?409:503,code);
            }
        }
    }
    public Map<String,Object> prices(long user,List<PromotionRules.Selection> items,String voucher) {
        return prices(user,items,voucher,false,null);
    }
    public Map<String,Object> currentPrices(long user,List<PromotionRules.Selection> items,String voucher) {
        return prices(user,items,voucher,true,null);
    }
    public void requireVoucherPromotionStacking(String voucher){
        require(vouchers.allowsPromotionStacking(voucher),"VOUCHER_PROMOTION_STACKING_NOT_ALLOWED");
    }
    private Map<String,Object> prices(long user,List<PromotionRules.Selection> items,String voucher,boolean currentRead,
                                      BigDecimal redeemedDiscount) {
        PromotionRules.selections(items);require(items.size()<=8,"BUNDLE_PRODUCT_COUNT_INVALID");
        BigDecimal subtotal=BigDecimal.ZERO;
        List<Map<String,Object>> lines=new ArrayList<>();
        for(PromotionRules.Selection item:items){
            Map<String,Object> product=db.product(item.productNo(),currentRead);
            require(productOnSale(product.get("status"))&&number(product.get("store_visible"))==1&&decimal(product.get("price_usdt")).signum()>0,"PRODUCT_NOT_AVAILABLE");
            BigDecimal unit=decimal(product.get("price_usdt")).setScale(6,RoundingMode.UNNECESSARY),line=unit.multiply(BigDecimal.valueOf(item.quantity()));
            subtotal=subtotal.add(line);
            lines.add(values("lineId",item.productNo(),"productNo",item.productNo(),"quantity",item.quantity(),"unitPriceUsdt",money(unit),"subtotalUsdt",money(line)));
        }
        BigDecimal discount=BigDecimal.ZERO;long policyVersion=1;
        if(items.size()>1){
            require(voucher.isEmpty(),"BUNDLE_VOUCHER_UNSUPPORTED");
            discount=subtotal.multiply(BundleDiscountPolicy.require(config::activeValue).rateFor(items.size())).setScale(6,RoundingMode.HALF_UP);
            policyVersion=Long.parseLong(config.activeValue(BundleDiscountPolicy.VERSION_KEY).orElseThrow());
        }else if(!voucher.isEmpty())discount=redeemedDiscount==null
                ?vouchers.prepareVoucher(user,voucher,items.get(0).productNo(),subtotal).discountUsdt():redeemedDiscount;
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
        if(root.get("deleted_at")!=null)return false;
        if(number(root.get("reserved_orders"))+number(root.get("used_orders"))>=PromotionRules.limit(contract.get("activityLimit")))return false;
        Map<String,BigDecimal> sums=new TreeMap<>();
        for(Map<String,Object> award:awards){Map<String,Object> r=map(award.get("reward"));String asset=asset(r.get("type")),product="DEVICE".equals(asset)?text(r.get("giftProductNo")):"";
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
    /** Price adjustments stay in the originating purchase line; they are never wallet rewards. */
    public static Map<String,Object> applyDiscounts(Map<String,Object> base,List<Map<String,Object>> awards){
        Map<String,BigDecimal> discounts=new HashMap<>();
        for(var award:awards){var reward=map(award.get("reward"));if(!"DISCOUNT_USDT".equals(reward.get("type")))continue;
            require("BUYER".equals(award.get("beneficiaryRole")),"PROMOTION_DISCOUNT_BUYER_REQUIRED");
            discounts.merge(text(award.get("productNo")),rewardAmount(reward),BigDecimal::add);
        }
        var result=copy(base);var lines=new ArrayList<Map<String,Object>>();BigDecimal total=BigDecimal.ZERO;
        for(var original:maps(base.get("items"))){var line=copy(original);BigDecimal discount=discounts.remove(text(line.get("productNo")));
            if(discount==null)discount=BigDecimal.ZERO;
            BigDecimal payable=amount(line.get("payableUsdt"));require(discount.signum()>=0&&discount.compareTo(payable)<=0,"PROMOTION_DISCOUNT_EXCEEDS_LINE_AMOUNT");
            line.put("promotionDiscountUsdt",money(discount));line.put("discountUsdt",money(amount(line.get("discountUsdt")).add(discount)));
            line.put("payableUsdt",money(payable.subtract(discount)));lines.add(line);total=total.add(discount);
        }
        require(discounts.isEmpty(),"PROMOTION_DISCOUNT_LINE_MISSING");
        BigDecimal payable=amount(base.get("amountUsdt")).subtract(total);
        // E4's canonical cash refund requires a positive paid amount.
        require(total.signum()==0||payable.signum()>0,"PROMOTION_DISCOUNT_REQUIRES_PAYMENT");
        result.put("items",lines);result.put("promotionDiscountUsdt",money(total));
        result.put("discountUsdt",money(amount(base.get("discountUsdt")).add(total)));
        result.put("amountUsdt",money(payable));return result;
    }
    public static List<PromotionRules.Selection> selections(Map<String,Object> request){return maps(request.get("items")).stream().map(i->new PromotionRules.Selection(text(i.get("productNo")),Math.toIntExact(number(i.get("quantity"))))).toList();}
    public static List<Map<String,Object>> expected(List<Map<String,Object>> awards,String activity,Long version){
        if(activity==null)return List.of();
        Map<String,Map<String,Object>> grouped=new LinkedHashMap<>();
        for(Map<String,Object> a:awards){String key=text(a.get("productNo"))+":"+text(a.get("rewardRuleId"));
            Map<String,Object> item=grouped.computeIfAbsent(key,k->values("lineId",a.get("productNo"),"activityId",activity,"version",version,"ruleId",a.get("ruleId"),"rewardRuleId",a.get("rewardRuleId"),
                "beneficiaryRole",a.get("beneficiaryRole"),"groups",0,"reward",ffdd.opsconsole.promotion.domain.PromotionLegacyAssetView.reward(map(a.get("reward"))),"eligibility","ELIGIBLE","message",null));
            item.put("groups",number(item.get("groups"))+1);}
        return new ArrayList<>(grouped.values());
    }
}
