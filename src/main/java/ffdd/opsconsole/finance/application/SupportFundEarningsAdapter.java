package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Central earnings receipts. The existing Binder owns physical transaction and raw coverage checks. */
@Service
@RequiredArgsConstructor
public class SupportFundEarningsAdapter {
    private final SupportFundJournalFacade journal;
    private final SupportFundJournalMapper raw;
    private final EarningsReleaseMapper earnings;
    private final ObjectMapper json;

    public record Capture(SupportFundJournalFacade.Before token,long customerId,Map<String,Object> wallet,
                          long previousOperationId) {
        public Capture { wallet=Collections.unmodifiableMap(new LinkedHashMap<>(wallet)); }
    }
    public Capture begin(long customerId) {
        var token=journal.begin(List.of(customerId));
        var wallets=raw.lockWallet(customerId);
        if(wallets==null || wallets.size()!=1)throw new IllegalStateException("Earnings wallet is ambiguous");
        Long predecessor=earnings.previousFundOperation(customerId);
        return new Capture(token,customerId,wallets.get(0),predecessor==null?0:predecessor);
    }
    public Map<String,Object> entry(String entryNo) {
        var row=earnings.fundEntry(entryNo);
        if(row==null)throw new IllegalStateException("Earnings source is missing");
        var snapshot=new LinkedHashMap<>(row);
        // MyBatis omits SQL NULLs; these three declared nullable columns still belong to the receipt.
        for(String field:List.of("clusterId","releasedAt","releaseSource"))snapshot.putIfAbsent(field,null);
        return Collections.unmodifiableMap(snapshot);
    }
    public void credit(Capture before,String entryNo) {
        var entry=entry(entryNo);String asset=(String)entry.get("asset");
        var action="withdrawable".equals(entry.get("bucket"))?Action.EARNINGS:Action.PROTECTED_EARNINGS;
        var event=new Event(eventId(entryNo),action,null,new Account(before.customerId(),asset,"available"),null,(BigDecimal)entry.get("amount"),null);
        bind(before,"CREDIT",entryNo,"nx_earnings_release_entry",Map.of("entry",entry),List.of(event));
    }
    public void release(Capture before,Map<String,Object> original,String authority,
                        EarningsReleaseService.TrustedAttestationProof proof) {
        String no=(String)original.get("entryNo");var source=new LinkedHashMap<String,Object>();
        source.put("beforeEntry",original);source.put("entry",entry(no));
        source.put("audit",earnings.fundReleaseAudit(no,"JANUS".equals(authority)?"K1_EARNINGS_ATTESTATION_RELEASED":"K1_EARNINGS_MANUALLY_RELEASED"));
        source.put("authority",authority);
        if(proof!=null)source.put("appliedProof",earnings.fundAppliedProof(proof.proofId().trim()));
        var event=new Event("EARNINGS_RELEASE:"+no,Action.RELEASE,
            new Account(before.customerId(),(String)original.get("asset"),"available"),null,eventId(no),null,null);
        bind(before,"RELEASE",no,"nx_earnings_release_entry",source,List.of(event));
    }
    public void recover(Capture before,Map<String,Object> original,String recoveryNo) {
        var recovery=earnings.fundRecovery(recoveryNo);
        if(recovery==null)throw new IllegalStateException("Earnings recovery source is missing");
        String no=(String)original.get("entryNo");var source=new LinkedHashMap<String,Object>();
        source.put("beforeEntry",original);source.put("entry",entry(no));source.put("recovery",recovery);
        BigDecimal amount=(BigDecimal)recovery.get("recovered");
        var events=amount.signum()==0?List.<Event>of():List.of(new Event("EARNINGS_RECOVER:"+recoveryNo,Action.RECOVER,
            new Account(before.customerId(),(String)original.get("asset"),"available"),null,eventId(no),amount,null));
        bind(before,"RECOVER",recoveryNo,"nx_earnings_source_recovery",source,events);
    }
    private void bind(Capture before,String phase,String key,String table,Map<String,Object> source,List<Event> events) {
        var wallets=raw.lockWallet(before.customerId());
        if(wallets==null || wallets.size()!=1)throw new IllegalStateException("Earnings wallet is ambiguous");
        var after=wallets.get(0);long start=number(before.wallet(),"revision"),end=number(after,"revision");
        var rows=raw.journal((String)before.wallet().get("incarnation"),start,end);
        var trace=new ArrayList<Map<String,Object>>();
        for(var row:rows)trace.add(Map.of("sourceId",table+":"+key,"eventIds",events.stream().map(Event::id).toList(),"journal",row));
        var proof=new LinkedHashMap<String,Object>();proof.put("cashBefore",before.wallet());proof.put("cashAfter",after);proof.put("cashTrace",trace);
        if(rows.isEmpty())proof.put("checkpoint",Map.of("wallet",after,"previousOperationId",before.previousOperationId()));
        journal.bind(before.token(),new SupportFundJournalFacade.ReceiptInput("EARNINGS:"+phase+":"+key,"SUPPORT_EARNINGS",phase,table,key,
            "PRODUCTION",write(events),write(source),write(proof)));
    }
    public static String eventId(String entryNo) {return "EARNINGS:"+entryNo;}
    private static long number(Map<String,Object> row,String field) {
        Object value=row.get(field);if(!(value instanceof Number n))throw new IllegalStateException("Invalid earnings wallet field");
        try{return new BigDecimal(n.toString()).longValueExact();}catch(ArithmeticException ex){throw new IllegalStateException("Invalid earnings wallet field",ex);}
    }
    private String write(Object value) {
        try{return json.writeValueAsString(normalize(value));}catch(JsonProcessingException ex){throw new IllegalStateException("Earnings source serialization failed",ex);}
    }
    private static Object normalize(Object value) {
        if(value instanceof java.sql.Timestamp at)return at.toLocalDateTime().toString();
        if(value instanceof java.time.LocalDateTime at)return at.toString();
        if(value instanceof Map<?,?> map){var result=new LinkedHashMap<String,Object>();map.forEach((k,v)->result.put(k.toString(),normalize(v)));return result;}
        if(value instanceof Collection<?> values)return values.stream().map(SupportFundEarningsAdapter::normalize).toList();return value;
    }
}
