package ffdd.opsconsole.treasury.application;

import ffdd.opsconsole.treasury.facade.*;
import ffdd.opsconsole.treasury.mapper.SupportCommissionReadMapper;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.common.boundary.ApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.*;

/** Local review candidate: raw cash binding, never historical eligibility. */
@ApplicationService @RequiredArgsConstructor
public class SupportCommissionFundAdapter implements SupportCommissionCaptureFacade {
    private final SupportFundJournalFacade journal;
    private final SupportCommissionReadMapper mapper;
    private final SupportCommissionReadFacade sources;
    private final ObjectMapper json;
    private enum NonProduction implements Before { SANDBOX }
    private record Capture(SupportFundJournalFacade.Before binding,long customer,long eventId,String phase,Map<String,Object> wallet) implements Before {}
    @Transactional(propagation=Propagation.MANDATORY)
    public Before begin(long customer,long eventId,String phase){
        if(customer<=0||eventId<=0||!Set.of("RELEASE","REVERSE").contains(phase))throw new IllegalArgumentException("Commission capture identity required");
        var before=wallet(customer);
        int userSandbox=flag(before.get("userSandbox")),walletSandbox=flag(before.get("sandbox"));
        if(userSandbox==1||walletSandbox==1)return NonProduction.SANDBOX;
        return new Capture(journal.begin(List.of(customer)),customer,eventId,phase,before);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void recorded(Before before){
        if(before==NonProduction.SANDBOX)return;
        if(!(before instanceof Capture t))throw new IllegalArgumentException("Commission capture token required");
        var actual=sources.readFundCommissions(List.of(t.customer));
        var event=one(actual.events(),"id",t.eventId);var ledger=one(actual.ledgers(),"bizNo",ledgerNo(t.eventId,t.phase));
        var release=t.phase.equals("REVERSE")?one(actual.ledgers(),"bizNo",ledgerNo(t.eventId,"RELEASE")):null;
        String asset=(String)event.get("asset");if(!Set.of("USDT","NEX").contains(asset))throw new IllegalStateException("Commission currency required");
        var amount=amount(event.get(asset.equals("NEX")?"amountNex":"amountUsdt"));if(amount.signum()<=0)throw new IllegalStateException("Commission actual amount required");
        var account=new Account(t.customer,asset,"available");String key=eventId(t.eventId,t.phase);
        var events=List.of(t.phase.equals("RELEASE")?new Event(key,Action.EARNINGS,null,account,null,amount,null):new Event(key,Action.CLAWBACK,account,null,eventId(t.eventId,"RELEASE"),amount,null));
        var source=new LinkedHashMap<String,Object>();source.put("schemaVersion","SUPPORT_COMMISSION_V1");source.put("event",event);source.put("ledger",ledger);source.put("releaseLedger",release);
        var proof=new LinkedHashMap<String,Object>();proof.put("schemaVersion","SUPPORT_COMMISSION_V1");proof.put("cashBefore",t.wallet);proof.put("cashAfter",wallet(t.customer));proof.put("eventIds",List.of(key));
        var bound=journal.bind(t.binding,new SupportFundJournalFacade.ReceiptInput(key,"SUPPORT_COMMISSION",t.phase,"nx_commission_event",Long.toString(t.eventId),"PRODUCTION",encode(events),encode(source),encode(proof)));
        if(bound==null||!key.equals(bound.operationKey())||bound.journalLegs()!=1)throw new IllegalStateException("Commission bound receipt mismatch");
    }
    public static String eventId(long id,String phase){return "COMMISSION:"+id+":"+phase;}
    public static String ledgerNo(long id,String phase){return "F5-COMMISSION-"+id+"-"+phase;}
    private Map<String,Object> wallet(long customer){var rows=mapper.fundWallets(customer);if(rows==null||rows.size()!=1||rows.get(0)==null)throw new IllegalStateException("Commission wallet missing or ambiguous");return Collections.unmodifiableMap(new LinkedHashMap<>(rows.get(0)));}
    private static Map<String,Object> one(List<Map<String,Object>> rows,String field,Object expected){var matches=rows.stream().filter(r->expected instanceof Number?number(r.get(field))==number(expected):expected.equals(r.get(field))).toList();if(matches.size()!=1)throw new IllegalStateException("Commission original missing or ambiguous");return matches.get(0);}
    private static long number(Object v){if(!(v instanceof Number))throw new IllegalStateException("Commission numeric identity required");return new BigDecimal(v.toString()).longValueExact();}
    private static int flag(Object v){if(v instanceof Boolean b)return b?1:0;long n=number(v);if(n!=0&&n!=1)throw new IllegalStateException("Commission actual environment flag required");return (int)n;}
    private static BigDecimal amount(Object v){if(!(v instanceof Number))throw new IllegalStateException("Commission actual amount required");return new BigDecimal(v.toString()).setScale(6,java.math.RoundingMode.UNNECESSARY);}
    private String encode(Object v){try{return json.writeValueAsString(normalize(v));}catch(Exception e){throw new IllegalStateException("Commission source serialization failed",e);}}
    private static Object normalize(Object v){if(v instanceof LocalDateTime t)return t.toString();if(v instanceof java.sql.Timestamp t)return t.toLocalDateTime().toString();if(v instanceof Map<?,?> m){var r=new LinkedHashMap<String,Object>();m.forEach((k,x)->r.put(k.toString(),normalize(x)));return r;}if(v instanceof Collection<?> c)return c.stream().map(SupportCommissionFundAdapter::normalize).toList();return v;}
}
