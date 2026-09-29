package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.SupportRules;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportWorkbenchMapper;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.exception.BizException;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@ApplicationService
@RequiredArgsConstructor
public class SupportWorkbenchService {
    private static final long MAX_SAFE_INTEGER=9007199254740991L;
    private static final Set<String> FILTERS=Set.of("ALL","WINDOW_ACTIVE","ACTIVE","DORMANT","UNKNOWN",
            "DUE","WAITING_REPLY","FIRST_CONTACT","STOPPED","TODO");
    private final SupportWorkbenchMapper mapper;
    private final SupportBindingMapper bindings;
    private final SupportOwnershipService ownership;
    private final SupportActivityService activity;
    private final PlatformTransactionManager transactions;

    /**
     * Each response replaces BOTH cards and page. snapshotId is not a reusable snapshot token.
     * Facts use one RR database snapshot; evaluatedAt is the activity watermark/time-label anchor,
     * not an AS OF promise for mutable assignments/preferences. Newer committed contact facts remain visible.
     */
    public Map<String,Object> snapshot(Long agentId,String filter,String keyword,long page,int size,String from,String to) {
        validatePage(page,size);
        String selected=filter==null?"ALL":filter;
        if(!FILTERS.contains(selected)) throw new BizException(422,"SUPPORT_FILTER_INVALID");
        if(keyword!=null && keyword.length()>200) throw new BizException(422,"SUPPORT_KEYWORD_TOO_LONG");
        var coverage=activity.checkpoint();
        return transaction().execute(status -> {
            Long scopedAgent=scope(agentId);
            SupportRules rules=bindings.rules();
            var q=query(scopedAgent,null,rules,coverage);
            q.put("filter",selected); q.put("keyword",keyword==null || keyword.isBlank()?null:keyword.trim());
            q.put("offset",Math.multiplyExact(page-1,size)); q.put("limit",size);
            var totals=new LinkedHashMap<>(mapper.overview(q));
            totals.put("activeTotal",rules.activityWindowDays()==null || number(totals.get("unknownWindowCount"))>0
                    ?null:totals.get("knownActiveCount"));
            if(rules.dormantDays()==null) totals.put("dormantTotal",null);
            if(rules.maintenanceDays()==null) totals.put("dueTotal",null);
            long count=mapper.count(q);
            var pageData=new LinkedHashMap<String,Object>();
            pageData.put("total",count); pageData.put("pageNum",page); pageData.put("pageSize",size);
            pageData.put("filter",selected); pageData.put("available",available(selected,rules));
            pageData.put("records",mapper.customers(q).stream().map(row->customerView(row,rules)).toList());
            var result=metadata(scopedAgent,rules,coverage);
            result.put("overview",totals); result.put("customers",pageData);
            result.put("performance",performance(scopedAgent,coverage.observedThroughAt(),from,to));
            result.put("completeness",Map.of("unknownCount",number(totals.get("unknownCount")),
                    "unknownWindowCount",number(totals.get("unknownWindowCount")),
                    "coverageStartAt",utc(coverage.coverageStartAt()),
                    "observedThroughAt",utc(coverage.observedThroughAt()),"activitySource","INTERACTIVE_LOGIN",
                    "observationLagMillis",Math.max(0,Duration.between(coverage.observedThroughAt().toInstant(ZoneOffset.UTC),Instant.now()).toMillis())));
            return wire(result);
        });
    }

    public Map<String,Object> detail(Long customer) {
        requireSafeId(customer);
        var coverage=activity.checkpoint();
        return transaction().execute(status -> {
            ownership.requireRead(customer);
            Long scopedAgent=scope(null);
            var rules=bindings.rules();
            var q=query(scopedAgent,customer,rules,coverage);
            q.put("filter","ALL"); q.put("keyword",null); q.put("offset",0); q.put("limit",1);
            var rows=mapper.customers(q);
            if(rows.isEmpty()) throw new BizException(404,"SUPPORT_CUSTOMER_NOT_FOUND");
            var result=metadata(scopedAgent,rules,coverage);
            result.put("customer",customerView(rows.get(0),rules));
            return wire(result);
        });
    }

