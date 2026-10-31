package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.finance.domain.SupportFundReplay.*;
import ffdd.opsconsole.market.facade.SupportExchangeReadFacade;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Strict FX originals/cash/group verification; the Reader owns signatures and complete raw closure. */
@Service
@RequiredArgsConstructor
public class SupportFundExchangeEvidence {
    private final SupportFundJournalBindingService codec;
    private final ObjectMapper json;
    private final SupportExchangeReadFacade exchange;
    public record Inputs(List<SupportExchangeReadFacade.Source> sources,List<Map<String,Object>> raw,
                         List<Map<String,Object>> operations,List<Map<String,Object>> parties,
                         List<Map<String,Object>> legs,Map<String,Integer> precisions) {}
    public record Verified(Stamp stamp,List<Point> points,String coveredPhase) {
        public Verified {points=List.copyOf(points);}
    }
    private static final String SCHEMA="SUPPORT_EXCHANGE_FUND_V1";
    private static final Set<String> SOURCE_KEYS=Set.of("schemaVersion","phase","order","ledgers","feeAllocations","feeCurrency","feeAmount","phaseAt","orderBefore","ledgersBefore","feeAllocationsBefore");
    private static final Set<String> PROOF_KEYS=Set.of("schemaVersion","cashBefore","cashAfter","eventIds","sourceReservationPresent","eventPhase","originalHoldOperationKey","sourcePhaseAt");
    private static final Set<String> WALLET_KEYS=Set.of("id","customerId","incarnation","revision","version","usdtAvailable","nexAvailable","riskHeld","pendingWithdrawal","sandbox","deleted");
    private static final Set<String> IMMUTABLE=Set.of("id","customerId","exchangeNo","fromAsset","toAsset","fromAmount","createdAt","deleted","userSandbox","userDeleted");
    public Verified verify(Map<String,Object> operation,JsonNode originalSource,JsonNode originalProof,List<Event> events,Inputs input) {
        JsonNode source=ffdd.opsconsole.common.domain.LegacyUvtProof.current(originalSource);
        JsonNode proof=ffdd.opsconsole.common.domain.LegacyUvtProof.current(originalProof);
        require("SUPPORT_EXCHANGE".equals(operation.get("businessType"))&&"PRODUCTION".equals(operation.get("sourceEnvironment"))&&"nx_exchange_order".equals(operation.get("sourceTable")));
        String phase=text(operation.get("phase")),no=text(operation.get("sourceKey"));
        require(Set.of("IMMEDIATE","HOLD","COMPLETE","CANCEL").contains(phase)&&key(no,phase).equals(operation.get("operationKey")));
        keys(source,SOURCE_KEYS);keys(proof,PROOF_KEYS);
        require(SCHEMA.equals(text(source.path("schemaVersion")))&&SCHEMA.equals(text(proof.path("schemaVersion")))&&phase.equals(text(source.path("phase")))&&"USDT".equals(text(source.path("feeCurrency"))));
        var actual=oneSource(input,no);var current=actual.order();validOrder(current);
        var order=source.path("order");require(no.equals(text(order.path("exchangeNo"))));
        long customer=number(current.get("customerId"));long id=number(operation.get("id"));require(id>0);
        require(number(oneOperation(input,key(no,phase)).get("id"))==id&&equal(codec.readCurrentProof(text(operation.get("sourceSnapshotJson"))),source)&&equal(codec.readCurrentProof(text(operation.get("sourceProofJson"))),proof));
        String status=text(current.get("status"));
        if(phase.equals("HOLD")) {
            require("QUEUED".equals(text(order.path("status")))&&Set.of("QUEUED","COMPLETED","CANCELLED").contains(status));
            for(String field:IMMUTABLE)require(equal(order.path(field),current.get(field)));
            if(status.equals("QUEUED"))require(equal(order,current));
        }else require(equal(order,current)&&("CANCEL".equals(phase)?"CANCELLED":"COMPLETED").equals(status));
        validOrder(map(order));
        boolean reserved=hasHold(actual);require(proof.path("sourceReservationPresent").isBoolean()&&proof.path("sourceReservationPresent").booleanValue()==reserved);
        require(phase.equals(text(proof.path("eventPhase")))&&(!phase.equals("IMMEDIATE")||!reserved));
        validateLedgers(actual,reserved);
        var phaseLedgers=phase.equals("HOLD")?actual.ledgers().stream().filter(l->(no+"-HOLD").equals(l.get("bizNo"))).toList():actual.ledgers();
        require(equal(source.path("ledgers"),phaseLedgers));
        BigDecimal fee=money(source.path("feeAmount"));require(fee.signum()>=0);
        if(Set.of("IMMEDIATE","COMPLETE").contains(phase))validateFee(actual,fee);
        else require(fee.signum()==0 &&source.path("feeAllocations").isArray()&&source.path("feeAllocations").isEmpty());
        require(equal(source.path("feeAllocations"),phase.equals("HOLD")?List.of():actual.feeAllocations()));
        LocalDateTime at=parse(source.path("phaseAt"));require(at.equals(parse(order.path("updatedAt")))&&at.equals(parse(proof.path("sourcePhaseAt")))&&!at.isBefore(parse(order.path("createdAt"))));
        for(var ledger:phaseLedgers){String biz=text(ledger.get("bizNo"));if(phase.equals("HOLD")||!biz.equals(no+"-HOLD"))require(!time(ledger.get("createdAt")).isBefore(at));}
        Integer precision=input.precisions().get("nx_exchange_order.updated_at");require(precision!=null);
        if(Set.of("COMPLETE","CANCEL").contains(phase)) {
            require(reserved&&key(no,"HOLD").equals(text(proof.path("originalHoldOperationKey"))));
            var hold=oneOperation(input,key(no,"HOLD"));require(number(hold.get("id"))<id);
            var oldSource=codec.readCurrentProof(text(hold.get("sourceSnapshotJson")));var oldProof=codec.readCurrentProof(text(hold.get("sourceProofJson")));
            var heldEvents=exchange.events(snapshot(oldSource),"HOLD");var prior=verify(hold,oldSource,oldProof,heldEvents,input);
            require(!at.isBefore(prior.stamp().succeededAt())&&equal(source.path("orderBefore"),oldSource.path("order"))&&equal(source.path("ledgersBefore"),oldSource.path("ledgers"))&&equal(source.path("feeAllocationsBefore"),List.of()));
            if(phase.equals("CANCEL"))for(String field:List.of("rate","toAmount"))require(equal(order.path(field),source.path("orderBefore").path(field)));
        }else require(source.path("orderBefore").isNull()&&source.path("ledgersBefore").isArray()&&source.path("ledgersBefore").isEmpty()&&source.path("feeAllocationsBefore").isArray()&&source.path("feeAllocationsBefore").isEmpty()&&proof.path("originalHoldOperationKey").isNull());
        if(phase.equals("IMMEDIATE"))require(at.equals(parse(order.path("createdAt"))));
        var expected=exchange.events(snapshot(source),phase);require(!events.isEmpty()&&expected.equals(events)&&equal(codec.readCurrentProof(text(operation.get("orderedEventsJson"))),json.valueToTree(events)));
        require(equal(proof.path("eventIds"),events.stream().map(Event::id).toList()));
        Point point=cash(operation,proof,events,input,customer);
        validateLedgerCash(phase,source.path("ledgers"),proof.path("cashAfter"),text(order.path("fromAsset")),text(order.path("toAsset")),no);
        return new Verified(new Stamp(no,at,DateTimeFormatConfig.BUSINESS_ZONE.getId(),precision),List.of(point),no+":"+phase);
    }
    public List<Holding> verifyCoverage(Set<Long> component,Inputs input,Set<String> coveredPhases) {
        var expected=new HashSet<String>();var holdings=new ArrayList<Holding>();var seen=new HashSet<String>();
        for(var source:input.sources())if(component.contains(number(source.order().get("customerId")))) {
            var order=source.order();validOrder(order);String no=text(order.get("exchangeNo"));require(seen.add(no));
            boolean reserved=hasHold(source);String status=text(order.get("status"));validateLedgers(source,reserved);
            List<String> phases=switch(status){case "QUEUED" -> {require(reserved&&source.feeAllocations().isEmpty());yield List.of("HOLD");}case "COMPLETED" -> reserved?List.of("HOLD","COMPLETE"):List.of("IMMEDIATE");case "CANCELLED" -> {require(reserved&&source.feeAllocations().isEmpty());yield List.of("HOLD","CANCEL");}case "GEO_BLOCKED","USER_CAP","PLATFORM_CAP" -> {require(source.ledgers().isEmpty()&&source.feeAllocations().isEmpty()&&time(order.get("createdAt")).equals(time(order.get("updatedAt"))));yield List.of();}default -> throw failure();};
            for(String phase:phases){String covered=no+":"+phase;require(expected.add(covered)&&coveredPhases.contains(covered));var op=oneOperation(input,key(no,phase));var snapshot=codec.readCurrentProof(text(op.get("sourceSnapshotJson")));verify(op,snapshot,codec.readCurrentProof(text(op.get("sourceProofJson"))),exchange.events(snapshot(snapshot),phase),input);}
            var actualOps=input.operations().stream().filter(o->"SUPPORT_EXCHANGE".equals(o.get("businessType"))&&no.equals(o.get("sourceKey"))).toList();require(actualOps.size()==phases.size()&&actualOps.stream().allMatch(o->phases.contains(o.get("phase"))));
            if(status.equals("QUEUED"))holdings.add(new Holding(new Account(number(order.get("customerId")),text(order.get("fromAsset")),"held:exchange-"+no),money(order.get("fromAmount")),no));
        }
        for(var op:input.operations())if("SUPPORT_EXCHANGE".equals(op.get("businessType"))) {
            long id=number(op.get("id"));if(input.parties().stream().anyMatch(p->number(p.get("operationId"))==id&&component.contains(number(p.get("customerId")))))require(seen.contains(text(op.get("sourceKey")))&&expected.contains(text(op.get("sourceKey"))+":"+text(op.get("phase"))));
        }
        require(expected.equals(coveredPhases));return List.copyOf(holdings);
    }
    private Point cash(Map<String,Object> operation,JsonNode proof,List<Event> events,Inputs input,long customer){
        long id=number(operation.get("id"));var parties=input.parties().stream().filter(p->number(p.get("operationId"))==id).toList();require(parties.size()==1&&number(parties.get(0).get("customerId"))==customer);
        var legs=input.legs().stream().filter(l->number(l.get("operationId"))==id).toList();require(legs.size()==1);var leg=legs.get(0);String incarnation=text(leg.get("incarnation"));long revision=number(leg.get("revision"));require(revision>0&&incarnation.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        var rows=input.raw().stream().filter(r->incarnation.equals(r.get("incarnation"))&&number(r.get("revision"))==revision).toList();require(rows.size()==1);var raw=rows.get(0);
        require("CHANGE".equals(raw.get("observation"))&&number(raw.get("oldUserId"))==customer&&number(raw.get("newUserId"))==customer&&number(raw.get("oldId"))==number(raw.get("newId"))&&number(raw.get("newId"))>0&&number(raw.get("newVersion"))==number(raw.get("oldVersion"))+1&&time(raw.get("oldCreatedAt")).equals(time(raw.get("newCreatedAt"))));
        verifyWallet(proof.path("cashBefore"),raw,"old",revision-1,incarnation);verifyWallet(proof.path("cashAfter"),raw,"new",revision,incarnation);
        var before=cash(raw,"old");var after=cash(raw,"new");require(before.pending().compareTo(after.pending())==0&&before.risk().compareTo(after.risk())==0);
        BigDecimal usd=BigDecimal.ZERO,nex=BigDecimal.ZERO;
        for(var e:events){if(e.from()!=null){require(e.from().customerId()==customer);if(e.from().slot().equals("available")){if(e.from().currency().equals("USDT"))usd=usd.subtract(e.amount());else nex=nex.subtract(e.amount());}}if(e.to()!=null){require(e.to().customerId()==customer);if(e.to().slot().equals("available")){BigDecimal credit=e.action()==Action.EXCHANGE?e.targetAmount():e.amount();if(e.to().currency().equals("USDT"))usd=usd.add(credit);else nex=nex.add(credit);}}}
        require(after.usdt().subtract(before.usdt()).compareTo(usd)==0&&after.nex().subtract(before.nex()).compareTo(nex)==0);
        return new Point(incarnation,revision,customer,before,after,events.stream().map(Event::id).toList());
    }
    private void verifyWallet(JsonNode wallet,Map<String,Object> raw,String side,long revision,String incarnation){
        keys(wallet,WALLET_KEYS);require(number(wallet.path("revision"))==revision&&incarnation.equals(text(wallet.path("incarnation"))));
        for(var entry:Map.of("id","Id","customerId","UserId","version","Version","usdtAvailable","UsdtAvailable","nexAvailable","NexAvailable","riskHeld","CregisRiskHeld","pendingWithdrawal","PendingWithdraw").entrySet())require(equal(wallet.path(entry.getKey()),raw.get(side+entry.getValue())));
        require(flag(wallet.path("sandbox"))==0&&flag(wallet.path("deleted"))==0&&flag(raw.get(side+"Sandbox"))==0&&flag(raw.get(side+"IsDeleted"))==0);
    }
    private static Cash cash(Map<String,Object> raw,String side){return new Cash(money(raw.get(side+"UsdtAvailable")),money(raw.get(side+"NexAvailable")),money(raw.get(side+"PendingWithdraw")),money(raw.get(side+"CregisRiskHeld")));}
    private void validateLedgers(SupportExchangeReadFacade.Source source,boolean reserved){
        var order=source.order();String no=text(order.get("exchangeNo")),status=text(order.get("status"));long customer=number(order.get("customerId"));String from=text(order.get("fromAsset")),to=text(order.get("toAsset"));
        Set<String> expected=switch(status){case "QUEUED" -> {require(reserved);yield Set.of("-HOLD");}case "COMPLETED" -> reserved?Set.of("-HOLD","-IN"):Set.of("-OUT","-IN");case "CANCELLED" -> {require(reserved);yield Set.of("-HOLD","-REFUND");}case "GEO_BLOCKED","USER_CAP","PLATFORM_CAP" -> Set.of();default -> throw failure();};
        require(source.ledgers().size()==expected.size());var ids=new HashSet<Long>();var suffixes=new HashSet<String>();
        for(var l:source.ledgers()) {String biz=text(l.get("bizNo"));require(biz.startsWith(no));String suffix=biz.substring(no.length());require(expected.contains(suffix)&&suffixes.add(suffix)&&number(l.get("id"))>0&&ids.add(number(l.get("id")))&&number(l.get("customerId"))==customer&&no.equals(l.get("exchangeNo"))&&"EXCHANGE_SWAP".equals(l.get("bizType"))&&"SUCCESS".equals(l.get("status"))&&flag(l.get("deleted"))==0);
            boolean in=suffix.equals("-IN");require((in?to:from).equals(l.get("asset"))&&(Set.of("-IN","-REFUND").contains(suffix)?"IN":"OUT").equals(l.get("direction"))&&money(l.get("amount")).compareTo(money(order.get(in?"toAmount":"fromAmount")))==0&&money(l.get("balanceAfter")).signum()>=0&&!time(l.get("createdAt")).isBefore(time(order.get("createdAt")))&&!time(l.get("updatedAt")).isBefore(time(l.get("createdAt"))));}
    }
    private void validateFee(SupportExchangeReadFacade.Source source,BigDecimal fee){
        var order=source.order();BigDecimal rate=rate(order.get("rate")),gross=money(order.get("fromAmount")),target=money(order.get("toAmount"));String from=text(order.get("fromAsset"));
        BigDecimal grossUsd=from.equals("USDT")?gross:gross.multiply(rate).setScale(6,RoundingMode.HALF_UP);require(fee.signum()>=0&&fee.compareTo(grossUsd)<0);
        require(target.compareTo(from.equals("USDT")?grossUsd.subtract(fee).divide(rate,6,RoundingMode.DOWN):grossUsd.subtract(fee))==0);
        if(fee.signum()==0){require(source.feeAllocations().isEmpty());return;}require(source.feeAllocations().size()==1);var f=source.feeAllocations().get(0);BigDecimal burn=fee.multiply(new BigDecimal(".30")).setScale(6,RoundingMode.DOWN);
        require(number(f.get("id"))>0&&order.get("exchangeNo").equals(f.get("exchangeNo"))&&flag(f.get("deleted"))==0&&money(f.get("totalFeeUsdt")).compareTo(fee)==0&&rate(f.get("priceUsdt")).compareTo(rate)==0&&decimal(f.get("burnRatio"),8).compareTo(new BigDecimal(".30"))==0&&money(f.get("burnPoolUsdt")).compareTo(burn)==0&&money(f.get("feeBufferUsdt")).compareTo(fee.subtract(burn))==0&&money(f.get("nexEquivalent")).compareTo(burn.divide(rate,6,RoundingMode.DOWN))==0&&!time(f.get("createdAt")).isBefore(time(order.get("updatedAt")))&&!time(f.get("updatedAt")).isBefore(time(f.get("createdAt"))));
    }
    private void validateLedgerCash(String phase,JsonNode ledgers,JsonNode after,String from,String to,String no){
        require(ledgers.isArray());for(var ledger:ledgers){String biz=text(ledger.path("bizNo"));boolean relevant=biz.equals(no+(phase.equals("HOLD")?"-HOLD":phase.equals("CANCEL")?"-REFUND":"-IN"))||phase.equals("IMMEDIATE")&&biz.equals(no+"-OUT");if(relevant){String asset=text(ledger.path("asset"));require(equal(ledger.path("balanceAfter"),after.path(asset.equals("USDT")?"usdtAvailable":"nexAvailable")));}}
    }
    private static void validOrder(Map<String,Object> order){require(number(order.get("id"))>0&&number(order.get("customerId"))>0&&text(order.get("exchangeNo")).matches("[^\\s:]{1,107}")&&flag(order.get("deleted"))==0&&flag(order.get("userSandbox"))==0&&flag(order.get("userDeleted"))==0);String from=text(order.get("fromAsset")),to=text(order.get("toAsset"));require(Set.of("USDT","UVT").contains(from)&&Set.of("USDT","UVT").contains(to)&&!from.equals(to)&&money(order.get("fromAmount")).signum()>0&&money(order.get("toAmount")).signum()>0&&rate(order.get("rate")).signum()>0&&!time(order.get("updatedAt")).isBefore(time(order.get("createdAt"))));}
    private static boolean hasHold(SupportExchangeReadFacade.Source source){String no=text(source.order().get("exchangeNo"));return source.ledgers().stream().anyMatch(l->(no+"-HOLD").equals(l.get("bizNo"))&&"EXCHANGE_SWAP".equals(l.get("bizType"))&&"OUT".equals(l.get("direction"))&&"SUCCESS".equals(l.get("status"))&&flag(l.get("deleted"))==0);}
    private static SupportExchangeReadFacade.Source oneSource(Inputs input,String no){var matches=input.sources().stream().filter(s->no.equals(s.order().get("exchangeNo"))).toList();require(matches.size()==1);return matches.get(0);}
    private static Map<String,Object> oneOperation(Inputs input,String key){var matches=input.operations().stream().filter(o->key.equals(o.get("operationKey"))).toList();require(matches.size()==1);return matches.get(0);}
    private SupportExchangeReadFacade.Source snapshot(JsonNode node){return new SupportExchangeReadFacade.Source(map(node.path("order")),rows(node.path("ledgers")),rows(node.path("feeAllocations")));}
    private static Map<String,Object> map(JsonNode node){require(node.isObject());var result=new LinkedHashMap<String,Object>();node.fields().forEachRemaining(e->result.put(e.getKey(),value(e.getValue())));return result;}
    private static Object value(JsonNode node){if(node.isNull())return null;if(node.isObject())return map(node);if(node.isArray()){var values=new ArrayList<>();node.forEach(v->values.add(value(v)));return values;}if(node.isNumber())return node.decimalValue();if(node.isBoolean())return node.booleanValue();require(node.isTextual());return node.textValue();}
    private static List<Map<String,Object>> rows(JsonNode node){require(node.isArray());var result=new ArrayList<Map<String,Object>>();node.forEach(n->result.add(map(n)));return result;}
    private boolean equal(JsonNode node,Object value){return codec.canonical(node).equals(codec.canonical(value instanceof JsonNode n?n:json.valueToTree(normalize(value))));}
    private static Object normalize(Object value){if(value instanceof Timestamp t)return t.toLocalDateTime().toString();if(value instanceof LocalDateTime t)return t.toString();if(value instanceof Map<?,?> m){var out=new LinkedHashMap<String,Object>();m.forEach((k,v)->out.put(k.toString(),normalize(v)));return out;}if(value instanceof Collection<?> c)return c.stream().map(SupportFundExchangeEvidence::normalize).toList();return value;}
    private static String key(String no,String phase){try{return "EXCHANGE:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((no+"\n"+phase).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String text(Object value){require(value instanceof String&&!((String)value).isBlank());return (String)value;}
    private static String text(JsonNode node){require(node.isTextual());return text(node.textValue());}
    private static BigDecimal decimal(Object value,int scale){require(value instanceof Number);var n=new BigDecimal(value.toString());require(n.stripTrailingZeros().scale()<=scale);return n;}
    private static BigDecimal money(Object value){return decimal(value,6);}
    private static BigDecimal money(JsonNode value){require(value.isNumber());return money(value.numberValue());}
    private static BigDecimal rate(Object value){return decimal(value,8);}
    private static long number(Object value){try{return decimal(value,0).longValueExact();}catch(ArithmeticException e){throw failure();}}
    private static long number(JsonNode node){require(node.isIntegralNumber()||node.isNumber()&&node.decimalValue().stripTrailingZeros().scale()<=0);return number(node.numberValue());}
    private static int flag(Object value){if(value instanceof Boolean b)return b?1:0;long n=number(value);require(n==0||n==1);return (int)n;}
    private static int flag(JsonNode node){if(node.isBoolean())return node.booleanValue()?1:0;require(node.isNumber());return flag(node.numberValue());}
    private static LocalDateTime time(Object value){if(value instanceof Timestamp t)return t.toLocalDateTime();if(value instanceof LocalDateTime t)return t;if(value instanceof String s){try{return LocalDateTime.parse(s);}catch(java.time.format.DateTimeParseException e){try{return LocalDateTime.parse(s,DateTimeFormatConfig.DATE_TIME_FORMATTER);}catch(java.time.format.DateTimeParseException invalid){throw failure();}}}throw failure();}
    private static LocalDateTime parse(JsonNode node){return time(text(node));}
    private static void keys(JsonNode node,Set<String> expected){require(node.isObject());var fields=new HashSet<String>();node.fieldNames().forEachRemaining(fields::add);require(fields.equals(expected));}
    private static IllegalArgumentException failure(){return new IllegalArgumentException("Unproven exchange source");}
    private static void require(boolean value){if(!value)throw failure();}
}
