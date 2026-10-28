package ffdd.opsconsole.device.application;

import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade;
import ffdd.opsconsole.device.mapper.SupportDeviceReadMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class SupportDeviceFundAdapter {
    private final SupportFundJournalFacade fund;
    private final SupportDeviceReadMapper mapper;
    private final SupportDeviceReadFacade sources;
    private final ObjectMapper json;
    public interface Before {}
    private static final class Token implements Before {final SupportFundJournalFacade.Before binding;final long customer;final Map<String,Object> before;Map<String,Object> afterUsdt;Token(SupportFundJournalFacade.Before binding,long customer,Map<String,Object> before){this.binding=binding;this.customer=customer;this.before=before;}}
    @Transactional(propagation=Propagation.MANDATORY)
    public Before begin(long customer){var binding=fund.begin(List.of(customer));return new Token(binding,customer,wallet(customer));}
    @Transactional(propagation=Propagation.MANDATORY)
    public void afterUsdt(Before before){var token=token(before);if(token.afterUsdt!=null)throw new IllegalStateException("Duplicate device cash stage");token.afterUsdt=wallet(token.customer);}
    @Transactional(propagation=Propagation.MANDATORY)
    public void recorded(Before before,String receiptNo){
        var token=token(before);if(token.afterUsdt==null)throw new IllegalStateException("Device cash stage missing");var actual=sources.readFundEarnings(List.of(token.customer));var receipts=actual.receipts().stream().filter(r->receiptNo.equals(r.get("receiptNo"))).toList();if(receipts.size()!=1)throw new IllegalStateException("Device receipt missing or ambiguous");var receipt=receipts.get(0);String taskNo=(String)receipt.get("taskNo");
        var events=new ArrayList<Event>();var usd=amount(receipt.get("rewardUsdt"));var nex=amount(receipt.get("rewardNex"));var snapshots=new ArrayList<Map<String,Object>>();snapshots.add(token.before);snapshots.add(token.afterUsdt);var after=wallet(token.customer);if(nex.signum()>0)snapshots.add(after);else if(!token.afterUsdt.equals(after))throw new IllegalStateException("Unexpected device cash mutation");
        if(usd.signum()>0)events.add(new Event(key(receiptNo)+":USDT",Action.EARNINGS,null,new Account(token.customer,"USDT","available"),null,usd,null));if(nex.signum()>0)events.add(new Event(key(receiptNo)+":NEX",Action.EARNINGS,null,new Account(token.customer,"UVT","available"),null,nex,null));
        check(token.before,token.afterUsdt,usd,BigDecimal.ZERO);if(nex.signum()>0)check(token.afterUsdt,after,BigDecimal.ZERO,nex);
        var earnings=actual.earningEvents().stream().filter(e->receiptNo.equals(e.get("receiptNo"))).toList();var ledgerNos=new HashSet<String>();ledgerNos.add(taskNo);
        // UVT daily ledger is identified by the actual UTC settlement day, never a new earning entry.
        String daily="CLOUD_SHARE_DAILY:"+receipt.get("deviceId")+":"+time(receipt.get("completedAt")).atZone(ffdd.opsconsole.shared.config.DateTimeFormatConfig.BUSINESS_ZONE).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDate();if(nex.signum()>0)ledgerNos.add(daily);
        var source=new LinkedHashMap<String,Object>();source.put("schemaVersion","SUPPORT_DEVICE_EARNING_V1");source.put("receipt",receipt);source.put("tasks",actual.tasks().stream().filter(t->Objects.equals(taskNo,t.get("taskNo"))).toList());source.put("ledgers",actual.ledgers().stream().filter(l->ledgerNos.contains(l.get("bizNo"))).toList());source.put("earningEvents",earnings);
        var proof=new LinkedHashMap<String,Object>();proof.put("schemaVersion","SUPPORT_DEVICE_EARNING_V1");proof.put("walletStages",snapshots);proof.put("eventIds",events.stream().map(Event::id).toList());
        var bound=fund.bind(token.binding,new SupportFundJournalFacade.ReceiptInput(key(receiptNo),"SUPPORT_DEVICE_EARNING","CREDIT","nx_compute_receipt",receiptNo,"PRODUCTION",encode(events),encode(source),encode(proof)));if(bound==null||!key(receiptNo).equals(bound.operationKey())||bound.journalLegs()!=snapshots.size()-1)throw new IllegalStateException("Device bound receipt mismatch");
    }
    public static String key(String no){try{return "DEVICE-EARNING:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(no.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static Token token(Before before){if(!(before instanceof Token t))throw new IllegalArgumentException("Device before token required");return t;}
    private Map<String,Object> wallet(long customer){var rows=mapper.fundWallets(customer);if(rows==null||rows.size()!=1)throw new IllegalStateException("Device wallet missing");return Collections.unmodifiableMap(new LinkedHashMap<>(rows.get(0)));}
    private static void check(Map<String,Object> before,Map<String,Object> after,BigDecimal usd,BigDecimal nex){for(String key:List.of("id","customerId","incarnation","cregisRiskHeld","pendingWithdraw","sandbox","deleted","createdAt"))if(!Objects.equals(before.get(key),after.get(key)))throw new IllegalStateException("Device wallet identity changed");if(num(after.get("revision"))!=num(before.get("revision"))+1||num(after.get("version"))!=num(before.get("version"))+1||amount(after.get("usdtAvailable")).subtract(amount(before.get("usdtAvailable"))).compareTo(usd)!=0||amount(after.get("nexAvailable")).subtract(amount(before.get("nexAvailable"))).compareTo(nex)!=0)throw new IllegalStateException("Device cash delta mismatch");}
    private static long num(Object v){if(!(v instanceof Number))throw new IllegalStateException("Device numeric identity required");return new BigDecimal(v.toString()).longValueExact();}
    private static BigDecimal amount(Object v){if(!(v instanceof Number))throw new IllegalStateException("Device amount required");return new BigDecimal(v.toString()).setScale(6,java.math.RoundingMode.UNNECESSARY);}
    private static LocalDateTime time(Object v){if(v instanceof java.sql.Timestamp t)return t.toLocalDateTime();if(v instanceof LocalDateTime t)return t;throw new IllegalStateException("Device source time required");}
    private String encode(Object v){try{return json.writeValueAsString(normalize(v));}catch(Exception e){throw new IllegalStateException("Device source serialization failed",e);}}
    private static Object normalize(Object v){if(v instanceof LocalDateTime t)return t.toString();if(v instanceof java.sql.Timestamp t)return t.toLocalDateTime().toString();if(v instanceof Map<?,?> m){var r=new LinkedHashMap<String,Object>();m.forEach((k,x)->r.put(k.toString(),normalize(x)));return r;}if(v instanceof Collection<?> c)return c.stream().map(SupportDeviceFundAdapter::normalize).toList();return v;}
}
