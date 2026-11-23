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
    /** Bulk selection evidence: unknown stays unknown so the same customers keep being selected. */
    public Map<String,Object> totals(Long customer) { return totals(customer,false); }

    /**
     * Display projection for the USDT column of C1/support profiles (B74). The four reads below
     * either finish or throw, so a completed read with no credited deposit, no successful
     * withdrawal principal and no processing principal is a proven decimal zero, not an unknown
     * amount. Anomalies keep null/UNKNOWN, positive subtotals keep PARTIAL, and other currencies
     * keep the bulk shape.
     */
    public Map<String,Object> displayTotals(Long customer) { return totals(customer,true); }

    private Map<String,Object> totals(Long customer,boolean display) {
        Map<String,Map<String,Object>> currencies=new TreeMap<>();
        var balances=deposits.supportBalances(customer);
        for(var balance:balances) {
            var row=currencies.computeIfAbsent(text(balance,"currency"),c->row(c));
            row.put("balance",decimal(balance.get("availableBalance")));row.put("availableBalance",decimal(balance.get("availableBalance")));
            statuses(row).put("balance",balance.get("availableBalance")==null?"UNKNOWN":"READY");
            statuses(row).put("availableBalance",balance.get("availableBalance")==null?"UNKNOWN":"READY");
        }
        for(var credit:deposits.supportCredits(customer)) {
            var row=currencies.computeIfAbsent(text(credit,"currency"),c->row(c));
            observed(row,"creditedDepositTotal",credit.get("creditedDepositTotal"),display&&"USDT".equals(row.get("currency")));
        }
        for(var withdrawal:withdrawals.supportTotals(customer)) {
            var row=currencies.computeIfAbsent(text(withdrawal,"currency"),c->row(c));
            boolean zero=display&&"USDT".equals(row.get("currency"));
            // Only the three published totals are in scope; fee and net keep the bulk meaning.
            put(row,"successfulWithdrawalPrincipalTotal",withdrawal,"principal","principalAnomalies",zero);
            put(row,"successfulWithdrawalFeeTotal",withdrawal,"actualFee","settlementAnomalies",false);
            put(row,"successfulWithdrawalNetTotal",withdrawal,"net","settlementAnomalies",false);
            put(row,"processingWithdrawalPrincipalTotal",withdrawal,"processing","processingAnomalies",zero);
        }
        for(var anomaly:deposits.supportCreditAnomalies(customer)) {
            if(((Number)anomaly.get("anomalies")).longValue()>0) {
                var row=currencies.computeIfAbsent(text(anomaly,"currency"),c->row(c));
                row.put("creditedDepositTotal",null);statuses(row).put("creditedDepositTotal","UNKNOWN");
            }
        }
        if(display&&!currencies.containsKey("USDT")) {
            // No USDT source row: only the three published totals become a proven zero; the
            // refund policy column and the balances stay unknown like the previous synthesis.
            var row=row("USDT");row.put("depositRefundTotal",null);statuses(row).put("depositRefundTotal","UNKNOWN");
            currencies.put("USDT",row);
        }
        for(var row:currencies.values()) settleUnread(row,display);
        return Map.of("byCurrency",List.copyOf(currencies.values()),"completeness","PARTIAL","balanceSourceStatus",balances.isEmpty()?"UNKNOWN":"READY",
            "refundSourceStatus","NOT_APPLICABLE","evaluatedAt",Instant.now().toString());
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
    /** Published display totals: a completed read without an amount is a proven decimal zero. */
    private static final List<String> PROVEN_ZERO_FIELDS=List.of("creditedDepositTotal","successfulWithdrawalPrincipalTotal","processingWithdrawalPrincipalTotal");

    private static Map<String,Object> row(String currency) {
        var row=new LinkedHashMap<String,Object>();row.put("currency",currency);var statuses=new LinkedHashMap<String,String>();
        for(String field:List.of("creditedDepositTotal","successfulWithdrawalPrincipalTotal","successfulWithdrawalFeeTotal",
                "successfulWithdrawalNetTotal","processingWithdrawalPrincipalTotal")) {row.put(field,null);statuses.put(field,"UNREAD");}
        // Successful deposits cannot be refunded; preserve the old field as a policy zero.
        row.put("depositRefundTotal","0");statuses.put("depositRefundTotal","READY");
        row.put("balance",null);row.put("availableBalance",null);
        statuses.put("balance","UNKNOWN");statuses.put("availableBalance","UNKNOWN");
        row.put("fieldStatuses",statuses);return row;
    }
    private static void put(Map<String,Object> row,String name,Map<String,Object> source,String value,String anomalies,boolean zeroWhenRead) {
        boolean known=((Number)source.get(anomalies)).longValue()==0;
        if(known)observed(row,name,source.get(value),zeroWhenRead);
        else {row.put(name,null);statuses(row).put(name,"UNKNOWN");}
    }
    private static void observed(Map<String,Object> row,String name,Object value,boolean zeroWhenRead) {
        // Legacy SQL has reconciled subtotals, not customer lifetime/environment coverage.
        String amount=decimal(value);boolean positive=amount!=null && new BigDecimal(amount).signum()>0;
        row.put(name,positive?amount:(zeroWhenRead?"0":null));
        statuses(row).put(name,positive?"PARTIAL":(zeroWhenRead?"READY":"UNKNOWN"));
    }
    /** Fields no query reported on yet: the USDT display row proves a zero, everything else unknown. */
    private static void settleUnread(Map<String,Object> row,boolean display) {
        boolean zero=display&&"USDT".equals(row.get("currency"));
        for(String field:List.of("creditedDepositTotal","successfulWithdrawalPrincipalTotal","successfulWithdrawalFeeTotal",
                "successfulWithdrawalNetTotal","processingWithdrawalPrincipalTotal")) {
            if(!"UNREAD".equals(statuses(row).get(field)))continue;
            boolean proven=zero&&PROVEN_ZERO_FIELDS.contains(field);
            row.put(field,proven?"0":null);statuses(row).put(field,proven?"READY":"UNKNOWN");
        }
    }
    @SuppressWarnings("unchecked") private static Map<String,String> statuses(Map<String,Object> row){return (Map<String,String>)row.get("fieldStatuses");}
    private static String text(Map<String,Object> row,String key){return String.valueOf(row.get(key));}
    public static String decimal(Object value){return value==null?null:new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();}
    private static java.time.LocalDateTime businessTime(java.time.LocalDateTime utc){return utc==null?null:java.time.LocalDateTime.ofInstant(utc.toInstant(java.time.ZoneOffset.UTC),ffdd.opsconsole.shared.config.DateTimeFormatConfig.BUSINESS_ZONE);}
}