    private TransactionTemplate transaction() {
        var template=new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return template;
    }

    private Long scope(Long requested) {
        Long actor=ownership.actorId();
        if(requested!=null) requireSafeId(requested);
        if(ownership.supervisor(actor)) return requested;
        ownership.requireEligibleAgent();
        if(requested!=null && !requested.equals(actor)) throw new BizException(403,"SUPPORT_SCOPE_FORBIDDEN");
        return actor;
    }

    private Map<String,Object> query(Long agent,Long customer,SupportRules rules,SupportActivityService.Coverage coverage) {
        var q=new HashMap<String,Object>();
        q.put("agentId",agent); q.put("customerId",customer); q.put("evaluatedAt",coverage.observedThroughAt());
        q.put("coverageStartAt",coverage.coverageStartAt());
        q.put("dormantCutoff",cutoff(coverage.observedThroughAt(),rules.dormantDays()));
        q.put("windowCutoff",cutoff(coverage.observedThroughAt(),rules.activityWindowDays()));
        q.put("maintenanceDays",rules.maintenanceDays());
        return q;
    }

    private Map<String,Object> metadata(Long agent,SupportRules rules,SupportActivityService.Coverage coverage) {
        var result=new LinkedHashMap<String,Object>();
        result.put("snapshotId",UUID.randomUUID().toString()); result.put("evaluatedAt",utc(coverage.observedThroughAt()));
        result.put("rulesVersion",rules.version());
        var scope=new LinkedHashMap<String,Object>();
        scope.put("actorId",ownership.actorId()); scope.put("agentAdminId",agent); scope.put("mode",agent==null?"SUPERVISOR_ALL":"AGENT");
        result.put("scope",scope);
        var ruleView=new LinkedHashMap<String,Object>();
        ruleView.put("dormantDays",rules.dormantDays()); ruleView.put("maintenanceDays",rules.maintenanceDays());
        ruleView.put("activityWindowDays",rules.activityWindowDays());
        ruleView.put("dormantAvailable",rules.dormantDays()!=null); ruleView.put("dueAvailable",rules.maintenanceDays()!=null);
        ruleView.put("windowActiveAvailable",rules.activityWindowDays()!=null);
        result.put("rules",ruleView);
        return result;
    }

    private Map<String,Object> customerView(Map<String,Object> source,SupportRules rules) {
        var row=new LinkedHashMap<>(source);
        for(String name:List.of("enabled","waitingReply","firstContact","due")) row.put(name,number(row.get(name))!=0);
        if(rules.maintenanceDays()==null) row.put("due",null);
        for(String name:List.of("lastEffectiveAt","lastExecutionAt","lastSucceededAt","nextDueAt","openCycleId","stoppedReason",
                "pendingConversationNo","pendingThroughMessageId")) row.putIfAbsent(name,null);
        row.put("version",row.get("preferenceVersion"));
        return row;
    }

