package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.SupportRules;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportWorkbenchMapper;
import ffdd.opsconsole.shared.config.BusinessCalendar;
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
    private final SupportCustomerProfileService profiles;

    /**
     * Each response replaces BOTH cards and page. snapshotId is not a reusable snapshot token.
     * Facts use one RR database snapshot; evaluatedAt is the activity watermark/time-label anchor,
     * not an AS OF promise for mutable assignments/preferences. Newer committed contact facts remain visible.
     */
    public Map<String,Object> snapshot(Long agentId,String filter,String keyword,long page,int size,String from,String to) {
        return snapshot(null,null,agentId,filter,keyword,page,size,from,to);
    }

    public Map<String,Object> snapshot(ReadMode mode,Long groupId,Long agentId,String filter,String keyword,long page,int size,String from,String to) {
        validatePage(page,size);
        String selected=filter==null?"ALL":filter;
        if(!FILTERS.contains(selected)) throw new BizException(422,"SUPPORT_FILTER_INVALID");
        if(keyword!=null && keyword.length()>200) throw new BizException(422,"SUPPORT_KEYWORD_TOO_LONG");
        // Reject inaccessible scopes before the activity write; recheck in the response transaction.
        if(mode==null) ownership.defaultQueryScope(groupId,agentId);
        else ownership.queryScope(mode,groupId,agentId);
        var coverage=activity.checkpoint();
        return transaction().execute(status -> {
            ReadScope scopedAgent=mode==null ? ownership.defaultQueryScope(groupId,agentId) : ownership.queryScope(mode,groupId,agentId);
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
        ownership.customerQueryScope(customer);
        var coverage=activity.checkpoint();
        var read=transaction();
        read.setReadOnly(true);
        return read.execute(status -> {
            ReadScope scopedAgent=ownership.customerQueryScope(customer);
            var rules=bindings.rules();
            var q=query(scopedAgent,customer,rules,coverage);
            q.put("filter","ALL"); q.put("keyword",null); q.put("offset",0); q.put("limit",1);
            var rows=mapper.customers(q);
            if(rows.isEmpty()) throw new BizException(404,"SUPPORT_CUSTOMER_NOT_FOUND");
            var result=metadata(scopedAgent,rules,coverage);
            result.put("customer",customerView(rows.get(0),rules));
            result.put("profile",profiles.profile(customer));
            return wire(result);
        });
    }

    private TransactionTemplate transaction() {
        var template=new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return template;
    }

    static Map<String,Object> query(ReadScope scope,Long customer,SupportRules rules,SupportActivityService.Coverage coverage) {
        var q=new HashMap<String,Object>();
        q.put("scope",Objects.requireNonNull(scope)); q.put("customerId",customer); q.put("evaluatedAt",coverage.observedThroughAt());
        q.put("coverageStartAt",coverage.coverageStartAt());
        q.put("dormantCutoff",cutoff(coverage.observedThroughAt(),rules.dormantDays()));
        q.put("windowCutoff",cutoff(coverage.observedThroughAt(),rules.activityWindowDays()));
        q.put("maintenanceDays",rules.maintenanceDays());
        return q;
    }

    private Map<String,Object> metadata(ReadScope readScope,SupportRules rules,SupportActivityService.Coverage coverage) {
        var result=new LinkedHashMap<String,Object>();
        result.put("snapshotId",UUID.randomUUID().toString()); result.put("evaluatedAt",utc(coverage.observedThroughAt()));
        result.put("rulesVersion",rules.version());
        result.put("scope",scopeView(readScope));
        var ruleView=new LinkedHashMap<String,Object>();
        ruleView.put("dormantDays",rules.dormantDays()); ruleView.put("maintenanceDays",rules.maintenanceDays());
        ruleView.put("activityWindowDays",rules.activityWindowDays());
        ruleView.put("dormantAvailable",rules.dormantDays()!=null); ruleView.put("dueAvailable",rules.maintenanceDays()!=null);
        ruleView.put("windowActiveAvailable",rules.activityWindowDays()!=null);
        result.put("rules",ruleView);
        return result;
    }

    private Map<String,Object> scopeView(ReadScope readScope) {
        var scope=new LinkedHashMap<String,Object>();
        scope.put("actorId",readScope.actorId()); scope.put("agentAdminId",readScope.requestedAgentId());
        scope.put("groupId",readScope.requestedGroupId()); scope.put("mode",readScope.mode().name());
        scope.put("availableModes",ownership.availableReadModes().stream().map(Enum::name).toList());
        return scope;
    }

    /**
     * Newest-first business months with their exact UTC windows from the business calendar, so a month that
     * was settled before a time-zone change keeps the zone it was settled in (BUG-005).
     */
    public static List<Map<String,Object>> analyticsMonths(ffdd.opsconsole.shared.config.BusinessCalendar.Track calendar,
            java.time.Instant now,int count) {
        java.time.YearMonth current=calendar.month(now);
        var months=new java.util.ArrayList<Map<String,Object>>();
        for(int index=0;index<count;index++) {
            java.time.YearMonth month=current.minusMonths(index);
            var row=new LinkedHashMap<String,Object>();
            row.put("month",month.toString());
            row.put("from",calendar.startOf(month.atDay(1)).toString());
            row.put("to",calendar.startOf(month.plusMonths(1).atDay(1)).toString());
            months.add(row);
        }
        return months;
    }

    static Map<String,Object> customerView(Map<String,Object> source,SupportRules rules) {
        var row=new LinkedHashMap<>(source);
        var asset=row.remove("advisorAvatarAssetId");var avatarVersion=row.remove("advisorAvatarVersion");
        if(asset==null || asset.toString().isBlank()) {
            row.put("advisorAvatar",null);row.put("advisorAvatarRef",null);
        } else {
            var avatar=new LinkedHashMap<String,Object>();avatar.put("assetId",asset);avatar.put("version",avatarVersion);
            row.put("advisorAvatar",avatar);
            row.put("advisorAvatarRef","/api/admin/content/support-agents/"+row.get("agentAdminId")+"/avatar?customerId="+row.get("customerId"));
        }
        // Customers without an uploaded avatar get no reference, so the console never requests one (BUG-004).
        var customerAvatarKey=row.remove("customerAvatarKey");
        row.put("customerAvatarRef",ffdd.opsconsole.content.domain.SupportCustomerAvatar.ref(number(row.get("customerId")),
                customerAvatarKey==null?null:customerAvatarKey.toString()));
        for(String name:List.of("enabled","waitingReply","firstContact","due")) row.put(name,number(row.get(name))!=0);
        if(rules.maintenanceDays()==null) row.put("due",null);
        for(String name:List.of("lastEffectiveAt","lastExecutionAt","lastSucceededAt","nextDueAt","openCycleId","stoppedReason",
                "pendingConversationNo","pendingThroughMessageId")) row.putIfAbsent(name,null);
        row.put("version",row.get("preferenceVersion"));
        return row;
    }

    private record PerformanceDay(LocalDate date,Instant from,Instant to,ZoneId zone,ZoneOffset offset) {}

    private record PerformancePeriod(Instant from,Instant to,ZoneId zone,String calendarKey,List<PerformanceDay> days) {
        Map<String,Object> query(ReadScope scope,LocalDateTime evaluatedAt) {
            var q=new HashMap<String,Object>();
            q.put("scope",scope); q.put("evaluatedAt",evaluatedAt); q.put("calendarKey",calendarKey);
            bindPerformanceWindow(q,from,to);
            return q;
        }
    }

    private static void bindPerformanceWindow(Map<String,Object> query,Instant from,Instant to) {
        query.put("windowFrom",from.toString()); query.put("windowTo",to.toString());
        query.put("from",databaseMicrosecondCeiling(from)); query.put("to",databaseMicrosecondCeiling(to));
    }

    // DATETIME(6) facts satisfy >= from and < to exactly when compared against the ceiling of each bound.
    private static LocalDateTime databaseMicrosecondCeiling(Instant bound) {
        return LocalDateTime.ofInstant(bound.plusNanos(999).truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC);
    }

    private static PerformancePeriod performancePeriod(LocalDateTime evaluatedAt,String rawFrom,String rawTo) {
        var calendar=BusinessCalendar.legacy();
        Instant end=evaluatedAt.toInstant(ZoneOffset.UTC);
        Instant start=calendar.startOf(calendar.dateAt(end).withDayOfMonth(1));
        if((rawFrom==null)!=(rawTo==null)) throw new BizException(422,"SUPPORT_PERFORMANCE_RANGE_REQUIRED");
        if(rawFrom!=null) {
            try { start=Instant.parse(rawFrom); end=Instant.parse(rawTo); }
            catch(DateTimeException e) { throw new BizException(422,"SUPPORT_PERFORMANCE_RANGE_INVALID"); }
        }
        // Technical response bound, not a maintenance policy or historical retention limit.
        if((rawFrom!=null && !start.isBefore(end)) || start.isAfter(end) || ChronoUnit.DAYS.between(start,end)>3660 || start.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                || end.isAfter(Instant.parse("9999-12-31T00:00:00Z"))) throw new BizException(422,"SUPPORT_PERFORMANCE_RANGE_INVALID");
        ZoneId zone=calendar.zoneAt(start);
        var days=new ArrayList<PerformanceDay>();
        var calendarKey=new StringBuilder(zone.getId());
        for(LocalDate day=calendar.dateAt(start);;day=day.plusDays(1)) {
            ZoneId dayZone=calendar.versionOnDate(day).zone();
            Instant dayStart=day.atStartOfDay(dayZone).toInstant();
            if(!dayStart.isBefore(end)) break;
            Instant nextStart=calendar.startOf(day.plusDays(1));
            ZoneOffset offset=dayZone.getRules().getOffset(dayStart);
            var previous=days.isEmpty()?null:days.get(days.size()-1);
            if(previous==null || !dayZone.equals(previous.zone()) || !offset.equals(previous.offset()))
                calendarKey.append('|').append(day).append('/').append(dayZone).append(offset);
            days.add(new PerformanceDay(day,dayStart.isBefore(start)?start:dayStart,nextStart.isAfter(end)?end:nextStart,dayZone,offset));
        }
        return new PerformancePeriod(start,end,zone,calendarKey.toString(),days);
    }

    private Map<String,Object> performance(ReadScope scope,LocalDateTime evaluatedAt,String rawFrom,String rawTo) {
        var period=performancePeriod(evaluatedAt,rawFrom,rawTo);
        var q=period.query(scope,evaluatedAt);
        var days=new LinkedHashMap<String,Map<String,Object>>();
        for(var day:period.days()) {
            var row=new LinkedHashMap<String,Object>(); row.put("day",day.date().toString());
            row.put("executionCount",0L); row.put("successfulCycleCount",0L); days.put(day.date().toString(),row);
        }
        for(int first=0;first<period.days().size();) {
            var startDay=period.days().get(first);
            int next=first+1;
            while(next<period.days().size() && period.days().get(next).offset().equals(startDay.offset())) next++;
            var lastDay=period.days().get(next-1);
            var segment=new HashMap<>(q);
            bindPerformanceWindow(segment,startDay.from(),lastDay.to());
            segment.put("businessOffset",startDay.offset().equals(ZoneOffset.UTC)?"+00:00":startDay.offset().toString());
            segment.put("lastDay",lastDay.date().toString());
            for(var row:mapper.executionDays(segment)) { var day=days.get(row.get("day").toString()); if(day!=null) day.put("executionCount",number(row.get("executionCount"))); }
            for(var row:mapper.successDays(segment)) { var day=days.get(row.get("day").toString()); if(day!=null) day.put("successfulCycleCount",number(row.get("successfulCycleCount"))); }
            first=next;
        }
        var result=new LinkedHashMap<String,Object>(Map.of("from",period.from().toString(),"to",period.to().toString(),
                "timeZone",period.zone().toString(),"scopeBasis","CURRENT_AGENT_MEMBERSHIP","days",List.copyOf(days.values()),
                "executionCount",days.values().stream().mapToLong(d->number(d.get("executionCount"))).sum(),
                "successfulCycleCount",days.values().stream().mapToLong(d->number(d.get("successfulCycleCount"))).sum(),
                "successfulCustomerCount",mapper.successfulCustomers(q)));
        result.put("scopeVersion",mapper.performanceScopeVersion(q));
        return result;
    }

    public Map<String,Object> performanceDetails(ReadMode mode,Long groupId,Long agentId,String metric,String from,String to,
            String rawEvaluatedAt,String scopeVersion,long expectedTotal,String rawDay,long page,int size,String expectedQueryVersion) {
        validatePage(page,size);
        if(!"EXECUTION".equals(metric) && !"SUCCESSFUL_CYCLE".equals(metric)) throw new BizException(422,"SUPPORT_PERFORMANCE_METRIC_INVALID");
        if(expectedTotal<0 || expectedTotal>MAX_SAFE_INTEGER) throw new BizException(422,"SUPPORT_PERFORMANCE_TOTAL_INVALID");
        if(!scopeVersion.matches("[0-9a-f]{64}") || (expectedQueryVersion!=null && !expectedQueryVersion.matches("[0-9a-f]{64}")))
            throw new BizException(422,"SUPPORT_PERFORMANCE_VERSION_INVALID");
        if(page>1 && expectedQueryVersion==null) throw new BizException(422,"SUPPORT_PERFORMANCE_VERSION_REQUIRED");
        LocalDateTime evaluatedAt;
        try { evaluatedAt=LocalDateTime.ofInstant(Instant.parse(rawEvaluatedAt),ZoneOffset.UTC); }
        catch(DateTimeException e) { throw new BizException(422,"SUPPORT_PERFORMANCE_CUTOFF_INVALID"); }
        if(evaluatedAt.getYear()<1000 || evaluatedAt.getYear()>9999 || evaluatedAt.getNano()%1000!=0)
            throw new BizException(422,"SUPPORT_PERFORMANCE_CUTOFF_INVALID");
        var period=performancePeriod(evaluatedAt,from,to);
        var selectedDay=rawDay==null?null:period.days().stream().filter(d->d.date().toString().equals(rawDay)).findFirst()
                .orElseThrow(()->new BizException(422,"SUPPORT_PERFORMANCE_DAY_INVALID"));
        return transaction().execute(status -> {
            var scope=mode==null?ownership.defaultQueryScope(groupId,agentId):ownership.queryScope(mode,groupId,agentId);
            var q=period.query(scope,evaluatedAt);
            if(!scopeVersion.equals(mapper.performanceScopeVersion(q))) throw new BizException(409,"SUPPORT_PERFORMANCE_VERSION_CHANGED");
            if(selectedDay!=null) bindPerformanceWindow(q,selectedDay.from(),selectedDay.to());
            long offset=(page-1)*size;
            q.put("scopeVersion",scopeVersion); q.put("metric",metric); q.put("day",rawDay);
            q.put("limit",size); q.put("offset",offset);
            var result=new LinkedHashMap<>(mapper.performanceDetailCounts(q));
            long total=number(result.get("total")),visible=number(result.get("visibleCount"));
            if(total!=expectedTotal || (expectedQueryVersion!=null && !expectedQueryVersion.equals(result.get("queryVersion"))))
                throw new BizException(409,"SUPPORT_PERFORMANCE_VERSION_CHANGED");
            result.put("restrictedCount",total-visible);
            result.put("metric",metric); result.put("day",rawDay); result.put("scope",scopeView(scope));
            result.put("scopeVersion",scopeVersion); result.put("scopeBasis","CURRENT_AGENT_MEMBERSHIP");
            result.put("from",period.from().toString()); result.put("to",period.to().toString());
            result.put("windowFrom",q.get("windowFrom")); result.put("windowTo",q.get("windowTo"));
            result.put("evaluatedAt",utc(evaluatedAt)); result.put("timeZone",(selectedDay==null?period.zone():selectedDay.zone()).getId());
            result.put("pageNum",page); result.put("pageSize",size);
            result.put("records",offset>=visible?List.of():mapper.performanceDetails(q));
            return wire(result);
        });
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

    private static String utc(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC).toString(); }

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
