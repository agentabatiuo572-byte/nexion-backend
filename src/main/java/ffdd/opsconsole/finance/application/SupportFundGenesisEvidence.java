package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.finance.domain.SupportFundReplay.*;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import ffdd.opsconsole.market.facade.SupportGenesisReadFacade;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Verifies the Genesis writer's immutable receipt; Reader owns the physical snapshot and complete lifetime replay. */
@Service
@RequiredArgsConstructor
public class SupportFundGenesisEvidence {
    private final ObjectMapper json;
    private final SupportFundJournalBindingService codec;
    public record Inputs(Snapshot history,List<SupportGenesisReadFacade.Source> sources,List<Map<String,Object>> raw,
                         List<Map<String,Object>> operations,List<Map<String,Object>> parties,List<Map<String,Object>> legs) {}
    public record Verified(Stamp stamp,List<Point> points,String factId) {public Verified {points=List.copyOf(points);}}
    public Verified verify(Map<String,Object> operation,JsonNode source,JsonNode proof,List<Event> events,Inputs input) {
        require(operation!=null &&source!=null &&proof!=null &&events!=null);validInputs(input);
        keys(source,Set.of("schemaVersion","customerId","factId","source","orderNo","currency","amount","succeededAt","fractionalSecondDigits","businessZone","settlement"));
        keys(proof,Set.of("schemaVersion","beforeHolding","acquisitionSource","cashBefore","cashAfter","cashTrace"));
        require("SUPPORT_GENESIS_FUND_V1".equals(text(source.path("schemaVersion"))) &&"SUPPORT_GENESIS_FUND_V1".equals(text(proof.path("schemaVersion"))));
        require("SUPPORT_GENESIS".equals(operation.get("businessType")) &&"PRODUCTION".equals(operation.get("sourceEnvironment"))
            &&"nx_genesis_order".equals(operation.get("sourceTable")));
        long operationId=id(operation.get("id"));String no=text(source.path("orderNo"));
        require(("GENESIS:"+no).equals(operation.get("operationKey")) &&no.equals(operation.get("sourceKey")));
        var actual=oneSource(input.sources(),no);var rows=SupportPaymentSourceService.genesisRows(actual);require(rows.size()==1);
        Source family=Source.valueOf(text(source.path("source")));require(SupportPaymentFacts.genesis(family));
        require(SupportPaymentFactService.invalidNewSource(rows.get(0),family)==null &&family.name().equals(rows.get(0).get("source")));
        Fact fact=SupportPaymentFactService.fact(rows.get(0),family);require(SupportPaymentFacts.validateCanonical(fact,"Asia/Shanghai")==null);
        var facts=input.history().facts().stream().filter(f->fact.factId().equals(f.factId())).toList();require(facts.size()==1);
        require(normalizedAmount(fact).equals(normalizedAmount(facts.get(0))) &&input.history().issues().stream().noneMatch(i->(i.customerId()==null ||i.customerId()==fact.customerId())
            &&(i.source()==null ||i.source()==family)));
        require(equal(source.path("settlement"),Map.of("order",actual.order(),"ledgers",actual.ledgers())));
        require(id(source.path("customerId"))==fact.customerId() &&text(source.path("factId")).equals(fact.factId())
            &&text(source.path("currency")).equals(fact.currency()) &&amount(source.path("amount")).compareTo(fact.amount())==0
            &&time(source.path("succeededAt")).equals(fact.succeededAt()) &&id(source.path("fractionalSecondDigits"))==0
            &&"Asia/Shanghai".equals(text(source.path("businessZone"))));
        var order=actual.order();boolean primary=family==Source.GENESIS_PRIMARY;
        require((primary?"PRIMARY":"SECONDARY").equals(operation.get("phase")));
        var buyer=new Account(fact.customerId(),"USDT","available");var expectedEvents=new ArrayList<Event>();
        var expectedParties=new TreeSet<Long>();expectedParties.add(fact.customerId());var deltas=new TreeMap<Long,BigDecimal>();deltas.put(fact.customerId(),fact.amount().negate());
        if(primary) {
            require(proof.path("beforeHolding").isNull() &&proof.path("acquisitionSource").isNull());
            expectedEvents.add(new Event("CONSUME:"+fact.factId(),Action.CONSUME,buyer,null,null,fact.amount(),null));
        } else {
            long seller=id(order.get("sellerId"));expectedParties.add(seller);var holding=proof.path("beforeHolding");require(holding.isObject());
            require(id(holding.path("id"))>0 &&id(holding.path("customerId"))==seller &&"LISTED".equals(text(holding.path("status")))
                &&bit(holding.path("deleted"))==0 &&equal(holding.path("holdingNo"),order.get("holdingNo"))
                &&equal(holding.path("seriesCode"),order.get("seriesCode")) &&amount(holding.path("listingPrice")).compareTo(fact.amount())==0);
            var acquisition=oneSource(input.sources(),text(holding.path("orderNo")));
            require(equal(proof.path("acquisitionSource"),Map.of("order",acquisition.order(),"ledgers",acquisition.ledgers())));
            var acquisitionRows=SupportPaymentSourceService.genesisRows(acquisition);require(acquisitionRows.size()==1);
            Source acquisitionFamily=Source.valueOf(acquisitionRows.get(0).get("source").toString());
            require(SupportPaymentFactService.invalidNewSource(acquisitionRows.get(0),acquisitionFamily)==null
                &&id(acquisition.order().get("customerId"))==seller &&equal(holding.path("seriesCode"),acquisition.order().get("seriesCode"))
                &&equal(holding.path("acquiredAt"),acquisition.order().get("paidAt")) &&equal(holding.path("acquiredPrice"),acquisition.order().get("unitPrice")));
            if(acquisitionFamily==Source.GENESIS_SECONDARY)require(equal(holding.path("holdingNo"),acquisition.order().get("holdingNo")));
            else {
                String prefix=text(acquisition.order().get("orderNo"))+"-";String holdingNo=text(holding.path("holdingNo"));require(holdingNo.startsWith(prefix));
                String index=holdingNo.substring(prefix.length());require(index.matches("[0-9]{2,}"));
                long unit=Long.parseLong(index);require(unit>0 &&unit<=id(acquisition.order().get("quantity")));
            }
            BigDecimal fee=amount(order.get("royalty")),net=fact.amount().subtract(fee);require(fee.signum()>=0 &&net.signum()>0);
            expectedEvents.add(new Event("CONSUME:"+fact.factId(),Action.TRADE,buyer,new Account(seller,"USDT","available"),null,fact.amount(),net));deltas.put(seller,net);
        }
        require(expectedEvents.equals(events));var eventIds=events.stream().map(Event::id).toList();
        var actualParties=input.parties().stream().filter(p->id(p.get("operationId"))==operationId).map(p->id(p.get("customerId"))).toList();
        require(actualParties.equals(List.copyOf(expectedParties)));
        var opLegs=input.legs().stream().filter(l->id(l.get("operationId"))==operationId).toList();require(opLegs.size()==expectedParties.size());
        require(codec.decodeReceipt(operation,actualParties,opLegs).equals(events));
        var originalOps=input.operations().stream().filter(o->id(o.get("id"))==operationId).toList();require(originalOps.size()==1 &&equal(json.valueToTree(normalize(operation)),originalOps.get(0)));
        JsonNode before=proof.path("cashBefore"),after=proof.path("cashAfter"),trace=proof.path("cashTrace");
        keys(before,expectedParties.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet()));keys(after,expectedParties.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet()));
        require(trace.isArray() &&trace.size()==expectedParties.size());var points=new ArrayList<Point>();var covered=new HashSet<String>();var tracedParties=new TreeSet<Long>();
        for(var item:trace) {
            keys(item,Set.of("customerId","incarnation","revision","eventIds","raw","sourceIds"));long customer=id(item.path("customerId"));
            require(expectedParties.contains(customer) &&tracedParties.add(customer) &&equal(item.path("eventIds"),eventIds));
            String incarnation=text(item.path("incarnation"));long revision=id(item.path("revision"));require(revision>0 &&covered.add(incarnation+":"+revision));
            var raws=input.raw().stream().filter(r->incarnation.equals(r.get("incarnation")) &&id(r.get("revision"))==revision).toList();require(raws.size()==1);
            var raw=raws.get(0);require(equal(item.path("raw"),raw) &&"CHANGE".equals(raw.get("observation"))
                &&id(raw.get("oldUserId"))==customer &&id(raw.get("newUserId"))==customer);
            JsonNode b=before.path(String.valueOf(customer)),a=after.path(String.valueOf(customer));require(b.isObject() &&a.isObject());
            require(text(b.path("incarnation")).equals(incarnation) &&text(a.path("incarnation")).equals(incarnation)
                &&id(b.path("revision"))+1==revision &&id(a.path("revision"))==revision &&id(a.path("version"))==id(b.path("version"))+1
                &&id(b.path("id"))==id(a.path("id")));
            for(String field:List.of("id","userId","usdtAvailable","nexAvailable","pendingWithdraw","cregisRiskHeld","version","createdAt")) {
                String cap=Character.toUpperCase(field.charAt(0))+field.substring(1);require(equal(b.path(field),raw.get("old"+cap)) &&equal(a.path(field),raw.get("new"+cap)));
            }
            for(String field:List.of("sandbox","isDeleted")) {String cap=Character.toUpperCase(field.charAt(0))+field.substring(1);
                require(bit(b.path(field))==0 &&bit(a.path(field))==0 &&bit(raw.get("old"+cap))==0 &&bit(raw.get("new"+cap))==0);}
            for(String field:List.of("usdtAvailable","nexAvailable","pendingWithdraw","cregisRiskHeld"))
                require(amount(a.path(field)).subtract(amount(b.path(field))).compareTo(field.equals("usdtAvailable")?deltas.get(customer):BigDecimal.ZERO)==0);
            var ledger=actual.ledgers().stream().filter(l->id(l.get("customerId"))==customer).toList();require(ledger.size()==1);
            require(equal(item.path("sourceIds"),List.of("nx_wallet_ledger:"+id(ledger.get(0).get("id"))))
                &&amount(ledger.get(0).get("balanceAfter")).compareTo(amount(a.path("usdtAvailable")))==0);
            require(opLegs.stream().filter(l->incarnation.equals(l.get("incarnation")) &&id(l.get("revision"))==revision).count()==1);
            points.add(new Point(incarnation,revision,customer,cash(b),cash(a),eventIds));
        }
        require(tracedParties.equals(expectedParties));points.sort(Comparator.comparingLong(Point::customerId));
        return new Verified(new Stamp("nx_genesis_order:"+id(order.get("id")),fact.succeededAt(),"Asia/Shanghai",0),points,fact.factId());
    }
    public void verifyCoverage(Set<Long> component,Inputs input,Set<String> represented) {
        require(component!=null &&represented!=null);validInputs(input);
        for(var source:input.sources()) {
            var order=source.order();long buyer=id(order.get("customerId"));Object seller=order.get("sellerId");
            if(!component.contains(buyer) &&(seller==null ||!component.contains(id(seller))))continue;
            require(component.contains(buyer) &&(seller==null ||component.contains(id(seller))));
            var rows=SupportPaymentSourceService.genesisRows(source);require(rows.size()==1);Source family=Source.valueOf(rows.get(0).get("source").toString());
            require(SupportPaymentFactService.invalidNewSource(rows.get(0),family)==null &&represented.contains(SupportPaymentFactService.fact(rows.get(0),family).factId()));
        }
    }
    private static SupportGenesisReadFacade.Source oneSource(List<SupportGenesisReadFacade.Source> sources,String no) {
        var rows=sources.stream().filter(s->no.equals(s.order().get("orderNo"))).toList();require(rows.size()==1);return rows.get(0);
    }
    private static Fact normalizedAmount(Fact fact) {
        return new Fact(fact.factId(),fact.kind(),fact.source(),fact.sourceIds(),fact.customerId(),fact.ledgerId(),fact.sourceBusinessId(),
            fact.orderNo(),fact.orderType(),fact.originalFactId(),fact.currency(),fact.amount().stripTrailingZeros(),fact.succeededAt(),
            fact.successTimeField(),fact.fractionalSecondDigits(),fact.providerPaidAt(),fact.ledgerRecordedAt(),fact.sourceConfirmationAt(),
            fact.sourceVersion(),fact.historicalEnvironmentStatus());
    }
    private static void validInputs(Inputs input) {
        require(input!=null &&input.history()!=null &&input.sources()!=null &&input.raw()!=null &&input.operations()!=null &&input.parties()!=null &&input.legs()!=null);
        require(input.sources().stream().noneMatch(Objects::isNull) &&input.raw().stream().noneMatch(Objects::isNull)
            &&input.operations().stream().noneMatch(Objects::isNull) &&input.parties().stream().noneMatch(Objects::isNull) &&input.legs().stream().noneMatch(Objects::isNull));
    }
    private static Cash cash(JsonNode wallet) {return new Cash(amount(wallet.path("usdtAvailable")),amount(wallet.path("nexAvailable")),amount(wallet.path("pendingWithdraw")),amount(wallet.path("cregisRiskHeld")));}
    private boolean equal(JsonNode node,Object value) {return codec.canonical(node).equals(codec.canonical(json.valueToTree(normalize(value))));}
    private static Object normalize(Object value) {
        if(value instanceof Timestamp t)return t.toLocalDateTime().toString();if(value instanceof LocalDateTime t)return t.toString();
        if(value instanceof Map<?,?> m){var out=new LinkedHashMap<String,Object>();m.forEach((k,v)->out.put(k.toString(),normalize(v)));return out;}
        if(value instanceof Collection<?> c)return c.stream().map(SupportFundGenesisEvidence::normalize).toList();return value;
    }
    private static String text(Object value) {require(value instanceof String &&!((String)value).isBlank());return (String)value;}
    private static String text(JsonNode value) {require(value.isTextual());return text(value.textValue());}
    private static BigDecimal amount(Object value) {require(value instanceof Number);var n=new BigDecimal(value.toString());require(n.stripTrailingZeros().scale()<=6);return n;}
    private static BigDecimal amount(JsonNode value) {require(value.isNumber());return amount(value.numberValue());}
    private static long id(Object value) {try{return amount(value).longValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException("Unproven Genesis identifier",e);}}
    private static long id(JsonNode value) {require(value.isNumber());return id(value.numberValue());}
    private static int bit(Object value) {if(value instanceof Boolean b)return b?1:0;long n=id(value);require(n==0 ||n==1);return (int)n;}
    private static int bit(JsonNode value) {if(value.isBoolean())return value.booleanValue()?1:0;require(value.isNumber());return bit(value.numberValue());}
    private static LocalDateTime time(JsonNode value) {return LocalDateTime.parse(text(value));}
    private static void keys(JsonNode node,Set<String> expected) {require(node.isObject());var fields=new HashSet<String>();node.fieldNames().forEachRemaining(fields::add);require(fields.equals(expected));}
    private static void require(boolean valid) {if(!valid)throw new IllegalArgumentException("Unproven Genesis source");}
}
