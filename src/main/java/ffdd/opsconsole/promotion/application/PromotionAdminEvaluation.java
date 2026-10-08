package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.promotion.domain.PromotionRules;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

/** Read-only operator diagnostics. Eligibility and reward quantities always come from the shared evaluator. */
@RequiredArgsConstructor
final class PromotionAdminEvaluation {
    private final PromotionMapper db;
    private final PromotionContractValidator validator;
    private final PromotionPolicyResolver resolver;
    private final PromotionEvaluationService evaluator;
    private final PromotionQuoteService quotes;

    Map<String,Object> audience(Map<String,Object> contract) {
        validator.validate("Audience",contract.get("buyerAudience"));
        if(contract.get("inviterAudience")!=null)validator.validate("Audience",contract.get("inviterAudience"));
        Instant now=Instant.now();Map<String,Object> resolved=Map.of();String dependency=null;
        try{resolved=resolver.resolveAudience(contract);}catch(BizException|DataAccessException failure){dependency="AUDIENCE_POLICY_UNAVAILABLE";}
        List<Map<String,Object>> accounts;
        try{accounts=db.list("SELECT id FROM nx_user WHERE is_deleted=0 AND sandbox=0 ORDER BY id");}
        catch(DataAccessException failure){return values("asOf",now.toString(),"estimatedAccounts",null,"matched",null,"rejected",null,"unknown",null,"total",null,"completeness","UNAVAILABLE","conditionSummary",conditions(contract),"source",sources(),"reasonCounts",List.of(reasonCount("ACCOUNT_SOURCE_UNAVAILABLE",null)),"samples",List.of(),"audiences",List.of(),"matchedConditionIds",List.of(),"warnings",List.of(message("ACCOUNT_SOURCE_UNAVAILABLE")),"qualificationCredential",false);}
        var roles=new ArrayList<Map<String,Object>>();var samples=new ArrayList<Map<String,Object>>();
        for(String role:List.of("buyerAudience","inviterAudience")){
            if(contract.get(role)==null)continue;var audience=map(contract.get(role));long matched=0,rejected=0,unknown=0;var reasons=new TreeMap<String,Long>();
            for(var row:accounts){long account=number(row.get("id"));List<String> failures;String status;
                try{if(dependency!=null){failures=List.of(dependency);status="UNKNOWN";}else{failures=audienceFailures(contract,role,account,audience,resolved,now);status=failures.isEmpty()?"MATCHED":"REJECTED";}}
                catch(BizException|DataAccessException failure){failures=List.of("ACCOUNT_FACTS_UNAVAILABLE");status="UNKNOWN";}
                if("MATCHED".equals(status))matched++;else if("UNKNOWN".equals(status))unknown++;else rejected++;
                failures.forEach(code->reasons.merge(code,1L,Long::sum));
                if(PromotionAdminService.has("user_c1_read")&&"buyerAudience".equals(role)&&samples.size()<20)samples.add(values("accountId",String.valueOf(account),"status",status,"reasons",diagnostics(failures)));
            }
            roles.add(values("role","buyerAudience".equals(role)?"BUYER":"DIRECT_INVITER","conditions",audience,"matched",matched,"rejected",rejected,"unknown",unknown,"reasonCounts",reasons.entrySet().stream().map(e->reasonCount(e.getKey(),e.getValue())).toList()));
        }
        var primary=roles.get(0);long unknown=number(primary.get("unknown"));
        return values("asOf",now.toString(),"estimatedAccounts",unknown==0?primary.get("matched"):null,"matched",primary.get("matched"),"rejected",primary.get("rejected"),"unknown",unknown,"total",accounts.size(),"completeness",unknown==0&&dependency==null?"COMPLETE":"UNAVAILABLE","conditionSummary",conditions(contract),"source",sources(),"reasonCounts",primary.get("reasonCounts"),"samples",samples,"audiences",roles,"matchedConditionIds",new ArrayList<>(map(contract.get("buyerAudience")).keySet()),"warnings",dependency==null?List.of():List.of(message(dependency)),"qualificationCredential",false);
    }
    private Map<String,Object> conditions(Map<String,Object> c){return values("template",c.get("template"),"buyerAudience",c.get("buyerAudience"),"inviterAudience",c.get("inviterAudience"));}
    private List<String> sources(){return List.of("nx_user excluding sandbox/deleted","nx_order paid/refunded history","nx_user_device with approved audience policy","nx_promotion_device_receipt");}
    private List<String> audienceFailures(Map<String,Object> c,String role,long id,Map<String,Object> audience,Map<String,Object> resolved,Instant now){
        var account=evaluator.account(id,audience,resolved,null);var first=PromotionPolicyResolver.content(resolved,"firstPurchase");boolean restore=first!=null&&Boolean.TRUE.equals(first.get("restoreAfterRefund"));
        var failures=new ArrayList<String>();
        if(!account.active())failures.add("ACCOUNT_INACTIVE");
        for(String key:List.of("registrationAge","purchaseHistory","lastValidPurchaseAge","neverPurchased","rankIds","markets","sponsor","devicePresence")){
            var one=anyAudience();one.put(key,audience.get(key));
            if(account.active()&&!PromotionRules.eligible(one,account,now,restore))failures.add("AUDIENCE_"+key.toUpperCase(Locale.ROOT));
        }
        if("buyerAudience".equals(role)){
            if(Set.of("FIRST_PURCHASE","DIRECT_REFERRAL").contains(text(c.get("template")))&&(restore?account.firstPurchasePaid():account.everPaid())>0)failures.add("FIRST_PURCHASE_ALREADY_USED");
            if("REPURCHASE".equals(c.get("template"))&&account.validPaid()==0)failures.add("VALID_PURCHASE_REQUIRED");
            if("DIRECT_REFERRAL".equals(c.get("template"))&&(account.sponsorId()==null||account.sponsorId()==id))failures.add("DIRECT_INVITER_REQUIRED");
        }
        return failures;
    }
    private Map<String,Object> anyAudience(){return values("registrationAge",values("minDays",null,"maxDays",null),"purchaseHistory","ANY","devicePresence","ANY","deviceAudiencePolicy",null,"rankIds",List.of(),"lastValidPurchaseAge",values("minDays",null,"maxDays",null),"neverPurchased",false,"markets",List.of(),"sponsor","ANY");}

