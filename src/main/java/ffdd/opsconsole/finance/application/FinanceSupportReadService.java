package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.finance.mapper.DepositOrderMapper;
import ffdd.opsconsole.finance.mapper.WithdrawalOrderMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

/** Caller authorizes the customer. This reader joins its snapshot and never writes money. */
@ApplicationService
@RequiredArgsConstructor
public class FinanceSupportReadService {
    private final DepositOrderMapper deposits;
    private final WithdrawalOrderMapper withdrawals;
    public Map<String,Object> totals(Long customer) {
        Map<String,Map<String,Object>> currencies=new TreeMap<>();
        var balances=deposits.supportBalances(customer);
        for(var balance:balances) currencies.put(text(balance,"currency"),row(text(balance,"currency"),balance.get("availableBalance")));
        for(var credit:deposits.supportCredits(customer)) {
            var row=currencies.computeIfAbsent(text(credit,"currency"),c->row(c,null));
            row.put("creditedDepositTotal",decimal(credit.get("creditedDepositTotal")));
        }
        for(var withdrawal:withdrawals.supportTotals(customer)) {
            var row=currencies.computeIfAbsent(text(withdrawal,"currency"),c->row(c,null));
            put(row,"successfulWithdrawalPrincipalTotal",withdrawal,"principal","principalAnomalies");
            put(row,"successfulWithdrawalFeeTotal",withdrawal,"actualFee","settlementAnomalies");
            put(row,"successfulWithdrawalNetTotal",withdrawal,"net","settlementAnomalies");
            put(row,"processingWithdrawalPrincipalTotal",withdrawal,"processing","processingAnomalies");
        }
        for(var anomaly:deposits.supportCreditAnomalies(customer)) {
            if(((Number)anomaly.get("anomalies")).longValue()>0) {
                var row=currencies.computeIfAbsent(text(anomaly,"currency"),c->row(c,null));
                row.put("creditedDepositTotal",null);statuses(row).put("creditedDepositTotal","UNKNOWN");
            }
        }
        return Map.of("byCurrency",List.copyOf(currencies.values()),"completeness","PARTIAL","balanceSourceStatus",balances.isEmpty()?"UNKNOWN":"READY",
            "refundSourceStatus","UNKNOWN","evaluatedAt",Instant.now().toString());
    }

    public Map<String,Object> flows(Long customer,long page,int size,String currency,String status,
            java.time.LocalDateTime from,java.time.LocalDateTime to) {
        var q=new HashMap<String,Object>();q.put("userId",customer);q.put("currency",currency);q.put("status",status);
        q.put("from",businessTime(from));q.put("to",businessTime(to));q.put("limit",size);q.put("offset",Math.multiplyExact(page-1,size));
        List<Map<String,Object>> records=new ArrayList<>();
        for(var source:deposits.supportFlows(q)) {
            var row=new LinkedHashMap<>(source);
            for(String field:List.of("principal","fee","net","paymentAmount")) row.put(field,decimal(row.get(field)));
            for(String field:List.of("createdAt","completedAt")) {
                Object value=row.get(field);java.time.LocalDateTime date=value instanceof java.sql.Timestamp stamp?stamp.toLocalDateTime():value instanceof java.time.LocalDateTime at?at:null;
                row.put(field,date==null?null:date.atZone(ffdd.opsconsole.shared.config.DateTimeFormatConfig.BUSINESS_ZONE).toInstant().toString());
            }
            records.add(row);
        }
        return Map.of("records",records,"total",deposits.supportFlowCount(q),"pageNum",page,"pageSize",size);
    }
    private static Map<String,Object> row(String currency,Object balance) {
        var row=new LinkedHashMap<String,Object>();row.put("currency",currency);var statuses=new LinkedHashMap<String,String>();
        for(String field:List.of("creditedDepositTotal","successfulWithdrawalPrincipalTotal","successfulWithdrawalFeeTotal",
                "successfulWithdrawalNetTotal","processingWithdrawalPrincipalTotal")) {row.put(field,"0");statuses.put(field,"READY");}
        row.put("depositRefundTotal",null);statuses.put("depositRefundTotal","UNKNOWN");
        row.put("balance",decimal(balance));row.put("availableBalance",decimal(balance));
        statuses.put("balance",balance==null?"UNKNOWN":"READY");statuses.put("availableBalance",balance==null?"UNKNOWN":"READY");
        row.put("fieldStatuses",statuses);return row;
    }
    private static void put(Map<String,Object> row,String name,Map<String,Object> source,String value,String anomalies) {
        boolean known=((Number)source.get(anomalies)).longValue()==0;
        row.put(name,known?decimal(source.get(value)):null);statuses(row).put(name,known?"READY":"UNKNOWN");
    }
    @SuppressWarnings("unchecked") private static Map<String,String> statuses(Map<String,Object> row){return (Map<String,String>)row.get("fieldStatuses");}
    private static String text(Map<String,Object> row,String key){return String.valueOf(row.get(key));}
    public static String decimal(Object value){return value==null?null:new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();}
    private static java.time.LocalDateTime businessTime(java.time.LocalDateTime utc){return utc==null?null:java.time.LocalDateTime.ofInstant(utc.toInstant(java.time.ZoneOffset.UTC),ffdd.opsconsole.shared.config.DateTimeFormatConfig.BUSINESS_ZONE);}
}