    private Map<String,Object> performance(Long agent,LocalDateTime evaluatedAt,String rawFrom,String rawTo) {
        var zone=DateTimeFormatConfig.BUSINESS_ZONE;
        Instant end=evaluatedAt.toInstant(ZoneOffset.UTC);
        Instant start=end.atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant();
        if((rawFrom==null)!=(rawTo==null)) throw new BizException(422,"SUPPORT_PERFORMANCE_RANGE_REQUIRED");
        if(rawFrom!=null) {
            try { start=Instant.parse(rawFrom); end=Instant.parse(rawTo); }
            catch(DateTimeException e) { throw new BizException(422,"SUPPORT_PERFORMANCE_RANGE_INVALID"); }
        }
        // Technical response bound, not a maintenance policy or historical retention limit.
        if((rawFrom!=null && !start.isBefore(end)) || start.isAfter(end) || ChronoUnit.DAYS.between(start,end)>3660 || start.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                || end.isAfter(Instant.parse("9999-12-31T00:00:00Z"))) throw new BizException(422,"SUPPORT_PERFORMANCE_RANGE_INVALID");
        var q=new HashMap<String,Object>();
        q.put("agentId",agent); q.put("from",LocalDateTime.ofInstant(start,ZoneOffset.UTC)); q.put("evaluatedAt",evaluatedAt);
        q.put("to",LocalDateTime.ofInstant(end,ZoneOffset.UTC));
        q.put("businessOffset",zone.getRules().getOffset(start).toString());
        var days=new TreeMap<String,Map<String,Object>>();
        for(LocalDate day=start.atZone(zone).toLocalDate();day.atStartOfDay(zone).toInstant().isBefore(end);day=day.plusDays(1)) {
            var row=new LinkedHashMap<String,Object>(); row.put("day",day.toString());
            row.put("executionCount",0L); row.put("successfulCycleCount",0L); days.put(day.toString(),row);
        }
        for(var row:mapper.executionDays(q)) days.get(row.get("day").toString()).put("executionCount",number(row.get("executionCount")));
        for(var row:mapper.successDays(q)) days.get(row.get("day").toString()).put("successfulCycleCount",number(row.get("successfulCycleCount")));
        return Map.of("from",start.toString(),"to",end.toString(),"timeZone",zone.toString(),"days",List.copyOf(days.values()),
                "executionCount",days.values().stream().mapToLong(d->number(d.get("executionCount"))).sum(),
                "successfulCycleCount",days.values().stream().mapToLong(d->number(d.get("successfulCycleCount"))).sum(),
                "successfulCustomerCount",mapper.successfulCustomers(q));
    }

    static boolean available(String filter,SupportRules rules) {
        return switch(filter) {
            case "WINDOW_ACTIVE" -> rules.activityWindowDays()!=null;
            case "ACTIVE","DORMANT" -> rules.dormantDays()!=null;
            case "DUE" -> rules.maintenanceDays()!=null;
            default -> true;
        };
    }

    static LocalDateTime cutoff(LocalDateTime now,Integer days) {
        if(days==null) return null;
        LocalDateTime result=now.minusDays(days);
        return result.getYear()<1000?LocalDateTime.of(1000,1,1,0,0):result;
    }

    public static void requireSafeId(Long id) {
        if(id==null || id<1 || id>MAX_SAFE_INTEGER) throw new BizException(422,"SUPPORT_ID_INVALID");
    }

    public static void validatePage(long page,int size) {
        if(page<1 || page>MAX_SAFE_INTEGER || size<1 || size>100 || page>Long.MAX_VALUE/size)
            throw new BizException(422,"SUPPORT_PAGE_INVALID");
    }

    private static long number(Object value) {
        if(value instanceof Boolean b) return b?1:0;
        return value==null?0:((Number)value).longValue();
    }

    private static String utc(LocalDateTime value) { return value.atOffset(ZoneOffset.UTC).toString(); }

    @SuppressWarnings("unchecked")
    public static Map<String,Object> wire(Map<String,Object> source) { return (Map<String,Object>)wireValue(source); }

    private static Object wireValue(Object value) {
        if(value instanceof Map<?,?> map) {
            var out=new LinkedHashMap<String,Object>(); map.forEach((k,v)->out.put(k.toString(),wireValue(v))); return out;
        }
        if(value instanceof List<?> list) return list.stream().map(SupportWorkbenchService::wireValue).toList();
        if(value instanceof Timestamp timestamp) return utc(timestamp.toLocalDateTime());
        if(value instanceof LocalDateTime date) return utc(date);
        if(value instanceof Number number) {
            var decimal=new java.math.BigDecimal(number.toString());
            if(decimal.abs().compareTo(java.math.BigDecimal.valueOf(MAX_SAFE_INTEGER))>0 || decimal.stripTrailingZeros().scale()>0)
                throw new BizException(422,"SUPPORT_NUMBER_OUT_OF_RANGE");
        }
        return value;
    }
}
