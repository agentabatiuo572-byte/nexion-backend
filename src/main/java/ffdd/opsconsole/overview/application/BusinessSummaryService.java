package ffdd.opsconsole.overview.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.overview.mapper.BusinessSummaryMapper;
import ffdd.opsconsole.overview.mapper.BusinessSummaryMapper.Metric;
import ffdd.opsconsole.overview.mapper.BusinessSummaryMapper.Trend;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.config.BusinessCalendar;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@RequiredArgsConstructor
public class BusinessSummaryService {
    public static final String READ = "hasAnyAuthority('finance_d1_read','finance_d2_read','device_e4_read',"
            + "'bi_l3_read','user_c1_read','bi_l1_read','overview_b0_sales_read')";
    private final BusinessSummaryMapper mapper;
    private final Clock clock;

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ApiResult<Map<String,Object>> summary(String period,String registrationGranularity,Authentication authentication) {
        if(!Set.of("today","week","month","all").contains(period))throw new BizException(400,"B36_PERIOD_INVALID");
        if(!Set.of("day","week","month").contains(registrationGranularity))throw new BizException(400,"B36_REGISTRATION_GRANULARITY_INVALID");
        Set<String> grants=authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        boolean deposits=grants.contains("finance_d1_read");
        boolean withdrawals=grants.contains("finance_d2_read");
        boolean salesDetail=grants.contains("bi_l3_read");
        boolean sales=salesDetail||grants.contains("device_e4_read")||grants.contains("overview_b0_sales_read");
        boolean users=grants.contains("user_c1_read");
        boolean active=grants.contains("bi_l1_read");
        if(!(deposits||withdrawals||sales||users||active))throw new BizException(403,"ADMIN_PERMISSION_DENIED");

        var state=mapper.state();
        if(state==null||state.asOf()==null)throw new BizException(503,"B36_ROLLUP_NOT_READY");
        var calendar=BusinessCalendar.legacy();
        Instant generatedAt=clock.instant();
        boolean all="all".equals(period);
        LocalDateTime asOf=all?state.asOf():LocalDateTime.ofInstant(generatedAt,BusinessCalendar.STORAGE_ZONE);
        LocalDateTime wallNow=calendar.calendarTime(asOf);
        LocalDate today=wallNow.toLocalDate();
        LocalDate start=switch(period) {
            case "today"->today;
            case "week"->today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month"->today.withDayOfMonth(1);
            default->state.statisticsStart();
        };
        LocalDate previousStart=all?null:switch(period) {
            case "today"->start.minusDays(1);
            case "week"->start.minusWeeks(1);
            default->start.minusMonths(1);
        };
        LocalDateTime previousEnd=null;
        if(!all) {
            LocalDateTime previousWall=previousStart.atStartOfDay().plus(Duration.between(start.atStartOfDay(),wallNow));
            if(previousWall.isAfter(start.atStartOfDay()))previousWall=start.atStartOfDay();
            previousEnd=LocalDateTime.ofInstant(previousWall.atZone(calendar.versionOnDate(previousWall.toLocalDate()).zone()).toInstant(),
                    BusinessCalendar.STORAGE_ZONE);
        }
        Map<String,BigDecimal> current=metrics(all?mapper.rolledUp(start,today.plusDays(1)):mapper.current(calendar.storageStart(start),asOf));
        Map<String,BigDecimal> previous=all?Map.of():metrics(mapper.current(calendar.storageStart(previousStart),previousEnd));
        Map<String,Object> blocks=new LinkedHashMap<>();
        if(deposits)blocks.put("deposits",moneyBlock(deposit(current),all?null:deposit(previous),depositBreakdown(current),all?null:depositBreakdown(previous)));
        if(withdrawals) {
            var block=moneyBlock(withdrawal(current),all?null:withdrawal(previous),withdrawalBreakdown(current),all?null:withdrawalBreakdown(previous));
            var stock=mapper.inTransit();
            block.put("inTransit",linked("count",stock.count(),"amountUsdt",stock.value(),"asOf",generatedAt));
            blocks.put("withdrawals",block);
        }
        if(deposits&&withdrawals)blocks.put("netDeposits",moneyBlock(deposit(current).subtract(withdrawal(current)),
                all?null:deposit(previous).subtract(withdrawal(previous)),null,null));
        if(sales) {
            boolean currentKnown=count(current,"salesRefundUnknown")==0;
            boolean previousKnown=count(previous,"salesRefundUnknown")==0;
            var block=moneyBlock(currentKnown?sales(current):null,all||!previousKnown?null:sales(previous),
                    salesDetail&&currentKnown?salesBreakdown(current):null,!all&&salesDetail&&previousKnown?salesBreakdown(previous):null);
            block.put("available",currentKnown);
            if(!currentKnown)block.put("reason","SALES_REFUND_FACT_UNAVAILABLE");
            if(!all&&!previousKnown)block.put("previousUnavailableReason","SALES_REFUND_FACT_UNAVAILABLE");
            blocks.put("sales",block);
        }
        if(users) {
            var rows=all?mapper.rolledUpRegistrations(start,today.plusDays(1)):mapper.registrations(calendar.storageStart(start),asOf);
            blocks.put("registrations",linked("count",count(current,"registered"),"previousCount",all?null:count(previous,"registered"),
                    "cumulativeCount",all?count(current,"registered"):mapper.registeredTotal(asOf),
                    "firstDepositCount",count(current,"firstDeposit"),"previousFirstDepositCount",all?null:count(previous,"firstDeposit"),
                    "firstPurchaseCount",count(current,"firstPurchase"),"previousFirstPurchaseCount",all?null:count(previous,"firstPurchase"),
                    "trendGranularity",registrationGranularity,"trend",trend(rows,start,today,registrationGranularity)));
        }
        if(active) {
            var block=linked("available",!all,"count",all?null:mapper.active(calendar.storageStart(start),asOf),
                    "previousCount",all?null:mapper.active(calendar.storageStart(previousStart),previousEnd));
            if(all)block.put("reason","ALL_TIME_ACTIVE_NOT_PROVIDED");
            if(grants.contains("device_e5_read")) {
                var devices=mapper.devices();
                block.put("devices",linked("online",devices.online(),"abnormal",devices.abnormal(),"asOf",generatedAt));
            }
            blocks.put("activeUsers",block);
        }
        return ApiResult.ok(linked("period",period,"timezone",calendar.zoneAt(asOf.atZone(BusinessCalendar.STORAGE_ZONE).toInstant()).getId(),
                "statisticsStart",state.statisticsStart(),"asOf",asOf.atZone(BusinessCalendar.STORAGE_ZONE).toInstant(),
                "generatedAt",generatedAt,"rollupAsOf",state.asOf().atZone(BusinessCalendar.STORAGE_ZONE).toInstant(),
                "range",linked("from",calendar.startOf(start),"toExclusive",asOf.atZone(BusinessCalendar.STORAGE_ZONE).toInstant()),
                "comparison",all?null:linked("from",calendar.startOf(previousStart),"toExclusive",previousEnd.atZone(BusinessCalendar.STORAGE_ZONE).toInstant()),
                "blocks",blocks));
    }

