package ffdd.opsconsole.market.application;

import ffdd.opsconsole.finance.domain.SupportFundAllocation.Event;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Account;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Action;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.market.facade.SupportExchangeReadFacade;
import ffdd.opsconsole.market.mapper.SupportExchangeSourceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Binds existing exchange cash operations; it does not set prices or write cash. */
@Service
@RequiredArgsConstructor
public class SupportExchangeFundAdapter {
    private final SupportFundJournalFacade fund;
    private final SupportExchangeSourceMapper mapper;
    private final SupportExchangeReadFacade sources;
    private final ObjectMapper json;
    public interface Before {}
    private record Token(SupportFundJournalFacade.Before binding,long customer,Map<String,Object> wallet,SupportExchangeReadFacade.Source prior) implements Before {}

    @Transactional(propagation=Propagation.MANDATORY)
    public Before begin(long customer) {
        var binding=fund.begin(List.of(customer));
        return new Token(binding,customer,wallet(customer),null);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Before begin(long customer,String exchangeNo) {
        var token=(Token)begin(customer);var originals=sources.readByOrderNos(List.of(exchangeNo));
        if(originals.size()!=1||number(originals.get(0).order(),"customerId")!=customer||!"QUEUED".equals(originals.get(0).order().get("status")))throw new IllegalStateException("Exchange before source missing or ambiguous");
        return new Token(token.binding(),customer,token.wallet(),originals.get(0));
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void recorded(Before before,String exchangeNo,String phase,BigDecimal actualFeeUsdt) {
        if(!(before instanceof Token token))throw new IllegalArgumentException("Exchange before token required");
        var originals=sources.readByOrderNos(List.of(exchangeNo));
        if(originals.size()!=1)throw new IllegalStateException("Exchange original missing or ambiguous");
        var source=originals.get(0);var order=source.order();
        if(number(order,"customerId")!=token.customer() || !exchangeNo.equals(order.get("exchangeNo")))throw new IllegalStateException("Exchange customer identity mismatch");
        String status=String.valueOf(order.get("status"));
        if(!Set.of("IMMEDIATE","HOLD","COMPLETE","CANCEL").contains(phase) || !("HOLD".equals(phase)?"QUEUED":"CANCEL".equals(phase)?"CANCELLED":"COMPLETED").equals(status))throw new IllegalStateException("Exchange phase mismatch");
        BigDecimal gross=decimal(order.get("fromAmount")),target=decimal(order.get("toAmount")),fee=money(actualFeeUsdt);
        String from=String.valueOf(order.get("fromAsset")),to=String.valueOf(order.get("toAsset"));
        if(!Set.of("USDT","UVT").contains(from)||from.equals(to)||!Set.of("USDT","UVT").contains(to)||gross.signum()<=0||target.signum()<0||!(order.get("updatedAt") instanceof LocalDateTime))throw new IllegalStateException("Exchange original invalid");
        boolean reserved=source.ledgers().stream().anyMatch(l->(exchangeNo+"-HOLD").equals(l.get("bizNo"))&&"EXCHANGE_SWAP".equals(l.get("bizType"))&&"OUT".equals(l.get("direction"))&&"SUCCESS".equals(l.get("status"))&&flag(l.get("deleted"))==0);
        if(Set.of("IMMEDIATE","COMPLETE").contains(phase))verifyAmounts(source,gross,target,fee,from);
        var after=wallet(token.customer());
        boolean cash="HOLD".equals(phase)||!"CANCEL".equals(phase)||reserved;
        // Existing DOWN6 conversion can produce zero target: retain real cash provenance, leaving allocation UNKNOWN.
        boolean unrepresentable=target.signum()==0&&Set.of("IMMEDIATE","COMPLETE").contains(phase);
        var ordered=cash&&!unrepresentable?events(token.customer(),exchangeNo,"COMPLETE".equals(phase)&&!reserved?"IMMEDIATE":phase,from,gross,target,fee):List.<Event>of();
        BigDecimal usdt=BigDecimal.ZERO,nex=BigDecimal.ZERO;
        if("HOLD".equals(phase)||Set.of("IMMEDIATE","COMPLETE").contains(phase)&&!("COMPLETE".equals(phase)&&reserved)){if("USDT".equals(from))usdt=gross.negate();else nex=gross.negate();}
        if(Set.of("IMMEDIATE","COMPLETE").contains(phase)){if("USDT".equals(to))usdt=usdt.add(target);else nex=nex.add(target);}
        if("CANCEL".equals(phase)&&reserved){if("USDT".equals(from))usdt=gross;else nex=gross;}
        verifyWallet(token.wallet(),after,usdt,nex,cash?1:0);
        var snapshot=new LinkedHashMap<String,Object>();snapshot.put("schemaVersion","SUPPORT_EXCHANGE_FUND_V1");snapshot.put("phase",phase);snapshot.put("order",order);snapshot.put("ledgers",source.ledgers());snapshot.put("feeAllocations",source.feeAllocations());snapshot.put("feeCurrency","USDT");snapshot.put("feeAmount",fee);snapshot.put("phaseAt",order.get("updatedAt"));
        snapshot.put("orderBefore",token.prior()==null?null:token.prior().order());snapshot.put("ledgersBefore",token.prior()==null?List.of():token.prior().ledgers());snapshot.put("feeAllocationsBefore",token.prior()==null?List.of():token.prior().feeAllocations());
        var proof=new LinkedHashMap<String,Object>();proof.put("schemaVersion","SUPPORT_EXCHANGE_FUND_V1");proof.put("cashBefore",token.wallet());proof.put("cashAfter",after);proof.put("eventIds",ordered.stream().map(Event::id).toList());proof.put("sourceReservationPresent",reserved);proof.put("eventPhase","COMPLETE".equals(phase)&&!reserved?"IMMEDIATE":phase);proof.put("originalHoldOperationKey",Set.of("COMPLETE","CANCEL").contains(phase)?operationKey(exchangeNo,"HOLD"):null);proof.put("sourcePhaseAt",order.get("updatedAt"));
        String key=operationKey(exchangeNo,phase);
        var bound=fund.bind(token.binding(),new SupportFundJournalFacade.ReceiptInput(key,"SUPPORT_EXCHANGE",phase,"nx_exchange_order",exchangeNo,"PRODUCTION",encode(ordered),encode(snapshot),encode(proof)));
        if(bound==null||!key.equals(bound.operationKey())||bound.journalLegs()!=(cash?1:0))throw new IllegalStateException("Exchange bound receipt mismatch");
    }
    private void verifyAmounts(SupportExchangeReadFacade.Source source,BigDecimal gross,BigDecimal target,BigDecimal fee,String from) {
        if(fee.signum()<0)throw new IllegalStateException("Exchange fee invalid");
        var allocation=source.feeAllocations();
        if(fee.signum()==0){if(!allocation.isEmpty())throw new IllegalStateException("Unexpected exchange fee allocation");}
        else if(allocation.size()!=1||decimal(allocation.get(0).get("totalFeeUsdt")).compareTo(fee)!=0||decimal(allocation.get(0).get("burnPoolUsdt")).add(decimal(allocation.get(0).get("feeBufferUsdt"))).compareTo(fee)!=0)throw new IllegalStateException("Exchange fee source mismatch");
        Object rateValue=source.order().get("rate");if(!(rateValue instanceof Number))throw new IllegalStateException("Exchange rate invalid");
        BigDecimal rate=new BigDecimal(rateValue.toString()).setScale(8,RoundingMode.UNNECESSARY);if(rate.signum()<=0)throw new IllegalStateException("Exchange rate invalid");
        if(fee.signum()>0) {
            var row=allocation.get(0);
            if(flag(row.get("deleted"))!=0||!source.order().get("exchangeNo").equals(row.get("exchangeNo"))||!(row.get("priceUsdt") instanceof Number price)||new BigDecimal(price.toString()).compareTo(rate)!=0||decimal(row.get("burnPoolUsdt")).signum()<0||decimal(row.get("feeBufferUsdt")).signum()<0)throw new IllegalStateException("Exchange fee identity mismatch");
        }
        BigDecimal grossUsdt="USDT".equals(from)?gross:gross.multiply(rate).setScale(6,RoundingMode.HALF_UP);
        if(fee.compareTo(grossUsdt)>=0)throw new IllegalStateException("Exchange fee exceeds gross");
        BigDecimal expected="USDT".equals(from)?grossUsdt.subtract(fee).divide(rate,6,RoundingMode.DOWN):grossUsdt.subtract(fee);
        if(expected.compareTo(target)!=0)throw new IllegalStateException("Exchange net target mismatch");
    }
    private Map<String,Object> wallet(long customer){var rows=mapper.wallets(customer);if(rows==null||rows.size()!=1)throw new IllegalStateException("Exchange wallet missing or ambiguous");return Collections.unmodifiableMap(new LinkedHashMap<>(rows.get(0)));}
    static void verifyWallet(Map<String,Object> before,Map<String,Object> after,BigDecimal usdt,BigDecimal nex,int revisions){
        for(String key:List.of("id","customerId","incarnation","sandbox","deleted","riskHeld","pendingWithdrawal"))if(!Objects.equals(before.get(key),after.get(key)))throw new IllegalStateException("Exchange wallet identity changed");
        if(number(after,"revision")!=number(before,"revision")+revisions||number(after,"version")!=number(before,"version")+revisions||decimal(after.get("usdtAvailable")).subtract(decimal(before.get("usdtAvailable"))).compareTo(usdt)!=0||decimal(after.get("nexAvailable")).subtract(decimal(before.get("nexAvailable"))).compareTo(nex)!=0)throw new IllegalStateException("Exchange cash boundary mismatch");
    }
    static String operationKey(String exchangeNo,String phase){try{return "EXCHANGE:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((exchangeNo+"\n"+phase).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private String encode(Object value){try{return json.writeValueAsString(normalize(value));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Exchange source serialization failed",e);}}
    private static Object normalize(Object value){if(value instanceof LocalDateTime t)return t.toString();if(value instanceof java.sql.Timestamp t)return t.toLocalDateTime().toString();if(value instanceof Map<?,?> m){var r=new LinkedHashMap<String,Object>();m.forEach((k,v)->r.put(k.toString(),normalize(v)));return r;}if(value instanceof Collection<?> c)return c.stream().map(SupportExchangeFundAdapter::normalize).toList();return value;}
    private static long number(Map<String,Object> row,String key){var v=row.get(key);if(!(v instanceof Number n))throw new IllegalStateException("Exchange numeric identity required");return new BigDecimal(n.toString()).longValueExact();}
    private static BigDecimal decimal(Object value){if(!(value instanceof Number n))throw new IllegalStateException("Exchange numeric amount required");return money(new BigDecimal(n.toString()));}
    private static BigDecimal money(BigDecimal value){if(value==null)throw new IllegalArgumentException("Exchange amount required");return value.setScale(6,RoundingMode.UNNECESSARY);}
    private static int flag(Object value){if(value instanceof Boolean b)return b?1:0;return decimal(value).intValueExact();}
    static List<Event> events(long customer,String exchangeNo,String phase,String fromAsset,
                              BigDecimal fromAmount,BigDecimal toAmount,BigDecimal feeUsdt) {
        if(exchangeNo==null||!exchangeNo.matches("[^\\s:]{1,107}")||!Set.of("IMMEDIATE","HOLD","COMPLETE","CANCEL").contains(phase)||!Set.of("USDT","UVT").contains(fromAsset))throw new IllegalArgumentException("Invalid exchange event source");
        BigDecimal gross,target,fee;
        try{gross=money(fromAmount);target=money(toAmount);fee=money(feeUsdt);}catch(ArithmeticException invalid){throw new IllegalArgumentException("Invalid exchange precision",invalid);}
        if(gross.signum()<=0||target.signum()<0||fee.signum()<0||target.signum()==0&&!Set.of("HOLD","CANCEL").contains(phase))throw new IllegalArgumentException("Invalid exchange event amounts");
        var available=new Account(customer,fromAsset,"available");
        var held=new Account(customer,fromAsset,"held:exchange-"+exchangeNo);
        String id=operationKey(exchangeNo,phase);
        if("HOLD".equals(phase)||"CANCEL".equals(phase)) {
            if(fee.signum()!=0)throw new IllegalArgumentException("Reservation cannot charge fee");
            return List.of(new Event(id+":0",Action.MOVE,"HOLD".equals(phase)?available:held,"HOLD".equals(phase)?held:available,null,gross,null));
        }
        var source="COMPLETE".equals(phase)?held:available;
        var destination=new Account(customer,"USDT".equals(fromAsset)?"UVT":"USDT","available");
        if(fee.signum()==0)return List.of(new Event(id+":0",Action.EXCHANGE,source,destination,null,gross,target));
        if("USDT".equals(fromAsset)) {
            if(fee.compareTo(gross)>=0)throw new IllegalArgumentException("Fee exceeds exchange source");
            return List.of(new Event(id+":0",Action.FEE,source,null,null,fee,null),new Event(id+":1",Action.EXCHANGE,source,destination,null,gross.subtract(fee),target));
        }
        var converted=new Account(customer,"USDT","held:exchange-target-"+exchangeNo);
        return List.of(new Event(id+":0",Action.EXCHANGE,source,converted,null,gross,target.add(fee)),new Event(id+":1",Action.FEE,converted,null,null,fee,null),new Event(id+":2",Action.MOVE,converted,destination,null,target,null));
    }
}
