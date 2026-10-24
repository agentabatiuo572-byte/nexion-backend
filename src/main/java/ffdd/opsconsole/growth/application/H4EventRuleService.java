package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.dto.H4EventRuleRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One versioned policy per activity; first participation freezes its economic terms. */
@Service
@RequiredArgsConstructor
public class H4EventRuleService {
    private final H4EventRuleRepository db;
    private final ObjectMapper json;
    private final AuditLogService audit;
    private final H4EventCommerceService commerce;
    private final ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade coverage;
    private final ffdd.opsconsole.platform.application.A2RuntimePolicy a2;
    public static final Set<String> CODES=Set.of("evt-pro-upgrade-7d","evt-refer-5-get-pro",
            "evt-weekend-double-nex","evt-regional-pk","evt-spring-spin","evt-reinvest-bonus",
            "evt-onboarding-7d","evt-black-friday","evt-nex-holders-share","evt-anniversary-spin");

    public Map<String,Object> find(String code,boolean lock) {
        return db.row("SELECT * FROM nx_growth_event_rule WHERE event_code=?"+(lock?" FOR UPDATE":""),code);
    }
    public List<Map<String,Object>> overview() {
        return db.rows("SELECT * FROM nx_growth_event_rule ORDER BY event_code").stream().map(this::view).toList();
    }
    public void registerDraft(String code,String kind){
        db.write("INSERT IGNORE INTO nx_growth_event_rule(event_code,kind,rules_json,enabled) VALUES(?,?,JSON_OBJECT(),0)",code,kind);
    }
    public void stop(String code){
        var row=find(code,true);if(row==null)return;
        if(number(row.get("enabled")).intValue()==1)freezeReferral(row);
        db.write("UPDATE nx_growth_event_rule SET enabled=0,disabled_at=COALESCE(disabled_at,UTC_TIMESTAMP(6)),revision=revision+1 WHERE event_code=? AND enabled=1",code);
    }
    private void freezeReferral(Map<String,Object> row){
        if(!Set.of("referral","onboarding").contains(text(row.get("kind")))||row.get("activated_at")==null)return;
        var r=rules(row);LocalDateTime start=date(row.get("starts_at")),activated=date(row.get("activated_at")),end=date(row.get("ends_at")),now=LocalDateTime.now(ZoneOffset.UTC);
        if("onboarding".equals(row.get("kind"))){
            if(now.isBefore(start)||!now.isBefore(end))return;
            db.write("""
                INSERT IGNORE INTO nx_growth_event_claim(event_code,user_id,claim_key,status,reward_json)
                SELECT ?,id,'ONCE','QUALIFIED',JSON_OBJECT() FROM nx_user
                WHERE status='ACTIVE' AND sandbox=0 AND is_deleted=0
                  AND CAST(REPLACE(UPPER(v_rank),'V','') AS UNSIGNED)>=?
                  AND created_at>DATE_SUB(NOW(),INTERVAL ? DAY)
                """,row.get("event_code"),number(r.get("minRank")),number(r.get("windowDays")).intValue());
            return;
        }
        if(activated.isAfter(start))start=activated;if(now.isBefore(end))end=now;
        var zone=ffdd.opsconsole.shared.config.DateTimeFormatConfig.BUSINESS_ZONE;
        LocalDateTime storedStart=LocalDateTime.ofInstant(start.toInstant(ZoneOffset.UTC),zone),storedEnd=LocalDateTime.ofInstant(end.toInstant(ZoneOffset.UTC),zone);
        db.write("""
            INSERT IGNORE INTO nx_growth_event_claim(event_code,user_id,claim_key,status,reward_json)
            SELECT ?,qualified.sponsor_user_id,'ONCE','QUALIFIED',JSON_OBJECT() FROM (
              SELECT candidate.sponsor_user_id,candidate.id FROM nx_user candidate JOIN nx_user invited
                ON invited.sponsor_user_id=candidate.sponsor_user_id AND invited.id<>candidate.sponsor_user_id
                AND invited.status='ACTIVE' AND invited.sandbox=0 AND invited.is_deleted=0
                AND invited.created_at>=candidate.created_at AND invited.created_at<DATE_ADD(candidate.created_at,INTERVAL ? DAY)
                AND invited.created_at<?
              WHERE candidate.sponsor_user_id IS NOT NULL AND candidate.id<>candidate.sponsor_user_id
                AND candidate.status='ACTIVE' AND candidate.sandbox=0 AND candidate.is_deleted=0
                AND candidate.created_at>=? AND candidate.created_at<?
              GROUP BY candidate.sponsor_user_id,candidate.id HAVING COUNT(invited.id)>=?
            ) qualified
            """,row.get("event_code"),number(r.get("windowDays")).intValue(),storedEnd,storedStart,storedEnd,number(r.get("targetInvites")).intValue());
    }
    public Map<String,Object> view(Map<String,Object> row) {
        Map<String,Object> r=rules(row);
        var missing=missing(text(row.get("event_code")),r,date(row.get("starts_at")),date(row.get("ends_at")));
        missing=new ArrayList<>(missing);
        if(!"evt-anniversary-spin".equals(row.get("event_code"))&&date(row.get("ends_at"))!=null&&!date(row.get("ends_at")).isAfter(LocalDateTime.now(ZoneOffset.UTC)))missing.add("endsAt");
        missing.addAll(localizedMissing(text(row.get("event_code"))));
        if(missing.isEmpty())missing.addAll(commerce.readiness(text(row.get("event_code")),r,date(row.get("starts_at")),date(row.get("ends_at"))));
        return values("eventCode",row.get("event_code"),"kind",row.get("kind"),"revision",row.get("revision"),
                "enabled",number(row.get("enabled")).intValue()==1,"startsAt",iso(row.get("starts_at")),
                "endsAt",iso(row.get("ends_at")),"rules",r,"rulesLocked",row.get("activated_at")!=null,
                "readiness",Map.of("ready",missing.isEmpty(),"missing",missing));
    }
    @Transactional(rollbackFor=Exception.class)
    public ApiResult<Map<String,Object>> update(String code,H4EventRuleRequest request) {
        if(request==null||request.reason()==null||request.reason().trim().length()<a2.reasonMinChars())throw new BizException(422,"REASON_REQUIRED");
        var current=find(code,true);
        if(current==null)throw new BizException(404,"EVENT_RULE_NOT_FOUND");
        if(number(current.get("revision")).longValue()!=request.expectedRevision())throw new BizException(409,"EVENT_CONFIG_STALE");
        Map<String,Object> rules=request.rules()==null?Map.of():request.rules();
        LocalDateTime start=parseDate(request.startsAt()),end=parseDate(request.endsAt());
        boolean changed=!rules(current).equals(rules)||!Objects.equals(date(current.get("starts_at")),start)
                ||!Objects.equals(date(current.get("ends_at")),end);
        if(changed&&(current.get("activated_at")!=null||number(current.get("enabled")).intValue()==1))
            throw new BizException(409,"EVENT_RULES_ALREADY_IN_USE");
        var missing=missing(code,rules,start,end);
        if(request.enabled()&&!missing.isEmpty())return ApiResult.fail(422,"EVENT_RULE_INCOMPLETE",Map.of("missing",missing));
        if(request.enabled()) {
            var localizedMissing=localizedMissing(code);
            if(!localizedMissing.isEmpty())return ApiResult.fail(422,"EVENT_RULE_INCOMPLETE",Map.of("missing",localizedMissing));
            var domainMissing=commerce.readiness(code,rules,start,end);
            if(!domainMissing.isEmpty())return ApiResult.fail(422,"EVENT_RULE_INCOMPLETE",Map.of("missing",domainMissing));
            var snapshot=coverage.snapshot();
            if(!"evt-anniversary-spin".equals(code)&&(snapshot==null||!snapshot.reliable()||snapshot.coverageRatio()==null||snapshot.redlinePct()==null
                    ||snapshot.coverageRatio().compareTo(snapshot.redlinePct())<0))throw new BizException(409,"COVERAGE_REDLINE");
            if(current.get("activated_at")!=null&&number(current.get("enabled")).intValue()!=1)
                throw new BizException(409,"EVENT_RULE_REOPEN_NOT_ALLOWED");
        }
        if(request.enabled()&&!"evt-anniversary-spin".equals(code)&&end!=null&&!end.isAfter(LocalDateTime.now(ZoneOffset.UTC)))throw new BizException(422,"EVENT_WINDOW_ENDED");
        db.write("UPDATE nx_growth_event_rule SET rules_json=?,starts_at=?,ends_at=?,enabled=?,revision=revision+1,updated_at=UTC_TIMESTAMP(6) WHERE event_code=?",
                encode(rules),start,end,request.enabled(),code);
        if(request.enabled()) {
            db.write("UPDATE nx_growth_event_rule SET activated_at=COALESCE(activated_at,UTC_TIMESTAMP(6)) WHERE event_code=?",code);
            db.write("UPDATE nx_event_quest SET starts_at=?,ends_at=?,status=1,updated_at=NOW() WHERE quest_code=? AND is_deleted=0",start,end,code);
            if("evt-nex-holders-share".equals(code)&&number(current.get("enabled")).intValue()!=1)db.write("""
                INSERT INTO nx_growth_event_holding(event_code,user_id,eligible_since,updated_at)
                SELECT ?,w.user_id,GREATEST(?,UTC_TIMESTAMP(6)),UTC_TIMESTAMP(6)
                FROM nx_user_wallet w JOIN nx_user u ON u.id=w.user_id AND u.status='ACTIVE' AND u.sandbox=0 AND u.is_deleted=0
                WHERE w.nex_available>=? AND w.is_deleted=0
                ON DUPLICATE KEY UPDATE eligible_since=VALUES(eligible_since),updated_at=VALUES(updated_at)
                """,code,start,number(rules.get("minNex")));
        } else {
            if(number(current.get("enabled")).intValue()==1)freezeReferral(current);
            db.write("UPDATE nx_growth_event_rule SET disabled_at=COALESCE(disabled_at,UTC_TIMESTAMP(6)) WHERE event_code=? AND activated_at IS NOT NULL",code);
        }
        audit.recordRequired(AuditLogWriteRequest.builder().action("H4_EVENT_RULE_CHANGED").resourceType("GROWTH_EVENT")
                .resourceId(code).actorType("ADMIN").actorUsername(ffdd.opsconsole.shared.security.AdminActorResolver.resolve("system"))
                .result("SUCCESS").riskLevel("HIGH")
                .detail(values("reason",request.reason(),"enabled",request.enabled(),"revision",request.expectedRevision()+1)).build());
        return ApiResult.ok(view(find(code,false)));
    }
    @SuppressWarnings("unchecked")
    private List<String> localizedMissing(String code){
        Map<String,Object> root=Map.of();var value=db.row("SELECT config_value FROM nx_config_item WHERE config_key='growth.content.localized' AND status=1 AND is_deleted=0 LIMIT 1");
        if(value!=null)try{root=json.readValue(text(value.get("config_value")),new TypeReference<>(){});}catch(Exception ignored){ }
        var event=root.get("event") instanceof Map<?,?> all&&all.get(code) instanceof Map<?,?> entry?entry:Map.of();
        List<String> missing=new ArrayList<>();
        for(String lang:List.of("zh","vi"))for(String field:List.of("name","description","rewardName"))
            if(!(event.get(lang) instanceof Map<?,?> translated)||text(translated.get(field)).isBlank())missing.add("localized."+lang+"."+field);
        return missing;
    }
    public static List<String> missing(String code,Map<String,Object> r,LocalDateTime start,LocalDateTime end) {
        List<String> missing=new ArrayList<>();
        if(start==null)missing.add("startsAt");
        if(end==null||start!=null&&!end.isAfter(start))missing.add("endsAt");
        switch(code) {
            case "evt-refer-5-get-pro" -> {positive(r,missing,"targetInvites","windowDays","giftQuantity","deviceBudget");required(r,missing,"giftProductNo");}
            case "evt-onboarding-7d" -> positive(r,missing,"windowDays","minRank","rewardNex","streakSavers","budgetNex");
            case "evt-nex-holders-share" -> {positive(r,missing,"minNex","holdDays","poolUsdt");if(!"EQUAL".equals(r.get("allocation")))missing.add("allocation");}
            case "evt-regional-pk" -> {
                positive(r,missing,"poolUsdt");
                if(!"PAID_ORDER_USDT".equals(r.get("metric")))missing.add("metric");
                if(!"PROPORTIONAL_VOLUME".equals(r.get("allocation")))missing.add("allocation");
                if(!(r.get("regions") instanceof List<?> regions)||regions.size()<2)missing.add("regions");
                else {
                    Set<String> names=new HashSet<>(),countries=new HashSet<>();
                    for(Object value:regions) {
                        if(!(value instanceof Map<?,?> region)||text(region.get("name")).isBlank()
                                ||!names.add(text(region.get("name")))||!(region.get("countries") instanceof List<?> list)||list.isEmpty()) {missing.add("regions");break;}
                        for(Object country:list)if(!text(country).matches("[A-Z]{2}")||!countries.add(text(country)))missing.add("regions.countries");
                    }
                }
            }
            case "evt-weekend-double-nex" -> {
                positive(r,missing,"multiplier","budgetNex");
                if(!"CHECKIN_MULTIPLIER".equals(r.get("action"))||number(r.get("multiplier")).compareTo(BigDecimal.ONE)<=0
                        ||number(r.get("multiplier")).stripTrailingZeros().scale()>0||number(r.get("multiplier")).compareTo(BigDecimal.valueOf(100))>0)missing.add("multiplier");
                try{ZoneId.of(text(r.get("timeZone")));}catch(Exception ex){missing.add("timeZone");}
                if(!(r.get("weekdays") instanceof List<?> days)||days.isEmpty()||days.stream().anyMatch(d->number(d).intValue()<1||number(d).intValue()>7))missing.add("weekdays");
            }
            case "evt-reinvest-bonus" -> positive(r,missing,"thresholdUsdt","ticketsPerThreshold","ticketBudget");
            case "evt-pro-upgrade-7d","evt-black-friday" -> {required(r,missing,"promotionActivityId");if(number(r.get("extraGenesisTickets")).signum()>0)positive(r,missing,"ticketBudget");}
            case "evt-spring-spin" -> { }
            case "evt-anniversary-spin" -> {if(end==null||end.isAfter(LocalDateTime.now(ZoneOffset.UTC)))missing.add("endsAt");}
            default -> missing.add("eventCode");
        }
        for(String key:List.of("targetInvites","windowDays","giftQuantity","deviceBudget","minRank","streakSavers","holdDays","ticketsPerThreshold","ticketBudget","extraGenesisTickets"))
            if(r.containsKey(key)&&number(r.get(key)).stripTrailingZeros().scale()>0)missing.add(key);
        if("evt-reinvest-bonus".equals(code)&&!"REINVEST_TICKETS".equals(r.get("action")))missing.add("action");
        if(number(r.get("giftQuantity")).compareTo(BigDecimal.valueOf(100))>0)missing.add("giftQuantity");
        for(String key:List.of("ticketsPerThreshold","extraGenesisTickets"))
            if(number(r.get(key)).compareTo(BigDecimal.valueOf(1000000))>0)missing.add(key);
        for(String key:List.of("rewardNex","poolUsdt","budgetNex"))if(number(r.get(key)).scale()>6)missing.add(key);
        for(String key:List.of("targetInvites","windowDays","giftQuantity","deviceBudget","minRank","streakSavers","holdDays","ticketsPerThreshold","ticketBudget","extraGenesisTickets"))
            if(number(r.get(key)).compareTo(BigDecimal.valueOf(Integer.MAX_VALUE))>0||number(r.get(key)).signum()<0)missing.add(key);
        return missing.stream().distinct().toList();
    }
    private static void positive(Map<String,Object> r,List<String> missing,String... keys){for(String key:keys)if(number(r.get(key)).signum()<=0)missing.add(key);}
    private static void required(Map<String,Object> r,List<String> missing,String key){if(text(r.get(key)).isBlank())missing.add(key);}
    public Map<String,Object> rules(Map<String,Object> row) {
        try{return json.readValue(text(row.get("rules_json")),new TypeReference<>(){});}catch(Exception ex){throw new BizException(503,"EVENT_RULE_INVALID");}
    }
    public String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalArgumentException(ex);}}
    public static BigDecimal number(Object value){try{return value==null?BigDecimal.ZERO:new BigDecimal(value.toString());}catch(Exception ex){return BigDecimal.ZERO;}}
    public static String text(Object value){return value==null?"":value.toString();}
    public static LocalDateTime date(Object value){return value==null?null:value instanceof Timestamp t?t.toLocalDateTime():value instanceof LocalDateTime t?t:LocalDateTime.parse(text(value).replace(' ','T'));}
    public static LocalDateTime parseDate(String value){try{return value==null||value.isBlank()?null:LocalDateTime.ofInstant(Instant.parse(value),ZoneOffset.UTC);}catch(Exception ex){throw new BizException(422,"EVENT_DATE_INVALID");}}
    private static String iso(Object value){LocalDateTime date=date(value);return date==null?null:date.toInstant(ZoneOffset.UTC).toString();}
    public static Map<String,Object> values(Object... args){Map<String,Object> map=new LinkedHashMap<>();for(int i=0;i<args.length;i+=2)map.put(String.valueOf(args[i]),args[i+1]);return map;}
}
