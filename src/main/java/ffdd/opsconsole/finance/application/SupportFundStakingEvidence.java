package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.*;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.finance.domain.SupportFundReplay.*;
import ffdd.opsconsole.market.facade.SupportStakingReadFacade;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Original positions/ledgers/interest validation. Reader owns raw closure and every receipt digest. */
@Service
@RequiredArgsConstructor
public class SupportFundStakingEvidence {
    private final SupportFundJournalBindingService codec;
    private final ObjectMapper json;
    private final SupportFundEarningsEvidence earnings;
    public record Inputs(List<SupportStakingReadFacade.Source> sources,List<Map<String,Object>> raw,
                         List<Map<String,Object>> operations,List<Map<String,Object>> parties,
                         List<Map<String,Object>> legs,Map<String,Integer> precisions,
                         SupportFundEarningsEvidence.Inputs earnings) {}
    public record Verified(Stamp stamp,List<Point> points,String coveredPhase,Set<String> representedEarnings) {
        public Verified {points=List.copyOf(points);representedEarnings=Set.copyOf(representedEarnings);}
    }
    private static final String SCHEMA="SUPPORT_STAKING_FUND_V1";
    private static final Set<String> IMMUTABLE=Set.of("id","customerId","positionNo","productId","productCode","productName","amountUsdt","apyBps","earlyPenaltyBps","termDays","lockedAt","unlockAt","estimatedInterestUsdt","createdAt","deleted","userSandbox","userDeleted");
    private static final Set<String> POSITION_KEYS=new HashSet<>(IMMUTABLE);
    static {POSITION_KEYS.addAll(Set.of("status","claimedAt","earlyWithdrawnAt","updatedAt"));}
    private static final Set<String> WALLET_KEYS=Set.of("id","customerId","incarnation","revision","version","usdtAvailable","nexAvailable","riskHeld","pendingWithdrawal","sandbox","deleted");
    public Verified verify(Map<String,Object> operation,JsonNode originalSource,JsonNode originalProof,List<Event> events,Inputs input) {
        JsonNode source=ffdd.opsconsole.common.domain.LegacyUvtProof.current(originalSource);
        JsonNode proof=ffdd.opsconsole.common.domain.LegacyUvtProof.current(originalProof);
        require("SUPPORT_STAKING".equals(operation.get("businessType"))&&"PRODUCTION".equals(operation.get("sourceEnvironment"))&&"nx_staking_position".equals(operation.get("sourceTable")));
        String phase=text(operation.get("phase")),no=text(operation.get("sourceKey"));require(Set.of("OPEN","CLAIM","EARLY").contains(phase));
        require(SupportStakingReadFacade.operationKey(no,phase).equals(operation.get("operationKey")));
        keys(source,Set.of("schemaVersion","phase","position","ledgers","positionBefore","ledgersBefore","phaseAt"));
        keys(proof,Set.of("schemaVersion","cashBefore","cashAfter","eventIds","originalOpenOperationKey"));
        require(SCHEMA.equals(text(source.path("schemaVersion")))&&SCHEMA.equals(text(proof.path("schemaVersion")))&&phase.equals(text(source.path("phase"))));
        var actual=oneSource(input,no);var current=actual.position();validPosition(current);validateLedgers(actual);
        var p=source.path("position");keys(p,POSITION_KEYS);require(no.equals(text(p.path("positionNo"))));
        for(String field:IMMUTABLE)require(equal(p.path(field),current.get(field)));
        String status=text(current.get("status"));
        if(phase.equals("OPEN"))require("ACTIVE".equals(text(p.path("status")))&&p.path("claimedAt").isNull()&&p.path("earlyWithdrawnAt").isNull());
        else require(equal(p,current)&&(phase.equals("CLAIM")?"CLAIMED":"EARLY_WITHDRAWN").equals(status));
        validPosition(map(p));
        var phaseLedgers=phase.equals("OPEN")?actual.ledgers().stream().filter(l->(no+"-OPEN").equals(l.get("bizNo"))).toList():actual.ledgers();
        require(equal(source.path("ledgers"),phaseLedgers));
        String column=switch(phase){case "OPEN"->"created_at";case "CLAIM"->"claimed_at";default->"early_withdrawn_at";};
        String field=switch(phase){case "OPEN"->"createdAt";case "CLAIM"->"claimedAt";default->"earlyWithdrawnAt";};
        LocalDateTime at=time(current.get(field));require(at.equals(time(text(source.path("phaseAt")))));
        Integer precision=input.precisions().get("nx_staking_position."+column);require(precision!=null&&precision>=0&&precision<=6&&at.getNano()%(int)Math.pow(10,9-precision)==0);
        require(events.equals(SupportStakingReadFacade.events(map(p),phase))&&!events.isEmpty()&&equal(codec.readCurrentProof(text(operation.get("orderedEventsJson"))),json.valueToTree(events)));
        require(equal(codec.readCurrentProof(text(operation.get("sourceSnapshotJson"))),source)&&equal(codec.readCurrentProof(text(operation.get("sourceProofJson"))),proof)&&equal(proof.path("eventIds"),events.stream().map(Event::id).toList()));
        require(number(oneOperation(input,text(operation.get("operationKey"))).get("id"))==number(operation.get("id")));
        Point point=cash(operation,proof,events,input,number(current.get("customerId")));
        Set<String> represented=Set.of();
        if(phase.equals("OPEN"))require(source.path("positionBefore").isNull()&&source.path("ledgersBefore").isArray()&&source.path("ledgersBefore").isEmpty()&&proof.path("originalOpenOperationKey").isNull());
        else {
            String openKey=SupportStakingReadFacade.operationKey(no,"OPEN");require(openKey.equals(text(proof.path("originalOpenOperationKey"))));
            var open=oneOperation(input,openKey);var birth=codec.readCurrentProof(text(open.get("sourceSnapshotJson")));var birthProof=codec.readCurrentProof(text(open.get("sourceProofJson")));
            var prior=verify(open,birth,birthProof,SupportStakingReadFacade.events(map(birth.path("position")),"OPEN"),input);
            require(number(open.get("id"))<number(operation.get("id"))&&point.incarnation().equals(prior.points().get(0).incarnation())&&point.revision()>prior.points().get(0).revision());
            var before=source.path("positionBefore");keys(before,POSITION_KEYS);validPosition(map(before));
            require(Set.of("ACTIVE","MATURE_UNCLAIMED").contains(text(before.path("status")))&&before.path("claimedAt").isNull()&&before.path("earlyWithdrawnAt").isNull());
            for(String f:IMMUTABLE)require(equal(before.path(f),p.path(f)));
            require(equal(source.path("ledgersBefore"),birth.path("ledgers"))&&!at.isBefore(prior.stamp().succeededAt()));
            if(phase.equals("EARLY"))require("ACTIVE".equals(text(before.path("status"))));
        }
        String suffix=phase.equals("OPEN")?"-OPEN":phase.equals("CLAIM")?"-CLAIM":"-EARLY";
        var ledger=phaseLedgers.stream().filter(l->(no+suffix).equals(l.get("bizNo"))).toList();require(ledger.size()==1);
        BigDecimal ledgerAfter=money(proof.path("cashAfter").path("usdtAvailable"));
        if(phase.equals("CLAIM")&&!repurchase(current)&&money(current.get("estimatedInterestUsdt")).signum()>0) {
            ledgerAfter=ledgerAfter.add(money(current.get("estimatedInterestUsdt")));
            represented=validateIndependentInterest(current,point,input);
        }
        require(money(ledger.get(0).get("balanceAfter")).compareTo(ledgerAfter)==0);
        return new Verified(new Stamp(no,at,DateTimeFormatConfig.BUSINESS_ZONE.getId(),precision),List.of(point),no+":"+phase,represented);
    }
    private Set<String> validateIndependentInterest(Map<String,Object> p,Point principal,Inputs input) {
        require(input.earnings()!=null);
        var matches=input.earnings().entries().stream().filter(e->"staking_interest".equals(e.get("sourceType"))&&p.get("positionNo").equals(e.get("sourceRef"))).toList();require(matches.size()==1);
        var entry=matches.get(0);String no=text(entry.get("entryNo"));
        require(number(entry.get("userId"))==number(p.get("customerId"))&&"USDT".equals(entry.get("asset"))&&("G1-STAKING-INTEREST-"+number(p.get("id"))).equals(entry.get("idempotencyKey"))&&money(entry.get("amount")).compareTo(money(p.get("estimatedInterestUsdt")))==0);
        var op=oneOperation(input,"EARNINGS:CREDIT:"+no);long id=number(op.get("id"));
        require(id>number(oneOperation(input,SupportStakingReadFacade.operationKey(text(p.get("positionNo")),"CLAIM")).get("id")));
        var parties=input.parties().stream().filter(x->number(x.get("operationId"))==id).map(x->number(x.get("customerId"))).toList();
        var legs=input.legs().stream().filter(x->number(x.get("operationId"))==id).toList();require(legs.size()==1);
        var ordered=codec.decodeReceipt(op,parties,legs);
        var verified=earnings.verify(op,codec.readCurrentProof(text(op.get("sourceSnapshotJson"))),codec.readCurrentProof(text(op.get("sourceProofJson"))),ordered,input.earnings());
        require((no+":CREDIT").equals(verified.coveredPhase())&&verified.checkpoints().isEmpty());
        var leg=legs.get(0);require(principal.incarnation().equals(leg.get("incarnation"))&&number(leg.get("revision"))==principal.revision()+1);
        var raws=input.raw().stream().filter(r->principal.incarnation().equals(r.get("incarnation"))&&number(r.get("revision"))==principal.revision()+1).toList();require(raws.size()==1);
        var raw=raws.get(0);require("CHANGE".equals(raw.get("observation"))&&number(raw.get("oldUserId"))==number(p.get("customerId"))&&number(raw.get("newUserId"))==number(p.get("customerId"))&&number(raw.get("oldId"))==number(raw.get("newId"))&&number(entry.get("sourceWalletId"))==number(raw.get("newId"))&&cash(raw,"old").equals(principal.after()));
        require(money(raw.get("newUsdtAvailable")).subtract(money(raw.get("oldUsdtAvailable"))).compareTo(money(p.get("estimatedInterestUsdt")))==0&&money(raw.get("newNexAvailable")).compareTo(money(raw.get("oldNexAvailable")))==0&&money(raw.get("newPendingWithdraw")).compareTo(money(raw.get("oldPendingWithdraw")))==0&&money(raw.get("newCregisRiskHeld")).compareTo(money(raw.get("oldCregisRiskHeld")))==0);
        return Set.of(no);
    }
    public List<Holding> verifyCoverage(Set<Long> component,Inputs input,Set<String> covered) {
        var expected=new HashSet<String>();var seen=new HashSet<String>();var holdings=new ArrayList<Holding>();
        for(var source:input.sources())if(component.contains(number(source.position().get("customerId")))) {
            var p=source.position();validPosition(p);validateLedgers(source);String no=text(p.get("positionNo"));require(seen.add(no));
            if(input.earnings()!=null) {
                var linked=input.earnings().entries().stream().filter(e->"staking_interest".equals(e.get("sourceType"))&&no.equals(e.get("sourceRef"))).toList();
                require(linked.size()==(!repurchase(p)&&"CLAIMED".equals(p.get("status"))&&money(p.get("estimatedInterestUsdt")).signum()>0?1:0));
            }
            List<String> phases=switch(text(p.get("status"))){case "ACTIVE","MATURE_UNCLAIMED"->List.of("OPEN");case "CLAIMED"->List.of("OPEN","CLAIM");case "EARLY_WITHDRAWN"->List.of("OPEN","EARLY");default->throw failure();};
            for(String phase:phases){require(expected.add(no+":"+phase)&&covered.contains(no+":"+phase));var op=oneOperation(input,SupportStakingReadFacade.operationKey(no,phase));var s=codec.readCurrentProof(text(op.get("sourceSnapshotJson")));verify(op,s,codec.readCurrentProof(text(op.get("sourceProofJson"))),SupportStakingReadFacade.events(map(s.path("position")),phase),input);}
            var ops=input.operations().stream().filter(o->"SUPPORT_STAKING".equals(o.get("businessType"))&&no.equals(o.get("sourceKey"))).toList();require(ops.size()==phases.size()&&ops.stream().allMatch(o->phases.contains(o.get("phase"))));
            if(phases.size()==1)holdings.add(new Holding(new Account(number(p.get("customerId")),"USDT","held:staking-"+no),money(p.get("amountUsdt")),no));
        }
        for(var op:input.operations())if("SUPPORT_STAKING".equals(op.get("businessType"))) {long id=number(op.get("id"));if(input.parties().stream().anyMatch(p->number(p.get("operationId"))==id&&component.contains(number(p.get("customerId")))))require(seen.contains(text(op.get("sourceKey")))&&expected.contains(text(op.get("sourceKey"))+":"+text(op.get("phase"))));}
        if(input.earnings()!=null)for(var entry:input.earnings().entries())if("staking_interest".equals(entry.get("sourceType"))&&component.contains(number(entry.get("userId"))))require(seen.contains(text(entry.get("sourceRef"))));
        require(expected.equals(covered));return List.copyOf(holdings);
    }
    private Point cash(Map<String,Object> op,JsonNode proof,List<Event> events,Inputs input,long customer) {
        long id=number(op.get("id"));var parties=input.parties().stream().filter(p->number(p.get("operationId"))==id).toList();require(parties.size()==1&&number(parties.get(0).get("customerId"))==customer);
        var legs=input.legs().stream().filter(l->number(l.get("operationId"))==id).toList();require(legs.size()==1);var leg=legs.get(0);String incarnation=text(leg.get("incarnation"));long revision=number(leg.get("revision"));require(revision>0&&incarnation.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        var rows=input.raw().stream().filter(r->incarnation.equals(r.get("incarnation"))&&number(r.get("revision"))==revision).toList();require(rows.size()==1);var raw=rows.get(0);
        require("CHANGE".equals(raw.get("observation"))&&number(raw.get("oldUserId"))==customer&&number(raw.get("newUserId"))==customer&&number(raw.get("oldId"))==number(raw.get("newId"))&&number(raw.get("newId"))>0&&number(raw.get("newVersion"))==number(raw.get("oldVersion"))+1);
        wallet(proof.path("cashBefore"),raw,"old",revision-1,incarnation);wallet(proof.path("cashAfter"),raw,"new",revision,incarnation);
        Cash before=cash(raw,"old"),after=cash(raw,"new");BigDecimal delta=BigDecimal.ZERO;
        for(var e:events){if(e.from()!=null){require(e.from().customerId()==customer&&e.from().currency().equals("USDT"));if(e.from().slot().equals("available"))delta=delta.subtract(e.amount());}if(e.to()!=null){require(e.to().customerId()==customer&&e.to().currency().equals("USDT"));if(e.to().slot().equals("available"))delta=delta.add(e.amount());}}
        require(after.usdt().subtract(before.usdt()).compareTo(delta)==0&&after.nex().compareTo(before.nex())==0&&after.pending().compareTo(before.pending())==0&&after.risk().compareTo(before.risk())==0);
        return new Point(incarnation,revision,customer,before,after,events.stream().map(Event::id).toList());
    }
    private void wallet(JsonNode wallet,Map<String,Object> raw,String side,long revision,String incarnation) {
        keys(wallet,WALLET_KEYS);require(number(wallet.path("revision"))==revision&&incarnation.equals(text(wallet.path("incarnation"))));
        for(var e:Map.of("id","Id","customerId","UserId","version","Version","usdtAvailable","UsdtAvailable","nexAvailable","NexAvailable","riskHeld","CregisRiskHeld","pendingWithdrawal","PendingWithdraw").entrySet())require(equal(wallet.path(e.getKey()),raw.get(side+e.getValue())));
        require(flag(wallet.path("sandbox"))==0&&flag(wallet.path("deleted"))==0&&flag(raw.get(side+"Sandbox"))==0&&flag(raw.get(side+"IsDeleted"))==0);
    }
    private void validateLedgers(SupportStakingReadFacade.Source s) {
        var p=s.position();String no=text(p.get("positionNo")),status=text(p.get("status"));boolean g7=repurchase(p);String terminal=status.equals("CLAIMED")?"-CLAIM":status.equals("EARLY_WITHDRAWN")?"-EARLY":null;
        require(s.ledgers().size()==(terminal==null?1:2));var suffixes=new HashSet<String>();var ids=new HashSet<Long>();
        for(var l:s.ledgers()) {
            String biz=text(l.get("bizNo"));require(biz.startsWith(no));String suffix=biz.substring(no.length());require((suffix.equals("-OPEN")||suffix.equals(terminal))&&suffixes.add(suffix)&&number(l.get("id"))>0&&ids.add(number(l.get("id")))&&number(l.get("customerId"))==number(p.get("customerId"))&&no.equals(l.get("positionNo"))&&"USDT".equals(l.get("asset"))&&"SUCCESS".equals(l.get("status"))&&flag(l.get("deleted"))==0);
            String type=suffix.equals("-OPEN")?(g7?"WALLET_REINVEST":"STAKING_OPEN"):suffix.equals("-CLAIM")?(g7?"REPURCHASE_CLAIM":"STAKING_CLAIM"):(g7?"REPURCHASE_EARLY_WITHDRAW":"STAKING_EARLY_WITHDRAW");
            BigDecimal amount=money(p.get("amountUsdt"));if(suffix.equals("-CLAIM"))amount=amount.add(money(p.get("estimatedInterestUsdt")));if(suffix.equals("-EARLY"))amount=amount.subtract(SupportStakingReadFacade.penalty(p));
            require(type.equals(l.get("bizType"))&&(suffix.equals("-OPEN")?"OUT":"IN").equals(l.get("direction"))&&money(l.get("amount")).compareTo(amount)==0&&money(l.get("balanceAfter")).signum()>=0&&!time(l.get("updatedAt")).isBefore(time(l.get("createdAt"))));
        }
    }
    private static void validPosition(Map<String,Object> p) {
        require(p.keySet().equals(POSITION_KEYS)&&number(p.get("id"))>0&&number(p.get("customerId"))>0&&number(p.get("productId"))>0&&text(p.get("positionNo")).matches("[^\\s:]{1,96}")&&flag(p.get("deleted"))==0&&flag(p.get("userSandbox"))==0&&flag(p.get("userDeleted"))==0);
        require(Set.of("USDT_30D","USDT_90D","USDT_180D","USDT_365D","REPURCHASE_90D").contains(text(p.get("productCode")))&&money(p.get("amountUsdt")).signum()>0&&money(p.get("estimatedInterestUsdt")).signum()>=0&&money(p.get("apyBps")).signum()>=0&&money(p.get("earlyPenaltyBps")).signum()>=0&&money(p.get("earlyPenaltyBps")).compareTo(new BigDecimal("10000"))<=0);
        long days=number(p.get("termDays"));require(days>0&&days<=3650&&time(p.get("unlockAt")).equals(time(p.get("lockedAt")).plusDays(days))&&!time(p.get("updatedAt")).isBefore(time(p.get("createdAt"))));
        String status=text(p.get("status"));
        switch(status) {
            case "ACTIVE","MATURE_UNCLAIMED": require(p.get("claimedAt")==null&&p.get("earlyWithdrawnAt")==null);break;
            case "CLAIMED":require(p.get("earlyWithdrawnAt")==null&&!time(p.get("claimedAt")).isBefore(time(p.get("unlockAt"))));break;
            case "EARLY_WITHDRAWN":require(p.get("claimedAt")==null&&!time(p.get("earlyWithdrawnAt")).isBefore(time(p.get("lockedAt")))&&time(p.get("earlyWithdrawnAt")).isBefore(time(p.get("unlockAt"))));break;
            default:throw failure();
        }
    }
    private static boolean repurchase(Map<String,Object> p){return "REPURCHASE_90D".equals(p.get("productCode"));}
    private static Cash cash(Map<String,Object> raw,String side){return new Cash(money(raw.get(side+"UsdtAvailable")),money(raw.get(side+"NexAvailable")),money(raw.get(side+"PendingWithdraw")),money(raw.get(side+"CregisRiskHeld")));}
    private static SupportStakingReadFacade.Source oneSource(Inputs input,String no){var rows=input.sources().stream().filter(s->no.equals(s.position().get("positionNo"))).toList();require(rows.size()==1);return rows.get(0);}
    private static Map<String,Object> oneOperation(Inputs input,String key){var rows=input.operations().stream().filter(o->key.equals(o.get("operationKey"))).toList();require(rows.size()==1);return rows.get(0);}
    private Map<String,Object> map(JsonNode node){require(node.isObject());var out=new LinkedHashMap<String,Object>();node.fields().forEachRemaining(e->out.put(e.getKey(),value(e.getValue())));return out;}
    private Object value(JsonNode n){if(n.isNull())return null;if(n.isObject())return map(n);if(n.isArray()){var out=new ArrayList<>();n.forEach(x->out.add(value(x)));return out;}if(n.isNumber())return n.decimalValue();if(n.isBoolean())return n.booleanValue();require(n.isTextual());return n.textValue();}
    private boolean equal(JsonNode n,Object v){return codec.canonical(n).equals(codec.canonical(v instanceof JsonNode j?j:json.valueToTree(normalize(v))));}
    private static Object normalize(Object v){if(v instanceof Timestamp t)return t.toLocalDateTime().toString();if(v instanceof LocalDateTime t)return t.toString();if(v instanceof Map<?,?> m){var o=new LinkedHashMap<String,Object>();m.forEach((k,x)->o.put(k.toString(),normalize(x)));return o;}if(v instanceof Collection<?> c)return c.stream().map(SupportFundStakingEvidence::normalize).toList();return v;}
    private static String text(Object v){require(v instanceof String&&!((String)v).isBlank());return (String)v;}
    private static String text(JsonNode n){require(n.isTextual());return text(n.textValue());}
    private static BigDecimal money(Object v){require(v instanceof Number);try{return new BigDecimal(v.toString()).setScale(6,java.math.RoundingMode.UNNECESSARY);}catch(ArithmeticException e){throw failure();}}
    private static BigDecimal money(JsonNode n){require(n.isNumber());return money(n.numberValue());}
    private static long number(Object v){require(v instanceof Number);try{return new BigDecimal(v.toString()).longValueExact();}catch(ArithmeticException e){throw failure();}}
    private static long number(JsonNode n){require(n.isNumber());return number(n.numberValue());}
    private static int flag(Object v){if(v instanceof Boolean b)return b?1:0;long n=number(v);require(n==0||n==1);return (int)n;}
    private static int flag(JsonNode n){if(n.isBoolean())return n.booleanValue()?1:0;require(n.isNumber());return flag(n.numberValue());}
    private static LocalDateTime time(Object v){if(v instanceof LocalDateTime t)return t;if(v instanceof Timestamp t)return t.toLocalDateTime();if(v instanceof String s){try{return LocalDateTime.parse(s);}catch(java.time.format.DateTimeParseException e){throw failure();}}throw failure();}
    private static void keys(JsonNode n,Set<String> expected){require(n.isObject());var actual=new HashSet<String>();n.fieldNames().forEachRemaining(actual::add);require(actual.equals(expected));}
    private static IllegalArgumentException failure(){return new IllegalArgumentException("Unproven staking source");}
    private static void require(boolean value){if(!value)throw failure();}
}