    private Map<String,BigDecimal> metrics(List<Metric> rows) {
        return rows.stream().collect(Collectors.toMap(Metric::metric,Metric::value));
    }

    private BigDecimal value(Map<String,BigDecimal> metrics,String key) { return metrics.getOrDefault(key,BigDecimal.ZERO); }
    private long count(Map<String,BigDecimal> metrics,String key) { return value(metrics,key).longValueExact(); }
    private BigDecimal deposit(Map<String,BigDecimal> metrics) {
        return metrics.entrySet().stream().filter(e->e.getKey().startsWith("deposit_")).map(Map.Entry::getValue).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
    private BigDecimal withdrawal(Map<String,BigDecimal> metrics) { return value(metrics,"withdrawalUsdt").add(value(metrics,"withdrawalVnd")); }
    private BigDecimal sales(Map<String,BigDecimal> metrics) { return value(metrics,"salesGross").subtract(value(metrics,"salesRefunds")); }

    private Map<String,Object> depositBreakdown(Map<String,BigDecimal> metrics) {
        return linked("bep20Usdt",value(metrics,"deposit_BEP20"),"erc20Usdt",value(metrics,"deposit_ERC20"),
                "trc20Usdt",value(metrics,"deposit_TRC20"),"bankUsdt",value(metrics,"deposit_BANK"),
                "externalDirectUsdt",value(metrics,"deposit_DIRECT"),"legacyCardUsdt",value(metrics,"deposit_CARD"),
                "otherUsdt",value(metrics,"deposit_OTHER"),"count",count(metrics,"depositCount"));
    }
    private Map<String,Object> withdrawalBreakdown(Map<String,BigDecimal> metrics) {
        return linked("usdt",value(metrics,"withdrawalUsdt"),"vndPrincipalUsdt",value(metrics,"withdrawalVnd"),
                "feesUsdt",value(metrics,"withdrawalFees"),"count",count(metrics,"withdrawalCount"));
    }
    private Map<String,Object> salesBreakdown(Map<String,BigDecimal> metrics) {
        long count=count(metrics,"salesCount");
        return linked("grossUsdt",value(metrics,"salesGross"),"refundsUsdt",value(metrics,"salesRefunds"),"orderCount",count,
                "averageOrderUsdt",count==0?null:sales(metrics).divide(BigDecimal.valueOf(count),6,RoundingMode.HALF_UP));
    }
    private Map<String,Object> moneyBlock(BigDecimal value,BigDecimal previous,Map<String,Object> breakdown,Map<String,Object> previousBreakdown) {
        var block=linked("amountUsdt",value,"previousAmountUsdt",previous);
        if(breakdown!=null)block.put("breakdown",breakdown);
        if(previousBreakdown!=null)block.put("previousBreakdown",previousBreakdown);
        return block;
    }
    private List<Map<String,Object>> trend(List<Trend> rows,LocalDate start,LocalDate last,String granularity) {
        Function<LocalDate,LocalDate> bucket=date->switch(granularity) {
            case "week"->date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month"->date.withDayOfMonth(1);
            default->date;
        };
        var totals=new TreeMap<LocalDate,BigDecimal>();
        for(LocalDate date=start;!date.isAfter(last);date=date.plusDays(1))totals.putIfAbsent(bucket.apply(date),BigDecimal.ZERO);
        for(var row:rows)totals.merge(bucket.apply(row.date()),row.value(),BigDecimal::add);
        var result=new ArrayList<Map<String,Object>>();
        totals.forEach((date,total)->result.add(linked("from",date,"count",total.longValueExact())));
        return result;
    }
    private static Map<String,Object> linked(Object... fields) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int index=0;index<fields.length;index+=2)result.put((String)fields[index],fields[index+1]);
        return result;
    }
}
