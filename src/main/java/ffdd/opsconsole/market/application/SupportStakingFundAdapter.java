package ffdd.opsconsole.market.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Event;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.market.facade.SupportStakingReadFacade;
import ffdd.opsconsole.market.mapper.SupportStakingSourceMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Capture only: existing command owns money, eligibility, position and ledger writes. */
@Service
@RequiredArgsConstructor
public class SupportStakingFundAdapter {
    private final SupportFundJournalFacade fund;
    private final SupportStakingSourceMapper mapper;
    private final SupportStakingReadFacade sources;
    private final ObjectMapper json;
    public interface Before {}
    private record Token(SupportFundJournalFacade.Before binding,long customer,Map<String,Object> wallet,SupportStakingReadFacade.Source prior) implements Before {}
    @Transactional(propagation=Propagation.MANDATORY)
    public Before begin(long customer,String no) {
        var binding=fund.begin(List.of(customer));
        var prior=no==null?List.<SupportStakingReadFacade.Source>of():sources.readByPositionNos(List.of(no));
        if(no!=null&&(prior.size()!=1||number(prior.get(0).position().get("customerId"))!=customer))throw new IllegalStateException("Staking original identity missing");
        return new Token(binding,customer,wallet(customer),prior.isEmpty()?null:prior.get(0));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void recorded(Before before,String no,String phase) {
        if(!(before instanceof Token token))throw new IllegalArgumentException("Staking capture token required");
        var rows=sources.readByPositionNos(List.of(no));if(rows.size()!=1)throw new IllegalStateException("Staking original missing or ambiguous");
        var source=rows.get(0);var p=source.position();
        if(number(p.get("customerId"))!=token.customer()||!no.equals(p.get("positionNo")))throw new IllegalStateException("Staking original identity mismatch");
        String expected=switch(phase){case "OPEN"->"ACTIVE";case "CLAIM"->"CLAIMED";case "EARLY"->"EARLY_WITHDRAWN";default->throw new IllegalArgumentException("Unsupported staking phase");};
        if(!expected.equals(p.get("status")))throw new IllegalStateException("Staking phase mismatch");
        var events=SupportStakingReadFacade.events(p,phase);var after=wallet(token.customer());
        BigDecimal delta=BigDecimal.ZERO;
        for(var event:events){if(event.from()!=null&&event.from().slot().equals("available"))delta=delta.subtract(event.amount());if(event.to()!=null&&event.to().slot().equals("available"))delta=delta.add(event.amount());}
        verifyWallet(token.wallet(),after,delta);
        String atKey=switch(phase){case "OPEN"->"createdAt";case "CLAIM"->"claimedAt";default->"earlyWithdrawnAt";};
        var snapshot=new LinkedHashMap<String,Object>();snapshot.put("schemaVersion","SUPPORT_STAKING_FUND_V1");snapshot.put("phase",phase);snapshot.put("position",p);snapshot.put("ledgers",source.ledgers());snapshot.put("positionBefore",token.prior()==null?null:token.prior().position());snapshot.put("ledgersBefore",token.prior()==null?List.of():token.prior().ledgers());snapshot.put("phaseAt",p.get(atKey));
        var proof=new LinkedHashMap<String,Object>();proof.put("schemaVersion","SUPPORT_STAKING_FUND_V1");proof.put("cashBefore",token.wallet());proof.put("cashAfter",after);proof.put("eventIds",events.stream().map(Event::id).toList());proof.put("originalOpenOperationKey",phase.equals("OPEN")?null:SupportStakingReadFacade.operationKey(no,"OPEN"));
        String key=SupportStakingReadFacade.operationKey(no,phase);
        var bound=fund.bind(token.binding(),new SupportFundJournalFacade.ReceiptInput(key,"SUPPORT_STAKING",phase,"nx_staking_position",no,"PRODUCTION",encode(events),encode(snapshot),encode(proof)));
        if(bound==null||!key.equals(bound.operationKey())||bound.journalLegs()!=1)throw new IllegalStateException("Staking binding mismatch");
    }
    private Map<String,Object> wallet(long customer){var rows=mapper.wallets(customer);if(rows==null||rows.size()!=1)throw new IllegalStateException("Staking wallet missing or ambiguous");return Collections.unmodifiableMap(new LinkedHashMap<>(rows.get(0)));}
    private static void verifyWallet(Map<String,Object> before,Map<String,Object> after,BigDecimal delta) {
        for(String key:List.of("id","customerId","version","revision"))number(before.get(key));
        if(number(after.get("id"))!=number(before.get("id"))||number(after.get("customerId"))!=number(before.get("customerId"))||!Objects.equals(before.get("incarnation"),after.get("incarnation"))||number(after.get("revision"))!=number(before.get("revision"))+1||number(after.get("version"))!=number(before.get("version"))+1)throw new IllegalStateException("Staking wallet identity or revision changed");
        for(String key:List.of("sandbox","deleted"))if(flag(before.get(key))!=0||flag(after.get(key))!=0)throw new IllegalStateException("Staking wallet flags invalid");
        for(String key:List.of("nexAvailable","riskHeld","pendingWithdrawal"))if(money(before.get(key)).compareTo(money(after.get(key)))!=0)throw new IllegalStateException("Staking other cash changed");
        if(money(after.get("usdtAvailable")).subtract(money(before.get("usdtAvailable"))).compareTo(delta)!=0)throw new IllegalStateException("Staking cash boundary mismatch");
    }
    private String encode(Object value){try{return json.writeValueAsString(normalize(value));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Staking source serialization failed",e);}}
    private static Object normalize(Object value){if(value instanceof LocalDateTime t)return t.toString();if(value instanceof java.sql.Timestamp t)return t.toLocalDateTime().toString();if(value instanceof Map<?,?> m){var r=new LinkedHashMap<String,Object>();m.forEach((k,v)->r.put(k.toString(),normalize(v)));return r;}if(value instanceof Collection<?> c)return c.stream().map(SupportStakingFundAdapter::normalize).toList();return value;}
    private static long number(Object v){if(!(v instanceof Number n))throw new IllegalStateException("Numeric staking identity required");return new BigDecimal(n.toString()).longValueExact();}
    private static BigDecimal money(Object v){if(!(v instanceof Number n))throw new IllegalStateException("Numeric staking amount required");return new BigDecimal(n.toString()).setScale(6,RoundingMode.UNNECESSARY);}
    private static int flag(Object v){if(v instanceof Boolean b)return b?1:0;long n=number(v);if(n!=0&&n!=1)throw new IllegalStateException("Binary staking flag required");return (int)n;}
}