    Map<String,Object> simulate(String activity,Map<String,Object> root,Map<String,Object> c,Map<String,Object> request){
        Instant now=Instant.now();Long account=request.get("sampleAccountId")==null?null:number(request.get("sampleAccountId"));var items=PromotionQuoteService.selections(request);PromotionRules.selections(items);
        var blockers=new ArrayList<String>();Map<String,Object> resolved=Map.of(),price=null;List<Map<String,Object>> awards=List.of();
        try{resolved=resolver.resolve(c,false);}catch(BizException|DataAccessException failure){blockers.add("EXECUTABLE_POLICY_UNAVAILABLE");}
        try{price=quotes.prices(account==null?0:account,items,"");}catch(BizException|DataAccessException failure){blockers.add("PRICING_SOURCE_UNAVAILABLE");}
        if(account==null)blockers.add("SAMPLE_ACCOUNT_REQUIRED");
        if(blockers.isEmpty())try{awards=evaluator.evaluate(activity,c,resolved,account,items,now,null).stream().map(PromotionEvaluationService::award).toList();}catch(BizException|DataAccessException failure){blockers.add("ACCOUNT_FACTS_UNAVAILABLE");}
        var results=new ArrayList<Map<String,Object>>();var units=new ArrayList<Map<String,Object>>();
        Map<String,Integer> quantities=new HashMap<>();items.forEach(item->quantities.put(item.productNo(),item.quantity()));
        for(var rule:maps(c.get("rules"))){
            int quantity=quantities.getOrDefault(text(rule.get("productNo")),0);var roleResults=new ArrayList<Map<String,Object>>();
            for(String role:List.of("BUYER","DIRECT_INVITER")){
                Object spec=rule.get("BUYER".equals(role)?"buyerReward":"inviterReward");if(spec==null)continue;
                var matched=awards.stream().filter(a->rule.get("ruleId").equals(a.get("ruleId"))&&role.equals(a.get("beneficiaryRole"))).toList();
                var reasons=new ArrayList<String>(blockers);String status=blockers.isEmpty()?(matched.isEmpty()?"REJECTED":"MATCHED"):"UNKNOWN";
                if(blockers.isEmpty()&&matched.isEmpty()){
                    try{
                        reasons.addAll(audienceFailures(c,"buyerAudience",account,map(c.get("buyerAudience")),resolved,now));
                        if(quantity<number(rule.get("minBuyQty")))reasons.add(quantity==0?"PRODUCT_NOT_SELECTED":"MINIMUM_QUANTITY_NOT_MET");
                        if("ALL".equals(c.get("combinationMatch"))&&maps(c.get("rules")).stream().anyMatch(r->quantities.getOrDefault(text(r.get("productNo")),0)<number(r.get("minBuyQty"))))reasons.add("COMBINATION_INCOMPLETE");
                        if("DIRECT_INVITER".equals(role)){var buyer=evaluator.account(account,map(c.get("buyerAudience")),resolved,null);if(buyer.sponsorId()!=null&&buyer.sponsorId()!=account)reasons.addAll(audienceFailures(c,"inviterAudience",buyer.sponsorId(),map(c.get("inviterAudience")),resolved,now));}
                        if(reasons.isEmpty()){
                            Long beneficiary=account;
                            if("DIRECT_INVITER".equals(role))beneficiary=evaluator.account(account,map(c.get("buyerAudience")),resolved,null).sponsorId();
                            if(beneficiary!=null)for(var usage:db.list("SELECT * FROM nx_promotion_usage WHERE activity_id=? AND account_id=? AND beneficiary_role=?",activity,beneficiary,role)){
                                if(text(usage.get("rule_id")).isEmpty()&&number(usage.get("reserved_orders"))+number(usage.get("used_orders"))>=PromotionRules.limit(map(c.get("perPersonLimit")).get("BUYER".equals(role)?"buyer":"directInviter")))reasons.add("PERSON_ORDER_LIMIT");
                                if(rule.get("ruleId").equals(usage.get("rule_id"))&&number(usage.get("reserved_groups"))+number(usage.get("used_groups"))>=PromotionRules.limit(rule.get("maxGroupsPerPerson")))reasons.add("PERSON_RULE_LIMIT");
                            }
                            if(reasons.isEmpty())reasons.add(awards.size()>=PromotionRules.limit(c.get("maxRewardUnitsPerOrder"))?"ORDER_REWARD_LIMIT":"RULE_PRIORITY_OR_USAGE_LIMIT");
                        }
                    }catch(BizException|DataAccessException failure){status="UNKNOWN";reasons.add("ACCOUNT_FACTS_UNAVAILABLE");}
                }
                for(var award:matched){var unit=copy(award);unit.put("beneficiaryId",text(award.get("beneficiaryId")));unit.put("purchaseUnitFrom",number(award.get("unitSeq"))*number(rule.get("minBuyQty"))+1);unit.put("purchaseUnitTo",(number(award.get("unitSeq"))+1)*number(rule.get("minBuyQty")));units.add(unit);}
                roleResults.add(values("role",role,"status",status,"rewardUnits",status.equals("UNKNOWN")?null:matched.size(),"reasons",diagnostics(reasons)));
            }
            results.add(values("ruleId",rule.get("ruleId"),"productNo",rule.get("productNo"),"purchaseQuantity",quantity,"minimumQuantity",rule.get("minBuyQty"),"beneficiaries",roleResults));
        }
        var resources=resources(activity,c,items,awards,blockers.isEmpty());
        var quotas=quotas(activity,root,c,account,awards,blockers.isEmpty());
        boolean unknown=!blockers.isEmpty()||resources.stream().anyMatch(r->"UNKNOWN".equals(r.get("status")))||quotas.stream().anyMatch(r->"UNKNOWN".equals(r.get("status")));
        boolean shortage=resources.stream().anyMatch(r->"SHORTAGE".equals(r.get("status")))||quotas.stream().anyMatch(r->"SHORTAGE".equals(r.get("status")));
        if(shortage)blockers.add("RESOURCE_SHORTAGE");
        var result=values("activityId",activity,"version",root.get("draft_version")==null?number(root.get("active_version")):number(root.get("draft_version")),"configHash",hash(c),"asOf",now.toString(),"sample",values("kind",account==null?"UNSELECTED":"EXISTING_ACCOUNT","accountId",account==null?null:String.valueOf(account)),"pricing",price,"eligibility",unknown?"UNKNOWN":awards.isEmpty()?"INELIGIBLE":"ELIGIBLE","ruleResults",results,"rewardUnits",units,"resources",resources,"quotaImpact",quotas,"publicPreview",resolved.isEmpty()?null:publicPreview(c,resolved),"blockers",diagnostics(blockers),"completeness",unknown?"UNAVAILABLE":"COMPLETE","reserved",false,"giftCostUsdt",null,"acquisitionCostUsdt",null,"source",List.of("PromotionRules.evaluate","nx_promotion_usage","nx_promotion_budget","nx_product","nx_admin_device_sku","approved native policies"));
        validator.validate("AdminSimulation",result);return result;
    }
    private List<Map<String,Object>> quotas(String activity,Map<String,Object> root,Map<String,Object> c,Long account,List<Map<String,Object>> awards,boolean known){
        var result=new ArrayList<Map<String,Object>>();
        result.add(quota("ACTIVITY_ORDER",null,null,null,c.get("activityLimit"),number(root.get("reserved_orders"))+number(root.get("used_orders")),known?(awards.isEmpty()?0L:1L):null));
        Map<String,Long> beneficiaries=new LinkedHashMap<>();beneficiaries.put("BUYER",account);
        if("DIRECT_REFERRAL".equals(c.get("template"))){Long inviter=null;if(account!=null)try{var user=db.user(account,false);if(user.get("sponsor_user_id")!=null&&number(user.get("sponsor_user_id"))!=account)inviter=number(user.get("sponsor_user_id"));}catch(BizException|DataAccessException ignored){/* Unknown identity stays null. */}beneficiaries.put("DIRECT_INVITER",inviter);}
        for(var recipient:beneficiaries.entrySet()){
            String role=recipient.getKey();Long id=recipient.getValue();List<Map<String,Object>> usage=List.of();boolean readable=id!=null;
            if(readable)try{usage=db.list("SELECT * FROM nx_promotion_usage WHERE activity_id=? AND account_id=? AND beneficiary_role=?",activity,id,role);}catch(DataAccessException failure){readable=false;}
            long orderUse=0;var groups=new HashMap<String,Long>();for(var row:usage){if(text(row.get("rule_id")).isEmpty())orderUse=number(row.get("reserved_orders"))+number(row.get("used_orders"));else groups.put(text(row.get("rule_id")),number(row.get("reserved_groups"))+number(row.get("used_groups")));}
            long units=awards.stream().filter(a->role.equals(a.get("beneficiaryRole"))).count();
            result.add(quota("PERSON_ORDER",id,role,null,map(c.get("perPersonLimit")).get("BUYER".equals(role)?"buyer":"directInviter"),readable?orderUse:null,known&&readable?(units>0?1L:0L):null));
            for(var rule:maps(c.get("rules"))){if(rule.get("BUYER".equals(role)?"buyerReward":"inviterReward")==null)continue;String ruleId=text(rule.get("ruleId"));long requested=awards.stream().filter(a->role.equals(a.get("beneficiaryRole"))&&ruleId.equals(a.get("ruleId"))).count();result.add(quota("PERSON_RULE",id,role,ruleId,rule.get("maxGroupsPerPerson"),readable?groups.getOrDefault(ruleId,0L):null,known&&readable?requested:null));}
        }
        return result;
    }
    private Map<String,Object> quota(String scope,Long account,String role,String rule,Object configured,Long used,Long required){
        long limit=PromotionRules.limit(configured);Long remaining=used==null||limit==Long.MAX_VALUE?null:Math.max(0,limit-used);
        String status=used==null||required==null?"UNKNOWN":limit==Long.MAX_VALUE||used<=limit-required?"AVAILABLE":"SHORTAGE";
        return values("scope",scope,"beneficiaryId",account==null?null:String.valueOf(account),"beneficiaryRole",role,"ruleId",rule,"limit",limit==Long.MAX_VALUE?null:limit,"usedAndReserved",used,"required",required,"remaining",remaining,"status",status);
    }
    private List<Map<String,Object>> resources(String activity,Map<String,Object> c,List<PromotionRules.Selection> items,List<Map<String,Object>> awards,boolean known){
        var rows=new ArrayList<Map<String,Object>>();var demand=new TreeMap<String,BigDecimal>();
        for(var award:awards){var reward=map(award.get("reward"));String key=text(reward.get("type"))+":"+("DEVICE".equals(reward.get("type"))?text(reward.get("giftProductNo")):"");demand.merge(key,PromotionQuoteService.rewardAmount(reward),BigDecimal::add);}
        for(var budget:maps(c.get("budgets"))){String asset=text(budget.get("asset")),product=text(budget.get("productNo")),key=asset+":"+product;BigDecimal needed=demand.getOrDefault(key,BigDecimal.ZERO);Object available=null;String status="UNKNOWN";
            try{var current=db.one("SELECT * FROM nx_promotion_budget WHERE activity_id=? AND asset=? AND product_no=?",activity,asset,product);BigDecimal remaining=decimal(budget.get("total"));if(current!=null)remaining=remaining.subtract(decimal(current.get("reserved"))).subtract(decimal(current.get("committed"))).subtract(decimal(current.get("issued"))).add(decimal(current.get("reversed")));available=money(remaining.max(BigDecimal.ZERO));if(known)status=remaining.compareTo(needed)>=0?"AVAILABLE":"SHORTAGE";}catch(DataAccessException ignored){/* UNKNOWN is surfaced below, never interpreted as zero. */}
            rows.add(values("kind","BUDGET","asset",asset,"productNo",product.isEmpty()?null:product,"required",known?money(needed):null,"available",available,"status",status));
        }
        var stock=new TreeMap<String,BigDecimal>();for(var item:items)stock.merge(item.productNo(),BigDecimal.valueOf(item.quantity()),BigDecimal::add);for(var e:demand.entrySet())if(e.getKey().startsWith("DEVICE:"))stock.merge(e.getKey().substring(7),e.getValue(),BigDecimal::add);
        for(var e:stock.entrySet()){Object available=null;String status="UNKNOWN";
            try{var p=db.product(e.getKey(),false);if("UNLIMITED".equals(p.get("inventory_mode")))status="AVAILABLE";else if("FINITE".equals(p.get("inventory_mode"))){BigDecimal remaining=decimal(p.get("stock"));available=money(remaining);status=remaining.compareTo(e.getValue())>=0?"AVAILABLE":"SHORTAGE";}}catch(BizException|DataAccessException ignored){/* Preserve an explicit unknown resource. */}
            rows.add(values("kind","INVENTORY","asset","DEVICE","productNo",e.getKey(),"required",known?money(e.getValue()):null,"available",available,"status",known?status:"UNKNOWN"));
        }
        return rows;
    }
    private Map<String,Object> publicPreview(Map<String,Object> c,Map<String,Object> policies){
        var rules=new ArrayList<Map<String,Object>>();for(var rule:maps(c.get("rules"))){var visible=new LinkedHashMap<String,Object>();for(String key:List.of("ruleId","productNo","minBuyQty","repeatMode","maxGroups","maxGroupsPerPerson"))visible.put(key,rule.get(key));for(String role:List.of("buyerReward","inviterReward")){if(rule.get(role)==null){visible.put(role,null);continue;}var reward=copy(map(rule.get(role)));reward.put("disclosure",PromotionPublicService.disclosure(db,c,policies,reward));reward.remove("assetPolicy");reward.remove("deviceRightsProfile");visible.put(role,reward);}rules.add(visible);}
        return values("title",c.get("title"),"terms",c.get("terms"),"startsAt",c.get("startsAt"),"endsAt",c.get("endsAt"),"displayTimezone",c.get("displayTimezone"),"placement",c.get("placement"),"rewardRules",rules);
    }
    private List<Map<String,Object>> diagnostics(List<String> codes){return codes.stream().distinct().map(code->values("code",code,"message",message(code))).toList();}
    private Map<String,Object> reasonCount(String code,Long count){return values("code",code,"message",message(code),"count",count);}
    private Map<String,Object> message(String code){
        String[] text=switch(code){
            case "SAMPLE_ACCOUNT_REQUIRED"->new String[]{"请选择账户样本后核对资格与奖励","Select an account sample to evaluate eligibility and rewards.","Chọn tài khoản mẫu để kiểm tra điều kiện và phần thưởng."};
            case "PRODUCT_NOT_SELECTED"->new String[]{"样本未购买此商品","This product is absent from the sample order.","Đơn mẫu không có sản phẩm này."};
            case "MINIMUM_QUANTITY_NOT_MET"->new String[]{"购买数量未达到本组门槛","The quantity is below this rule's minimum.","Số lượng chưa đạt mức tối thiểu của quy tắc."};
            case "COMBINATION_INCOMPLETE"->new String[]{"尚未满足全部商品组合","The required product combination is incomplete.","Chưa đủ tổ hợp sản phẩm bắt buộc."};
            case "PERSON_ORDER_LIMIT"->new String[]{"该受益人的活动订单限额已占满","This beneficiary's activity order limit is exhausted.","Người nhận đã dùng hết hạn mức đơn của chương trình."};
            case "PERSON_RULE_LIMIT"->new String[]{"该受益人的本组奖励限额已占满","This beneficiary's rule limit is exhausted.","Người nhận đã dùng hết hạn mức của quy tắc."};
            case "ORDER_REWARD_LIMIT"->new String[]{"整单奖励项数已达到上限","The order reward-unit limit has been reached.","Đã đạt giới hạn phần thưởng của đơn."};
            case "RULE_PRIORITY_OR_USAGE_LIMIT"->new String[]{"优先规则或首台限定已分配本次奖励","Rule priority or the first eligible unit has already allocated this reward.","Ưu tiên quy tắc hoặc đơn vị đầu tiên đã phân bổ phần thưởng."};
            case "RESOURCE_SHORTAGE"->new String[]{"当前预算或库存不足","The current budget or inventory is insufficient.","Ngân sách hoặc tồn kho hiện không đủ."};
            case "DIRECT_INVITER_REQUIRED"->new String[]{"缺少有效直属邀请人","A valid direct inviter is required.","Cần người mời trực tiếp hợp lệ."};
            case "FIRST_PURCHASE_ALREADY_USED","AUDIENCE_NEVERPURCHASED"->new String[]{"已有购买记录，不符合首购口径","Purchase history does not qualify as a first purchase.","Lịch sử mua không đáp ứng điều kiện mua lần đầu."};
            case "VALID_PURCHASE_REQUIRED","AUDIENCE_PURCHASEHISTORY"->new String[]{"历史购买不满足所选人群条件","Purchase history does not match the selected audience.","Lịch sử mua không khớp nhóm đã chọn."};
            case "ACCOUNT_INACTIVE"->new String[]{"账户当前不可参与","The account is currently inactive.","Tài khoản hiện không hoạt động."};
            case "AUDIENCE_REGISTRATIONAGE"->new String[]{"注册时间不在所选天数范围","Registration age is outside the selected range.","Thời gian đăng ký ngoài khoảng đã chọn."};
            case "AUDIENCE_LASTVALIDPURCHASEAGE"->new String[]{"距上次有效购买的天数不符","The last valid purchase is outside the selected age range.","Lần mua hợp lệ gần nhất ngoài khoảng thời gian đã chọn."};
            case "AUDIENCE_RANKIDS"->new String[]{"当前等级不在所选范围","The account rank is outside the selected set.","Cấp tài khoản không thuộc nhóm đã chọn."};
            case "AUDIENCE_MARKETS"->new String[]{"账户市场不在所选范围","The account market is outside the selected set.","Thị trường tài khoản không thuộc nhóm đã chọn."};
            case "AUDIENCE_SPONSOR"->new String[]{"邀请关系不符合所选条件","The sponsor relationship does not match the selected condition.","Quan hệ người mời không đáp ứng điều kiện đã chọn."};
            case "AUDIENCE_DEVICEPRESENCE"->new String[]{"按已批准设备口径核对后不满足持有条件","Device holdings do not match the approved audience definition.","Thiết bị sở hữu không đáp ứng định nghĩa nhóm đã phê duyệt."};
            default->new String[]{"依赖资料暂不可判断，请补齐或稍后重试","Required facts are unavailable; no eligibility decision was made.","Chưa có dữ liệu cần thiết; chưa thể xác định điều kiện."};
        };
        return values("zh",text[0],"en",text[1],"vi",text[2]);
    }
}
