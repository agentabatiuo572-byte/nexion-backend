package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade.FundEarnings;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.finance.domain.SupportFundReplay.*;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Device originals only; receipt signatures and complete journal closure remain Reader responsibilities. */
@Service @RequiredArgsConstructor
public class SupportFundDeviceEvidence {
    private final SupportFundJournalBindingService codec;
    private final ObjectMapper json;
    public record Inputs(FundEarnings sources,List<Map<String,Object>> raw,List<Map<String,Object>> operations,
        List<Map<String,Object>> parties,List<Map<String,Object>> legs,Map<String,Integer> precisions) {}
    public record Verified(Stamp stamp,List<Point> points,String coveredPhase){public Verified{points=List.copyOf(points);}}
    public Verified verify(Map<String,Object> op,JsonNode source,JsonNode proof,List<Event> events,Inputs input){
        require("SUPPORT_DEVICE_EARNING".equals(op.get("businessType"))&&"CREDIT".equals(op.get("phase"))&&"PRODUCTION".equals(op.get("sourceEnvironment"))&&"nx_compute_receipt".equals(op.get("sourceTable")));
        String no=text(op.get("sourceKey"));long id=number(op.get("id"));require(id>0&&key(no).equals(op.get("operationKey"))&&number(one(input.operations(),"operationKey",key(no)).get("id"))==id);
        keys(source,Set.of("schemaVersion","receipt","tasks","ledgers","earningEvents"));keys(proof,Set.of("schemaVersion","walletStages","eventIds"));require("SUPPORT_DEVICE_EARNING_V1".equals(text(source.path("schemaVersion")))&&"SUPPORT_DEVICE_EARNING_V1".equals(text(proof.path("schemaVersion"))));
        require(equal(codec.read(text(op.get("sourceSnapshotJson"))),source)&&equal(codec.read(text(op.get("sourceProofJson"))),proof));
        var originals=source(input.sources(),no);require(equal(source.path("receipt"),originals.receipt)&&equal(source.path("tasks"),List.of(originals.task))&&equal(source.path("ledgers"),originals.ledgers)&&equal(source.path("earningEvents"),originals.earnings));
        var completed=time(originals.receipt.get("completedAt"));stored(time(originals.task.get("completedAt")),completed,"nx_compute_task.completed_at",input);stored(time(originals.task.get("proofConsumedAt")),completed,"nx_compute_task.proof_consumed_at",input);
        for(var l:originals.ledgers)stored(time(l.get("createdAt")),completed,"nx_wallet_ledger.created_at",input);for(var e:originals.earnings){stored(time(e.get("walletPostedAt")),completed,"nx_earning_event.wallet_posted_at",input);stored(time(e.get("createdAt")),completed,"nx_earning_event.created_at",input);}
        var expected=originals.events;require(expected.equals(events)&&equal(codec.read(text(op.get("orderedEventsJson"))),json.valueToTree(events))&&equal(proof.path("eventIds"),events.stream().map(Event::id).toList()));
        long customer=number(originals.receipt.get("customerId"));var parties=input.parties().stream().filter(p->number(p.get("operationId"))==id).toList();require(parties.size()==1&&number(parties.get(0).get("customerId"))==customer);
        var legs=new ArrayList<>(input.legs().stream().filter(l->number(l.get("operationId"))==id).toList());require(legs.size()==(money(originals.receipt.get("rewardNex")).signum()>0?2:1));legs.sort(Comparator.comparingLong(l->number(l.get("revision"))));
        var stages=proof.path("walletStages");require(stages.isArray()&&stages.size()==legs.size()+1);var points=new ArrayList<Point>();String incarnation=null;long prior=-1;
        for(int i=0;i<legs.size();i++){
            var leg=legs.get(i);String inc=text(leg.get("incarnation"));long revision=number(leg.get("revision"));require(inc.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")&&revision>0&&(i==0||inc.equals(incarnation)&&revision==prior+1));incarnation=inc;prior=revision;
            var raws=input.raw().stream().filter(r->inc.equals(r.get("incarnation"))&&number(r.get("revision"))==revision).toList();require(raws.size()==1);var raw=raws.get(0);require("CHANGE".equals(raw.get("observation"))&&number(raw.get("oldUserId"))==customer&&number(raw.get("newUserId"))==customer&&number(raw.get("oldId"))>0&&number(raw.get("oldId"))==number(raw.get("newId"))&&number(raw.get("newVersion"))==number(raw.get("oldVersion"))+1&&time(raw.get("oldCreatedAt")).equals(time(raw.get("newCreatedAt"))));
            wallet(stages.get(i),raw,"old",inc,revision-1);wallet(stages.get(i+1),raw,"new",inc,revision);
            Cash before=cash(raw,"old"),after=cash(raw,"new");String asset=i==0?"USDT":"UVT";var amount=money(originals.receipt.get(i==0?"rewardUsdt":"rewardNex"));require(before.pending().compareTo(after.pending())==0&&before.risk().compareTo(after.risk())==0);
            require(after.usdt().subtract(before.usdt()).compareTo(asset.equals("USDT")?amount:BigDecimal.ZERO)==0&&after.nex().subtract(before.nex()).compareTo(asset.equals("UVT")?amount:BigDecimal.ZERO)==0);
            var ledger=originals.ledgers.stream().filter(l->asset.equals(l.get("asset"))).findFirst().orElseThrow();require(money(ledger.get("balanceAfter")).compareTo(asset.equals("USDT")?after.usdt():after.nex())==0);
            points.add(new Point(inc,revision,customer,before,after,amount.signum()==0?List.of():List.of(key(no)+":"+("USDT".equals(asset)?"USDT":"NEX"))));
        }
        Integer precision=input.precisions().get("nx_compute_receipt.completed_at");require(precision!=null);return new Verified(new Stamp(no,time(originals.receipt.get("completedAt")),DateTimeFormatConfig.BUSINESS_ZONE.getId(),precision),points,no+":CREDIT");
    }
    public void verifyCoverage(Set<Long> component,Inputs input,Set<String> covered){
        var expected=new HashSet<String>();var receipts=new HashSet<String>();var taskNos=new HashSet<String>();var ledgerIds=new HashSet<Long>();var earningIds=new HashSet<Long>();
        for(var r:input.sources().receipts())if(component.contains(number(r.get("customerId")))){String no=text(r.get("receiptNo"));require(receipts.add(no)&&expected.add(no+":CREDIT"));var original=source(input.sources(),no);require(taskNos.add(text(original.task.get("taskNo"))));for(var l:original.ledgers)require(ledgerIds.add(number(l.get("id"))));for(var e:original.earnings)require(earningIds.add(number(e.get("id"))));var op=one(input.operations(),"operationKey",key(no));verify(op,codec.read(text(op.get("sourceSnapshotJson"))),codec.read(text(op.get("sourceProofJson"))),original.events,input);}
        for(var t:input.sources().tasks())if(component.contains(number(t.get("customerId")))&&"COMPLETED".equals(t.get("status")))require(taskNos.contains(text(t.get("taskNo"))));
        for(var l:input.sources().ledgers())if(component.contains(number(l.get("customerId"))))require(ledgerIds.contains(number(l.get("id"))));
        for(var e:input.sources().earningEvents())if(component.contains(number(e.get("customerId"))))require(earningIds.contains(number(e.get("id"))));
        var opKeys=new HashSet<String>();for(var op:input.operations())if("SUPPORT_DEVICE_EARNING".equals(op.get("businessType"))){long id=number(op.get("id"));if(input.parties().stream().anyMatch(p->number(p.get("operationId"))==id&&component.contains(number(p.get("customerId")))))require(opKeys.add(text(op.get("sourceKey")))&&receipts.contains(text(op.get("sourceKey")))&&"CREDIT".equals(op.get("phase")));}
        require(expected.equals(covered));
    }
    private record Original(Map<String,Object> receipt,Map<String,Object> task,List<Map<String,Object>> ledgers,List<Map<String,Object>> earnings,List<Event> events){}
    private Original source(FundEarnings sources,String no){
        var r=one(sources.receipts(),"receiptNo",no);long customer=number(r.get("customerId")),device=number(r.get("deviceId"));require(number(r.get("id"))>0&&customer>0&&device>0&&zero(r.get("deleted"))&&zero(r.get("userDeleted"))&&zero(r.get("userSandbox"))&&"PRODUCTION".equals(r.get("sourceEnvironment"))&&"CREDITED".equals(r.get("earningStatus"))&&text(r.get("proofHash")).matches("[A-Fa-f0-9]{64,128}"));
        String taskNo=text(r.get("taskNo"));var t=one(sources.tasks(),"taskNo",taskNo);require(number(t.get("id"))>0&&number(t.get("customerId"))==customer&&number(t.get("deviceId"))==device&&zero(t.get("deleted"))&&"PRODUCTION".equals(t.get("sourceEnvironment"))&&"COMPLETED".equals(t.get("status"))&&text(t.get("clientName")).equals(text(r.get("clientName")))&&time(t.get("completedAt")).equals(time(r.get("completedAt")))&&!time(t.get("completedAt")).isBefore(time(t.get("startedAt")))&&close(time(t.get("proofConsumedAt")),time(r.get("completedAt"))));
        require(text(t.get("taskType")).equals(text(r.get("taskType"))));
        var usd=money(r.get("rewardUsdt"));var nex=money(r.get("rewardNex"));require(usd.signum()>=0&&nex.signum()>=0&&usd.compareTo(money(t.get("rewardUsdt")))==0);String daily="CLOUD_SHARE_DAILY:"+device+":"+time(r.get("completedAt")).atZone(DateTimeFormatConfig.BUSINESS_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        var ledgers=sources.ledgers().stream().filter(l->taskNo.equals(l.get("bizNo"))||nex.signum()>0&&daily.equals(l.get("bizNo"))).toList();var earnings=sources.earningEvents().stream().filter(e->no.equals(e.get("receiptNo"))).toList();int size=nex.signum()>0?2:1;require(ledgers.size()==size&&earnings.size()==size);var events=new ArrayList<Event>();var seenLedger=new HashSet<Long>();var seenEarning=new HashSet<Long>();
        for(String asset:nex.signum()>0?List.of("USDT","UVT"):List.of("USDT")){
            var amount=asset.equals("USDT")?usd:nex;var l=one(ledgers,"asset",asset);var e=one(earnings,"asset",asset);require(seenLedger.add(number(l.get("id")))&&number(l.get("id"))>0&&seenEarning.add(number(e.get("id")))&&number(e.get("id"))>0);
            require(number(l.get("customerId"))==customer&&"COMPUTE_TASK_REWARD".equals(l.get("bizType"))&&"IN".equals(l.get("direction"))&&"SUCCESS".equals(l.get("status"))&&zero(l.get("deleted"))&&amount.compareTo(money(l.get("amount")))==0&&money(l.get("balanceAfter")).signum()>=0&&(asset.equals("USDT")?taskNo:daily).equals(l.get("bizNo"))&&close(time(l.get("createdAt")),time(r.get("completedAt")))&&!time(l.get("updatedAt")).isBefore(time(l.get("createdAt"))));
            require(number(e.get("customerId"))==customer&&number(e.get("deviceId"))==device&&"POSTED".equals(e.get("status"))&&zero(e.get("deleted"))&&amount.compareTo(money(e.get("amount")))==0&&(asset.equals("USDT")?"EARN-"+taskNo:"EARN-NEX-"+no).equals(e.get("eventNo"))&&close(time(e.get("walletPostedAt")),time(r.get("completedAt")))&&close(time(e.get("createdAt")),time(r.get("completedAt")))&&!time(e.get("updatedAt")).isBefore(time(e.get("createdAt"))));
            if(amount.signum()>0)events.add(new Event(key(no)+":"+("USDT".equals(asset)?"USDT":"NEX"),Action.EARNINGS,null,new Account(customer,asset,"available"),null,amount,null));
        }return new Original(r,t,ledgers,earnings,List.copyOf(events));
    }
    private void wallet(JsonNode w,Map<String,Object> raw,String side,String incarnation,long revision){keys(w,Set.of("id","customerId","incarnation","revision","version","usdtAvailable","nexAvailable","cregisRiskHeld","pendingWithdraw","sandbox","deleted","createdAt"));require(incarnation.equals(text(w.path("incarnation")))&&number(w.path("revision"))==revision);
        for(var pair:Map.of("id","Id","customerId","UserId","version","Version","usdtAvailable","UsdtAvailable","nexAvailable","NexAvailable","cregisRiskHeld","CregisRiskHeld","pendingWithdraw","PendingWithdraw","createdAt","CreatedAt").entrySet())require(equal(w.path(pair.getKey()),raw.get(side+pair.getValue())));require(zero(w.path("sandbox"))&&zero(w.path("deleted"))&&zero(raw.get(side+"Sandbox"))&&zero(raw.get(side+"IsDeleted")));}
    private static Cash cash(Map<String,Object> r,String side){return new Cash(money(r.get(side+"UsdtAvailable")),money(r.get(side+"NexAvailable")),money(r.get(side+"PendingWithdraw")),money(r.get(side+"CregisRiskHeld")));}
    private static Map<String,Object> one(List<Map<String,Object>> rows,String field,String value){var matches=rows.stream().filter(r->value.equals(r.get(field))).toList();require(matches.size()==1);return matches.get(0);}
    private static boolean close(LocalDateTime a,LocalDateTime b){return Duration.between(a,b).abs().compareTo(Duration.ofSeconds(1))<0;}
    private static void stored(LocalDateTime actual,LocalDateTime original,String key,Inputs input){Integer p=input.precisions().get(key);require(p!=null&&p>=0&&p<=6);int unit=(int)Math.pow(10,9-p);require(actual.getNano()%unit==0);LocalDateTime floor=original.withNano(original.getNano()/unit*unit);require(actual.equals(floor)||!original.equals(floor)&&actual.equals(floor.plusNanos(unit)));}
    private boolean equal(JsonNode a,Object b){return codec.canonical(a).equals(codec.canonical(b instanceof JsonNode n?n:json.valueToTree(normalize(b))));}
    private static Object normalize(Object v){if(v instanceof LocalDateTime t)return t.toString();if(v instanceof Timestamp t)return t.toLocalDateTime().toString();if(v instanceof Map<?,?> m){var r=new LinkedHashMap<String,Object>();m.forEach((k,x)->r.put(k.toString(),normalize(x)));return r;}if(v instanceof Collection<?> c)return c.stream().map(SupportFundDeviceEvidence::normalize).toList();return v;}
    private static String key(String no){try{return "DEVICE-EARNING:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(no.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static BigDecimal money(Object v){require(v instanceof Number);var n=new BigDecimal(v.toString());require(n.stripTrailingZeros().scale()<=6);return n;}
    private static long number(Object v){require(v instanceof Number);try{return new BigDecimal(v.toString()).longValueExact();}catch(ArithmeticException e){throw failure();}}
    private static long number(JsonNode v){require(v.isNumber());return number(v.numberValue());}
    private static boolean zero(Object v){if(v instanceof Boolean b)return !b;return number(v)==0;}
    private static boolean zero(JsonNode v){if(v.isBoolean())return !v.booleanValue();return number(v)==0;}
    private static String text(Object v){require(v instanceof String&&!((String)v).isBlank());return (String)v;}
    private static String text(JsonNode v){require(v.isTextual());return text(v.textValue());}
    private static LocalDateTime time(Object v){if(v instanceof Timestamp t)return t.toLocalDateTime();if(v instanceof LocalDateTime t)return t;if(v instanceof String s){try{return LocalDateTime.parse(s);}catch(java.time.format.DateTimeParseException e){try{return LocalDateTime.parse(s,DateTimeFormatConfig.DATE_TIME_FORMATTER);}catch(java.time.format.DateTimeParseException x){throw failure();}}}throw failure();}
    private static void keys(JsonNode v,Set<String> expected){require(v.isObject());var fields=new HashSet<String>();v.fieldNames().forEachRemaining(fields::add);require(fields.equals(expected));}
    private static void require(boolean v){if(!v)throw failure();}
    private static IllegalArgumentException failure(){return new IllegalArgumentException("Unproven device earning source");}
}
