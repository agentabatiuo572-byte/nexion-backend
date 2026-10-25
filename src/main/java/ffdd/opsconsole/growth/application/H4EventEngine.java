package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static ffdd.opsconsole.growth.application.H4EventRuleService.*;

/** H4 decisions read canonical business facts; every award and budget share one transaction. */
@Service
@RequiredArgsConstructor
public class H4EventEngine {
    private final H4EventRuleRepository db;
    private final H4EventRuleService policies;
    private final H4EventCommerceService commerce;
    private final AppGrowthEngagementMapper events;
    private final EarningsReleaseService earnings;
    private final TreasuryLedgerPostingFacade ledger;
    private final TreasuryCoverageFacade coverage;
    private final AdminIdempotencyService idempotency;
    private final ffdd.opsconsole.shared.audit.AuditLogService audit;

    public boolean managed(String code){return policies.find(code,false)!=null;}
    public boolean enabled(String code){var row=policies.find(code,false);return row==null||number(row.get("enabled")).intValue()==1&&newParticipationOpen(row,false);}
    private boolean newParticipationOpen(Map<String,Object> rule,boolean lock){
        return !"evt-reinvest-bonus".equals(rule.get("event_code"))||commerce.reinvestParticipationOpen(lock);
    }
    private Map<String,Object> user(Long id,boolean lock){
        var user=db.row("SELECT *,TIMESTAMPDIFF(SECOND,created_at,NOW()) account_age_seconds FROM nx_user WHERE id=? AND status='ACTIVE' AND sandbox=0 AND is_deleted=0"+(lock?" FOR UPDATE":""),id);
        if(user==null)throw new BizException(403,"USER_NOT_FOUND_OR_INACTIVE");return user;
    }
    private String phase(Map<String,Object> rule){
        if(number(rule.get("enabled")).intValue()!=1)return "DISABLED";
        LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC),start=date(rule.get("starts_at")),end=date(rule.get("ends_at"));
        if(start==null||end==null)return "DISABLED";
        if(now.isBefore(start))return "NOT_STARTED";
        return now.isBefore(end)?"IN_PROGRESS":"ENDED";
    }
    private LocalDateTime effectiveStart(Map<String,Object> rule){
        LocalDateTime start=date(rule.get("starts_at")),activated=date(rule.get("activated_at"));
        return activated!=null&&activated.isAfter(start)?activated:start;
    }
    private LocalDateTime effectiveEnd(Map<String,Object> rule){
        LocalDateTime end=date(rule.get("ends_at")),disabled=date(rule.get("disabled_at"));
        return disabled!=null&&disabled.isBefore(end)?disabled:end;
    }
    public List<Map<String,Object>> project(Long id,List<Map<String,Object>> rows,String locale){
        List<Map<String,Object>> projected=new ArrayList<>();
        for(var row:rows){String code=text(row.get("eventCode"));var rule=policies.find(code,false);
            if(rule==null){projected.add(row);continue;}
            var value=project(id,row,rule,locale);
            if(!enabled(code)&&!Set.of("JOINED","CLAIMABLE","CLAIMED").contains(text(value.get("userStatus"))))continue;
            projected.add(value);
        }return projected;
    }
    private Map<String,Object> project(Long id,Map<String,Object> source,Map<String,Object> rule,String locale){
        var row=new LinkedHashMap<>(source);String code=text(rule.get("event_code")),phase=phase(rule);
        row.put("state",Set.of("ENDED","DISABLED").contains(phase)?"ended":"IN_PROGRESS".equals(phase)?"ongoing":"upcoming");
        var r=policies.rules(rule);var progress=progress(id,rule,r,source);
        row.put("progressValue",progress.getOrDefault("current",0));row.put("targetValue",progress.getOrDefault("target",1));
        boolean trackable=Set.of("referral","onboarding","regional").contains(text(rule.get("kind")))||"evt-reinvest-bonus".equals(code);
        row.put("trackable",trackable);
        if(!"wheel".equals(rule.get("kind"))){row.put("rewardType","COMPOSITE");row.put("rewardAmount",0);row.put("rewardName",rewardSummary(r,locale));}
        boolean claimed=db.row("SELECT 1 FROM nx_growth_event_claim WHERE event_code=? AND user_id=? AND claim_key='ONCE' AND status='CLAIMED'",code,id)!=null;
        boolean observed="holding".equals(rule.get("kind"))&&db.row("SELECT 1 FROM nx_growth_event_holding WHERE event_code=? AND user_id=?",code,id)!=null;
        Object previousStatus="CLAIMABLE".equals(source.get("userStatus"))?"JOINED":source.get("userStatus");
        row.put("userStatus",claimed?"CLAIMED":trackable&&Boolean.TRUE.equals(progress.get("eligible"))?"CLAIMABLE":observed?"JOINED":previousStatus);
        if(!newParticipationOpen(rule,false)){row.put("state","ended");row.put("href","");}
        row.put("startsAt",date(rule.get("starts_at"))==null?null:date(rule.get("starts_at")).toInstant(ZoneOffset.UTC).toString());
        row.put("endsAt",date(rule.get("ends_at"))==null?null:date(rule.get("ends_at")).toInstant(ZoneOffset.UTC).toString());
        return row;
    }
    public ApiResult<Map<String,Object>> detail(Long id,String code,String locale){
        user(id,false);var rule=policies.find(code,false);
        var source=events.eventState(id,locale==null?"en":locale).stream().filter(e->code.equals(e.get("eventCode"))).findFirst().orElse(null);
        if(source==null)throw new BizException(404,"EVENT_NOT_FOUND");
        if(rule==null)return ApiResult.ok(values("event",source,"rules",Map.of(),"enabled",true,"eligible",false,"eligibilityReason","IN_PROGRESS",
                "progressValue",source.get("progressValue"),"targetValue",source.get("targetValue"),"rewards",List.of(),"results",List.of(),"userStatus",source.get("userStatus")));
        var r=policies.rules(rule);var progress=progress(id,rule,r,source);var event=project(id,source,rule,locale);
        String phase=phase(rule),status=text(event.get("userStatus"));
        String reason="CLAIMED".equals(status)?"CLAIMED":!"IN_PROGRESS".equals(phase)?phase:Boolean.TRUE.equals(progress.get("eligible"))?"READY":"IN_PROGRESS";
        Map<String,Object> publicRules=new LinkedHashMap<>(r);
        List.of("budgetNex","poolUsdt","deviceBudget","ticketBudget").forEach(k->{if(!"poolUsdt".equals(k))publicRules.remove(k);});
        boolean participationOpen=newParticipationOpen(rule,false);
        boolean canParticipate="IN_PROGRESS".equals(phase)&&participationOpen;
        if("onboarding".equals(rule.get("kind")))canParticipate=canParticipate&&number(user(id,false).get("account_age_seconds")).longValue()<number(r.get("windowDays")).longValue()*86400;
        if("regional".equals(rule.get("kind")))canParticipate=canParticipate&&regionFor(user(id,false),r)!=null;
        if("evt-black-friday".equals(code))canParticipate=canParticipate&&Boolean.TRUE.equals(progress.get("eligible"));
        if("IN_PROGRESS".equals(phase)&&!canParticipate)reason="NOT_ELIGIBLE";
        if("evt-black-friday".equals(code)&&"IN_PROGRESS".equals(phase))reason=text(progress.getOrDefault("eligibilityReason",reason));
        if(!participationOpen)reason="DISABLED";
        if("CLAIMABLE".equals(status)){canParticipate=true;reason="READY";}
        return ApiResult.ok(values("event",event,"rules",publicRules,"enabled",enabled(code),"eligible",canParticipate,"eligibilityReason",reason,
                "progressValue",progress.getOrDefault("current",0),"targetValue",progress.getOrDefault("target",1),"rewards",rewards(r),"voucher",progress.get("voucher"),
                "results",resultView(id,rule,r),"userStatus",status,
                "claimKeys",progress.getOrDefault("claimKeys",List.of("ONCE")),"reminderSubscribed",
                db.row("SELECT 1 FROM nx_growth_event_reminder WHERE event_code=? AND user_id=?",code,id)!=null));
    }
    @Transactional(rollbackFor=Exception.class)
    public ApiResult<Map<String,Object>> remind(Long id,String code){
        if("evt-reinvest-bonus".equals(code)&&!commerce.reinvestParticipationOpen(true))throw new BizException(409,"EVENT_REMINDER_NOT_AVAILABLE");
        user(id,true);var rule=policies.find(code,true);
        if(rule==null||!"NOT_STARTED".equals(phase(rule)))throw new BizException(409,"EVENT_REMINDER_NOT_AVAILABLE");
        db.write("INSERT IGNORE INTO nx_growth_event_reminder(event_code,user_id) VALUES(?,?)",code,id);
        return ApiResult.ok(Map.of("eventId",code,"status","SUBSCRIBED"));
    }
    @Transactional(rollbackFor=Exception.class)
    public void notifyReminder(Long id,String code){
        if("evt-reinvest-bonus".equals(code)&&!commerce.reinvestParticipationOpen(true))return;
        user(id,true);var rule=policies.find(code,true);
        if(rule==null||!"IN_PROGRESS".equals(phase(rule)))return;
        var reminder=db.row("SELECT notified_at FROM nx_growth_event_reminder WHERE event_code=? AND user_id=? FOR UPDATE",code,id);
        if(reminder==null||reminder.get("notified_at")!=null)return;
        var account=user(id,false);String language=text(account.get("language"));String locale=language.startsWith("zh")?"zh":language.startsWith("vi")?"vi":"en";
        var event=events.eventState(id,locale).stream().filter(e->code.equals(e.get("eventCode"))).findFirst().orElseThrow();
        db.write("""
            INSERT IGNORE INTO nx_notification(biz_no,user_id,type,title,body,cta_label,cta_href,push_status,created_at,updated_at,is_deleted)
            VALUES(?,?,'EVENT',?,?,?,?, 'SKIPPED',NOW(),NOW(),0)
            ""","H4-REMINDER:"+code,id,text(event.get("title")),text(event.get("subtitle")),
                "zh".equals(locale)?"查看活动":"vi".equals(locale)?"Xem hoạt động":"View activity","/pages/events/detail?id="+code);
        db.write("UPDATE nx_growth_event_reminder SET notified_at=UTC_TIMESTAMP(6) WHERE event_code=? AND user_id=?",code,id);
    }
    private Map<String,Object> progress(Long id,Map<String,Object> rule,Map<String,Object> r,Map<String,Object> source){
        if("wheel".equals(rule.get("kind")))return values("current",number(source.get("progressValue")).signum()>0?1:0,"target",1,"eligible",false);
        return progress(id,rule,r);
    }
    private Map<String,Object> progress(Long id,Map<String,Object> rule,Map<String,Object> r){
        String code=text(rule.get("event_code"));
        if(!missing(code,r,date(rule.get("starts_at")),date(rule.get("ends_at"))).isEmpty())return values("current",0,"target",1,"eligible",false);
        if(rule.get("activated_at")==null||effectiveStart(rule).isAfter(LocalDateTime.now(ZoneOffset.UTC)))return values("current",0,"target",1,"eligible",false);
        if(Set.of("discount","seasonal").contains(text(rule.get("kind")))&&!"IN_PROGRESS".equals(phase(rule)))return values("current",0,"target",1,"eligible",false);
        if(Set.of("referral","onboarding").contains(text(rule.get("kind")))&&db.row("SELECT 1 FROM nx_growth_event_claim WHERE event_code=? AND user_id=? AND claim_key='ONCE' AND status='QUALIFIED'",code,id)!=null){
            Object target=r.getOrDefault("targetInvites",r.getOrDefault("minRank",1));
            return values("current",target,"target",target,"eligible",true,"claimKeys",List.of("ONCE"));
        }
        if(Set.of("referral","discount","seasonal").contains(text(rule.get("kind")))||"evt-reinvest-bonus".equals(code))
            return commerce.progress(id,code,r,effectiveStart(rule),effectiveEnd(rule));
        if("onboarding".equals(rule.get("kind"))){
            var account=user(id,false);int rank=number(text(account.get("v_rank")).replace("V","")).intValue();
            boolean inWindow=number(account.get("account_age_seconds")).longValue()<number(r.get("windowDays")).longValue()*86400;
            boolean earned=db.row("""
                SELECT 1 FROM nx_user_level_log l JOIN nx_user u ON u.id=l.user_id
                WHERE l.user_id=? AND l.level_type='VRANK' AND l.is_deleted=0
                  AND CAST(REPLACE(UPPER(l.to_code),'V','') AS UNSIGNED)>=?
                  AND l.created_at<CONVERT_TZ(?,'+00:00','+08:00')
                  AND GREATEST(l.created_at,CONVERT_TZ(?,'+00:00','+08:00'))<DATE_ADD(u.created_at,INTERVAL ? DAY)
                LIMIT 1
                """,id,number(r.get("minRank")),effectiveEnd(rule),effectiveStart(rule),number(r.get("windowDays")).intValue())!=null;
            return values("current",earned?number(r.get("minRank")):rank,"target",number(r.get("minRank")),"eligible",earned||"IN_PROGRESS".equals(phase(rule))&&inWindow&&rank>=number(r.get("minRank")).intValue());
        }
        if("holding".equals(rule.get("kind"))){
            var holding=db.row("SELECT eligible_since FROM nx_growth_event_holding WHERE event_code=? AND user_id=?",code,id);
            LocalDateTime since=holding==null?null:date(holding.get("eligible_since"));
            long days=since==null?0:Math.max(0,Duration.between(since,LocalDateTime.now(ZoneOffset.UTC)).toDays());
            return values("current",days,"target",number(r.get("holdDays")),"eligible",false);
        }
        return values("current",0,"target",1,"eligible",false);
    }
    private static List<Map<String,Object>> rewards(Map<String,Object> r){
        List<Map<String,Object>> result=new ArrayList<>();
        if(number(r.get("rewardNex")).signum()>0)result.add(values("type","NEX","amount",r.get("rewardNex")));
        if(number(r.get("streakSavers")).signum()>0)result.add(values("type","STREAK_SAVER","amount",0,"quantity",r.get("streakSavers")));
        if(r.containsKey("giftProductNo"))result.add(values("type","DEVICE","amount",0,"productNo",r.get("giftProductNo"),"quantity",r.get("giftQuantity")));
        if(number(r.get("poolUsdt")).signum()>0)result.add(values("type","USDT","amount",r.get("poolUsdt"),"pool",true));
        if(number(r.get("ticketsPerThreshold")).signum()>0)result.add(values("type","GENESIS_TICKET","amount",0,"quantity",r.get("ticketsPerThreshold")));
        if(number(r.get("extraGenesisTickets")).signum()>0)result.add(values("type","GENESIS_TICKET","amount",0,"quantity",r.get("extraGenesisTickets")));
        return result;
    }
    static String rewardSummary(Map<String,Object> r,String locale){
        boolean zh="zh".equals(locale),vi="vi".equals(locale);List<String> parts=new ArrayList<>();
        if(r.containsKey("voucherId")||r.containsKey("promotionActivityId"))parts.add(zh?"促销优惠":vi?"Ưu đãi khuyến mãi":"Promotional offer");
        if(number(r.get("multiplier")).signum()>0)parts.add((zh?"签到奖励":vi?"Thưởng điểm danh":"Check-in reward")+" × "+number(r.get("multiplier")).stripTrailingZeros().toPlainString());
        for(var reward:rewards(r)) {
            String type=text(reward.get("type"));String label=switch(type){case "NEX"->"UVT";case "USDT"->"USDT "+(zh?"总奖池":vi?"tổng quỹ thưởng":"total pool");case "DEVICE"->zh?"设备":vi?"thiết bị":"device";case "STREAK_SAVER"->zh?"补签卡":vi?"thẻ bù điểm danh":"streak saver";default->zh?"创世抽奖券":vi?"vé quay Genesis":"Genesis raffle tickets";};
            parts.add(number(reward.get("quantity")==null?reward.get("amount"):reward.get("quantity")).stripTrailingZeros().toPlainString()+" "+label);
        }
        return parts.isEmpty()?(zh?"查看活动规则":vi?"Xem thể lệ":"View event rules"):String.join(" + ",parts);
    }
    @Transactional(rollbackFor=Exception.class)
    public ApiResult<Map<String,Object>> join(Long id,String code){
        if("evt-reinvest-bonus".equals(code)&&!commerce.reinvestParticipationOpen(true))throw new BizException(409,"EVENT_NOT_OPEN");
        user(id,true);var rule=policies.find(code,true);if(rule==null||!"IN_PROGRESS".equals(phase(rule)))throw new BizException(409,"EVENT_NOT_OPEN");
        if("regional".equals(rule.get("kind"))){
            var account=user(id,false);String region=regionFor(account,policies.rules(rule));
            if(region==null)throw new BizException(409,"EVENT_REGION_NOT_ELIGIBLE");
            db.write("INSERT IGNORE INTO nx_growth_event_region(event_code,user_id,region_name,country_iso,joined_at) VALUES(?,?,?,?,UTC_TIMESTAMP(6))",code,id,region,country(account));
        }
        db.write("""
            INSERT IGNORE INTO nx_user_event_quest(user_id,quest_id,quest_code,progress_value,claim_status,reward_type,reward_amount,created_at,updated_at,is_deleted)
            SELECT ?,id,quest_code,0,'JOINED',reward_type,reward_amount,NOW(),NOW(),0 FROM nx_event_quest WHERE quest_code=? AND is_deleted=0
            """,id,code);
        return ApiResult.ok(Map.of("eventId",code,"status","JOINED"));
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    public ApiResult<Map<String,Object>> claim(Long id,String code,String key){
        return (ApiResult)idempotency.executeRetained("H4_EVENT:"+id+":"+code,key,code,ApiResult.class,()->award(id,code));
    }
    private ApiResult<Map<String,Object>> award(Long id,String code){
        user(id,true);var rule=policies.find(code,true);if(rule==null||rule.get("activated_at")==null)throw new BizException(409,"EVENT_NOT_OPEN");
        var r=policies.rules(rule);var progress=progress(id,rule,r);
        if(!Boolean.TRUE.equals(progress.get("eligible")))throw new BizException(409,"EVENT_NOT_CLAIMABLE");
        String claimKey="ONCE";
        if("evt-reinvest-bonus".equals(code))claimKey=text(((List<?>)progress.get("claimKeys")).get(0));
        var previous=db.row("SELECT status,reward_json FROM nx_growth_event_claim WHERE event_code=? AND user_id=? AND claim_key=?",code,id,claimKey);
        if(previous!=null&&"CLAIMED".equals(previous.get("status")))throw new BizException(409,"EVENT_ALREADY_CLAIMED");
        Map<String,Object> receipt;
        if("onboarding".equals(rule.get("kind"))){
            BigDecimal amount=number(r.get("rewardNex"));spend(rule,"nex",amount,number(r.get("budgetNex")));
            credit(id,code+":"+id,"NEX",amount);
            int savers=number(r.get("streakSavers")).intValueExact();
            db.write("INSERT IGNORE INTO nx_user_streak(user_id,current_streak,longest_streak,streak_savers,created_at,updated_at,is_deleted) VALUES(?,0,0,0,NOW(),NOW(),0)",id);
            db.write("UPDATE nx_user_streak SET streak_savers=streak_savers+?,updated_at=NOW() WHERE user_id=? AND is_deleted=0",savers,id);
            receipt=values("rewardType","NEX","rewardAmount",amount,"streakSavers",savers);
        }else{
            receipt="referral".equals(rule.get("kind"))?commerce.awardQualifiedReferral(id,code,r):commerce.awardReinvestTickets(id,code,r,effectiveStart(rule),effectiveEnd(rule),claimKey);
            boolean device="DEVICE".equals(receipt.get("rewardType"));
            spend(rule,device?"devices":"tickets",number(receipt.get("quantity")),number(r.get(device?"deviceBudget":"ticketBudget")));
        }
        db.write("INSERT INTO nx_growth_event_claim(event_code,user_id,claim_key,status,reward_json) VALUES(?,?,?,'CLAIMED',?) ON DUPLICATE KEY UPDATE status='CLAIMED',reward_json=VALUES(reward_json)",code,id,claimKey,policies.encode(receipt));
        awardAudit(id,code,claimKey,receipt);
        return ApiResult.ok(values("eventId",code,"rewardType",receipt.get("rewardType"),"rewardAmount",receipt.getOrDefault("rewardAmount",receipt.getOrDefault("quantity",0)),"receipt",receipt,"badgeCode",null));
    }
    public int checkInReward(Long id,LocalDate date,int base){
        var rule=policies.find("evt-weekend-double-nex",true);if(rule==null||!"IN_PROGRESS".equals(phase(rule)))return base;
        var r=policies.rules(rule);int day=ZonedDateTime.now(ZoneId.of(text(r.get("timeZone")))).getDayOfWeek().getValue();
        if(!((List<?>)r.get("weekdays")).stream().anyMatch(d->number(d).intValue()==day))return base;
        int total=number(r.get("multiplier")).multiply(BigDecimal.valueOf(base)).intValueExact();
        if(number(rule.get("spent_nex")).add(BigDecimal.valueOf(total-base)).compareTo(number(r.get("budgetNex")))>0)return base;
        spend(rule,"nex",BigDecimal.valueOf(total-base),number(r.get("budgetNex")));
        db.write("INSERT INTO nx_growth_event_claim(event_code,user_id,claim_key,status,reward_json) VALUES(?,?,?,'CLAIMED',?)",
                rule.get("event_code"),id,date.toString(),policies.encode(values("rewardType","NEX","rewardAmount",total-base)));
        return total;
    }
    @Transactional(rollbackFor=Exception.class)
    public void purchaseCompleted(Long id,String orderNo){automaticTickets(id,orderNo,true);}
    @Transactional(rollbackFor=Exception.class)
    public void reinvestCompleted(Long id,String positionNo){automaticTickets(id,positionNo,false);}
    private void automaticTickets(Long id,String factKey,boolean purchase){
        user(id,true);
        var candidates=db.rows("SELECT event_code FROM nx_growth_event_rule WHERE activated_at IS NOT NULL AND "+
                (purchase?"kind IN ('discount','seasonal')":"event_code='evt-reinvest-bonus'")+" ORDER BY event_code");
        for(var candidate:candidates){
            String code=text(candidate.get("event_code"));var rule=policies.find(code,true);var r=policies.rules(rule);
            if(purchase&&number(r.get("extraGenesisTickets")).signum()<=0)continue;
            if(db.row("SELECT 1 FROM nx_growth_event_claim WHERE event_code=? AND user_id=? AND claim_key=?",code,id,factKey)!=null)continue;
            LocalDateTime start=date(rule.get("starts_at")),activated=date(rule.get("activated_at")),end=date(rule.get("ends_at")),disabled=date(rule.get("disabled_at"));
            if(activated.isAfter(start))start=activated;if(disabled!=null&&disabled.isBefore(end))end=disabled;
            var receipt=purchase?commerce.awardPurchaseTickets(id,code,r,start,end,factKey):commerce.awardReinvestTickets(id,code,r,start,end,factKey);
            if(receipt==null||receipt.isEmpty())continue;
            spend(rule,"tickets",number(receipt.get("quantity")),number(r.get("ticketBudget")));
            db.write("INSERT INTO nx_growth_event_claim(event_code,user_id,claim_key,status,reward_json) VALUES(?,?,?,'CLAIMED',?)",code,id,factKey,policies.encode(receipt));
            awardAudit(id,code,factKey,receipt);
        }
    }
    private void spend(Map<String,Object> rule,String asset,BigDecimal amount,BigDecimal cap){
        if(!Set.of("nex","usdt","devices","tickets").contains(asset))throw new IllegalArgumentException();
        BigDecimal used=number(rule.get("spent_"+asset));
        if(amount.signum()<0||used.add(amount).compareTo(cap)>0)throw new BizException(409,"EVENT_BUDGET_EXHAUSTED");
        db.write("UPDATE nx_growth_event_rule SET spent_"+asset+"=spent_"+asset+"+? WHERE event_code=?",amount,rule.get("event_code"));
    }
    private void credit(Long id,String ref,String asset,BigDecimal amount){
        if(amount.signum()<=0)return;
        var snapshot=coverage.snapshot();
        if(snapshot==null||!snapshot.reliable()||snapshot.coverageRatio()==null||snapshot.redlinePct()==null
                ||snapshot.coverageRatio().compareTo(snapshot.redlinePct())<0)throw new BizException(409,"COVERAGE_REDLINE");
        earnings.creditReward(id,"EVENT_REWARD",ref,asset,amount,"H4:"+ref+":"+asset);
        ledger.postLedgerEntry("H4:"+ref+":"+asset,id,"EVENT_REWARD",asset,"IN",amount,"SUCCESS","Activity reward");
    }
    private String country(Map<String,Object> user){return switch(text(user.get("country_code")).replace("+","")){case "84"->"VN";case "86"->"CN";default->text(user.get("country_code")).matches("[A-Z]{2}")?text(user.get("country_code")):"";};}
    private String regionFor(Map<String,Object> user,Map<String,Object> rules){
        for(Object value:(List<?>)rules.getOrDefault("regions",List.of()))if(value instanceof Map<?,?> r&&r.get("countries") instanceof List<?> countries&&countries.contains(country(user)))return text(r.get("name"));
        return null;
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> regionMembers(Map<String,Object> rule,Map<String,Object> r){
        List<Map<String,Object>> members=new ArrayList<>();
        for(Object value:(List<?>)r.getOrDefault("regions",List.of())){
            var region=(Map<String,Object>)value;var countries=(List<?>)region.get("countries");
            List<Object> args=new ArrayList<>();args.add(rule.get("event_code"));args.add(region.get("name"));args.add(date(rule.get("starts_at")));args.add(date(rule.get("ends_at")));
            var rows=db.rows("""
                SELECT u.id userId,COALESCE(SUM(o.amount_usdt),0) volume
                FROM nx_user u JOIN nx_order o ON o.user_id=u.id AND o.is_deleted=0
                JOIN nx_growth_event_region er ON er.user_id=u.id AND er.event_code=? AND er.region_name=?
                WHERE u.status='ACTIVE' AND u.sandbox=0 AND u.is_deleted=0
                AND o.payment_status='PAID' AND o.order_status NOT IN ('REFUNDED','CHARGEBACK','CANCELLED')
                AND NOT EXISTS(SELECT 1 FROM nx_promotion_refund_hold h WHERE h.order_no=o.order_no AND h.status IN ('HELD','OUTCOME_UNKNOWN','EXECUTED'))
                AND o.paid_at>=DATE_ADD(er.joined_at,INTERVAL TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),NOW()) SECOND)
                AND o.paid_at>=DATE_ADD(?,INTERVAL TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),NOW()) SECOND)
                AND o.paid_at<DATE_ADD(?,INTERVAL TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),NOW()) SECOND)
                GROUP BY u.id HAVING SUM(o.amount_usdt)>0
                """,args.toArray());
            rows.forEach(row->{row.put("region",region.get("name"));members.add(row);});
        }return members;
    }
    private List<Map<String,Object>> leaderboard(Map<String,Object> rule,Map<String,Object> r){
        Map<String,BigDecimal> totals=new LinkedHashMap<>();
        for(var member:regionMembers(rule,r))totals.merge(text(member.get("region")),number(member.get("volume")),BigDecimal::add);
        List<Map<String,Object>> result=new ArrayList<>();
        totals.entrySet().stream().sorted(Map.Entry.<String,BigDecimal>comparingByValue().reversed())
                .forEach(e->result.add(values("region",e.getKey(),"rank",result.size()+1,"volume",e.getValue())));return result;
    }
    private List<Map<String,Object>> resultView(Long id,Map<String,Object> rule,Map<String,Object> r){
        if(rule.get("settled_at")!=null){
            if("regional".equals(rule.get("kind")))return db.rows("SELECT region_name region,1 AS `rank`,SUM(volume_usdt) volume,SUM(reward_usdt) rewardAmount FROM nx_growth_event_pool_result WHERE event_code=? AND status='PAID' GROUP BY region_name ORDER BY region_name",rule.get("event_code"));
            return db.rows("SELECT reward_usdt rewardAmount FROM nx_growth_event_pool_result WHERE event_code=? AND user_id=? AND status='PAID'",rule.get("event_code"),id);
        }
        return "regional".equals(rule.get("kind"))?leaderboard(rule,r):List.of();
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void settle(String code){
        // Account -> rule -> orders is shared with award writers. Lock every member before taking
        // the rule lock, then make allocation, all wallet postings and receipts one atomic batch.
        policies.lockHoldingWallets(code);
        for(var member:db.rows("SELECT user_id FROM nx_growth_event_region WHERE event_code=? UNION SELECT user_id FROM nx_growth_event_holding WHERE event_code=? ORDER BY user_id",code,code))
            db.row("SELECT id FROM nx_user WHERE id=? FOR UPDATE",member.get("user_id"));
        var rule=policies.find(code,true);if(rule==null||rule.get("settled_at")!=null)return;
        LocalDateTime end=date(rule.get("ends_at")),disabled=date(rule.get("disabled_at"));
        if(end==null||end.isAfter(LocalDateTime.now(ZoneOffset.UTC))||rule.get("activated_at")==null)return;
        if("holding".equals(rule.get("kind")))db.rows("SELECT user_id FROM nx_growth_event_holding WHERE event_code=? ORDER BY user_id FOR UPDATE",code);
        if("regional".equals(rule.get("kind"))) {
            db.rows("SELECT o.order_no FROM nx_order o JOIN nx_growth_event_region er ON er.user_id=o.user_id AND er.event_code=? ORDER BY o.order_no FOR UPDATE",code);
            db.rows("SELECT h.order_no FROM nx_promotion_refund_hold h JOIN nx_order o ON o.order_no=h.order_no JOIN nx_growth_event_region er ON er.user_id=o.user_id AND er.event_code=? ORDER BY h.order_no FOR UPDATE",code);
        }
        var r=policies.rules(rule);List<Map<String,Object>> members;String factsHash=null;
        if("holding".equals(rule.get("kind")))members=db.rows("""
            SELECT h.user_id userId,1 volume,'' region FROM nx_growth_event_holding h
            JOIN nx_user u ON u.id=h.user_id AND u.status='ACTIVE' AND u.sandbox=0 AND u.is_deleted=0
            WHERE h.event_code=? AND h.eligible_since IS NOT NULL AND TIMESTAMPDIFF(SECOND,h.eligible_since,?)>=?
            """,code,date(rule.get("ends_at")),number(r.get("holdDays")).longValue()*86400);
        else if("regional".equals(rule.get("kind"))){
            members=regionMembers(rule,r);factsHash=factsHash(members);Map<String,BigDecimal> totals=new HashMap<>();
            members.forEach(m->totals.merge(text(m.get("region")),number(m.get("volume")),BigDecimal::add));
            BigDecimal max=totals.values().stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            members=members.stream().filter(m->totals.get(text(m.get("region"))).compareTo(max)==0).toList();
        }else return;
        Map<Long,BigDecimal> allocations=allocatePool(members,number(r.get("poolUsdt")));
        for(var member:members){
            BigDecimal amount=allocations.get(number(member.get("userId")).longValue());
            db.write("INSERT IGNORE INTO nx_growth_event_pool_result(event_code,user_id,region_name,volume_usdt,reward_usdt) VALUES(?,?,?,?,?)",
                    code,member.get("userId"),member.get("region"),member.get("volume"),amount);
        }
        db.write("UPDATE nx_growth_event_rule SET settled_at=UTC_TIMESTAMP(6),settlement_hash=? WHERE event_code=?",factsHash,code);
        for(Long id:allocations.keySet().stream().sorted().toList())payPool(code,id);
    }
    /** Largest remainder at the wallet's six-decimal precision; ties resolve by stable user id. */
    public static Map<Long,BigDecimal> allocatePool(List<Map<String,Object>> members,BigDecimal pool){
        Map<Long,BigDecimal> result=new LinkedHashMap<>(),remainders=new HashMap<>();
        BigDecimal total=members.stream().map(m->number(m.get("volume"))).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(total.signum()==0)return result;
        BigDecimal distributed=BigDecimal.ZERO;
        for(var member:members){
            long id=number(member.get("userId")).longValue();BigDecimal numerator=pool.multiply(number(member.get("volume")));
            BigDecimal amount=numerator.divide(total,6,RoundingMode.DOWN);result.put(id,amount);distributed=distributed.add(amount);
            remainders.put(id,numerator.subtract(amount.multiply(total)));
        }
        int remaining=pool.subtract(distributed).movePointRight(6).intValueExact();
        List<Long> order=result.keySet().stream().sorted(Comparator.<Long,BigDecimal>comparing(remainders::get).reversed().thenComparingLong(Long::longValue)).toList();
        for(int i=0;i<remaining;i++)result.compute(order.get(i),(id,value)->value.add(new BigDecimal("0.000001")));
        return result;
    }
    private String factsHash(List<Map<String,Object>> rows){
        String text=rows.stream().sorted(Comparator.comparingLong(m->number(m.get("userId")).longValue()))
                .map(m->m.get("userId")+":"+m.get("region")+":"+number(m.get("volume")).stripTrailingZeros().toPlainString()).collect(java.util.stream.Collectors.joining("|"));
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    @Transactional(rollbackFor=Exception.class)
    public void payPool(String code,Long id){
        user(id,true);var rule=policies.find(code,true);if(rule==null||rule.get("settled_at")==null)return;
        var result=db.row("SELECT * FROM nx_growth_event_pool_result WHERE event_code=? AND user_id=? AND status='READY' FOR UPDATE",code,id);
        if(result==null)return;BigDecimal amount=number(result.get("reward_usdt"));
        spend(rule,"usdt",amount,number(policies.rules(rule).get("poolUsdt")));credit(id,code+":"+id,"USDT",amount);
        db.write("INSERT INTO nx_growth_event_claim(event_code,user_id,claim_key,status,reward_json) VALUES(?,?,'ONCE','CLAIMED',?)",code,id,policies.encode(values("rewardType","USDT","rewardAmount",amount)));
        db.write("UPDATE nx_growth_event_pool_result SET status='PAID' WHERE event_code=? AND user_id=?",code,id);
        awardAudit(id,code,"ONCE",values("rewardType","USDT","rewardAmount",amount));
    }
    private void awardAudit(Long id,String code,String key,Map<String,Object> receipt){
        audit.recordRequiredForTrustedActor(ffdd.opsconsole.shared.audit.AuditLogWriteRequest.builder()
                .action("H4_EVENT_CLAIMED").resourceType("USER_EVENT_QUEST").resourceId(code+":"+id+":"+key)
                .userId(id).actorId(id).actorType("USER").actorUsername("user:"+id).result("SUCCESS").riskLevel("HIGH")
                .detail(values("eventCode",code,"claimKey",key,"receipt",receipt)).build());
    }
}
