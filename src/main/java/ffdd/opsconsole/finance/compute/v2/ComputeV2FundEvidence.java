package ffdd.opsconsole.finance.compute.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import ffdd.opsconsole.device.compute.v2.ComputeV2Event;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade.FundEarnings;
import ffdd.opsconsole.finance.application.SupportFundDeviceEvidence;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.finance.domain.SupportFundReplay.*;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.finance.mapper.ComputeV2SettlementMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import static ffdd.opsconsole.finance.compute.v2.ComputeV2SettlementService.*;

/** Independent owning-source validation for a real batch revision and its immutable per-task children. */
public final class ComputeV2FundEvidence {
    private final ComputeV2SettlementMapper mapper;
    private final ObjectMapper json;
    public ComputeV2FundEvidence(ComputeV2SettlementMapper mapper,ObjectMapper json) {
        this.mapper=mapper;
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS);
    }
    public record Verified(Stamp stamp,List<Point> points,String phase,Set<String> receipts,
            Set<String> tasks,Set<Long> ledgers,Set<Long> earnings) {
        public Verified { points=List.copyOf(points); receipts=Set.copyOf(receipts); tasks=Set.copyOf(tasks);
            ledgers=Set.copyOf(ledgers); earnings=Set.copyOf(earnings); }
    }

    public Verified verify(Map<String,Object> op,JsonNode source,JsonNode proof,List<Event> events,
            SupportFundDeviceEvidence.Inputs input) {
        require(BUSINESS_TYPE.equals(op.get("businessType")) && "CREDIT".equals(op.get("phase"))
                && "nx_compute_v2_settlement".equals(op.get("sourceTable")) && "PRODUCTION".equals(op.get("sourceEnvironment")));
        String id = text(op.get("sourceKey"));
        var settlement = one(mapper.settlement(id),"SETTLEMENT_MISSING");
        String operation = operationKey(id),asset=text(settlement.get("asset"));
        long customer = number(settlement.get("user_id")),opId=number(op.get("id"));
        require(operation.equals(op.get("operationKey")) && operation.equals(settlement.get("operation_key"))
                && Set.of("USDT","UVT").contains(asset));
        keys(source,Set.of("schemaVersion","settlement","allocations","events","originals"));
        keys(proof,Set.of("schemaVersion","walletStages","eventIds"));
        require("COMPUTE_V2_BATCH_1".equals(source.path("schemaVersion").asText())
                && "COMPUTE_V2_BATCH_1".equals(proof.path("schemaVersion").asText()));
        equal(source.path("settlement"),settlement);
        var allocations = mapper.allocations(id);
        require(!allocations.isEmpty() && allocations.size() <= 200); equal(source.path("allocations"),allocations);
        long total = 0; var expected = new ArrayList<Event>(); var completed = new ArrayList<ComputeV2Event>();
        var receipts = new HashSet<String>(); var tasks = new HashSet<String>();
        var ledgerIds = new HashSet<Long>(); var earningIds = new HashSet<Long>();
        for (var a : allocations) {
            String eventId = text(a.get("event_id"));
            var row = one(mapper.event(eventId),"EVENT_MISSING");
            var event = decode(row.get("payload").toString());
            require(event.kind() == ComputeV2Event.Kind.VERIFIED && event.userId() == customer
                    && eventId.equals(event.eventId()) && sha(encode(event)).equals(row.get("payload_sha256"))
                    && event.taskNo().equals(a.get("task_no")) && event.receiptNo().equals(a.get("receipt_no"))
                    && event.deviceId() == number(a.get("user_device_id")) && customer == number(a.get("user_id"))
                    && event.task().walletId() == number(settlement.get("wallet_id"))
                    && event.task().walletIncarnation().equals(settlement.get("wallet_incarnation"))
                    && asset.equals(a.get("asset")) && allocationId(eventId,asset).equals(a.get("allocation_id"))
                    && amount(event,asset) == number(a.get("amount_micros")) && receipts.add(event.receiptNo()) && tasks.add(event.taskNo()));
            require(utc(event.evidence().completedAt()).equals(dateTime(a.get("completed_at_utc")))
                    && settlement.get("posted_at_utc").equals(a.get("posted_at_utc")));
            if (asset.equals("UVT")) require(event.evidence().dailyEntitlementKey().equals(a.get("daily_entitlement_key")));
            else require(a.get("daily_entitlement_key") == null);
            total = Math.addExact(total,number(a.get("amount_micros")));
            if (number(a.get("amount_micros")) > 0) expected.add(new Event("COMPUTE-V2-ALLOCATION:"+a.get("allocation_id"),Action.EARNINGS,null,
                    new Account(customer,asset,"available"),null,money(number(a.get("amount_micros"))),null));
            completed.add(event);
        }
        completed.sort(Comparator.comparing(ComputeV2Event::eventId));
        expected.sort(Comparator.comparing(e -> completed.stream().filter(c -> ("COMPUTE-V2-ALLOCATION:"+allocationId(c.eventId(),asset)).equals(e.id())).findFirst().orElseThrow().eventId()));
        require(total == number(settlement.get("total_micros")) && expected.equals(events));
        equal(source.path("events"),completed); equal(proof.path("eventIds"),events.stream().map(Event::id).toList());
        var originals = originals(completed,asset); equal(source.path("originals"),originals);
        var stages = proof.path("walletStages"); require(stages.isArray() && stages.size() == 2);
        long beforeRevision = number(settlement.get("before_revision")),afterRevision=number(settlement.get("after_revision"));
        String incarnation=text(settlement.get("wallet_incarnation"));
        var parties=input.parties().stream().filter(p -> number(p.get("operationId")) == opId).toList();
        require(parties.size() == 1 && number(parties.get(0).get("customerId")) == customer);
        var legs=input.legs().stream().filter(l -> number(l.get("operationId")) == opId).toList();
        require(legs.size() == (total > 0 ? 1 : 0) && afterRevision == beforeRevision + (total > 0 ? 1 : 0));
        Cash before=cash(stages.get(0)),after=cash(stages.get(1));
        require(before.pending().compareTo(after.pending()) == 0 && before.risk().compareTo(after.risk()) == 0
                && after.usdt().subtract(before.usdt()).compareTo(asset.equals("USDT")?money(total):BigDecimal.ZERO) == 0
                && after.nex().subtract(before.nex()).compareTo(asset.equals("UVT")?money(total):BigDecimal.ZERO) == 0);
        for (String field:List.of("id","customerId","incarnation","sandbox","deleted","createdAt")) require(stages.get(0).path(field).equals(stages.get(1).path(field)));
        require(stages.get(0).path("id").longValue() == number(settlement.get("wallet_id"))
                && stages.get(0).path("customerId").longValue() == customer && incarnation.equals(stages.get(0).path("incarnation").asText())
                && stages.get(0).path("revision").longValue() == beforeRevision && stages.get(1).path("revision").longValue() == afterRevision
                && stages.get(1).path("version").longValue() == stages.get(0).path("version").longValue() + (total > 0 ? 1 : 0));
        var points=new ArrayList<Point>();
        if (total > 0) {
            var leg=legs.get(0); require(incarnation.equals(leg.get("incarnation")) && number(leg.get("revision")) == afterRevision);
            var raw=one(input.raw().stream().filter(r -> incarnation.equals(r.get("incarnation")) && number(r.get("revision")) == afterRevision).toList(),"RAW_MISSING");
            require("CHANGE".equals(raw.get("observation")));
            wallet(stages.get(0),raw,"old",incarnation,beforeRevision); wallet(stages.get(1),raw,"new",incarnation,afterRevision);
            points.add(new Point(incarnation,afterRevision,customer,before,after,events.stream().map(Event::id).toList()));
        } else require(stages.get(0).equals(stages.get(1)));
        for (var event:completed) validateOriginals(event,asset,after,originals,input.sources(),ledgerIds,earningIds);
        LocalDateTime posted=dateTime(settlement.get("posted_at_utc"));
        return new Verified(new Stamp(id,posted,"UTC",6),points,id+":CREDIT",receipts,tasks,ledgerIds,earningIds);
    }

    public FundEarnings legacySources(Set<Long> component,SupportFundDeviceEvidence.Inputs input,List<Verified> verified) {
        var receipts=new HashSet<String>();var tasks=new HashSet<String>();var ledgers=new HashSet<Long>();var earnings=new HashSet<Long>();var phases=new HashSet<String>();
        for (var v:verified) { require(phases.add(v.phase())); receipts.addAll(v.receipts());tasks.addAll(v.tasks());
            for(long id:v.ledgers())require(ledgers.add(id));for(long id:v.earnings())require(earnings.add(id)); }
        // Independent owning-table enumeration catches omitted operations and orphan allocation evidence.
        var ids=component.stream().sorted().toList();
        var rows=mapper.settlementsForUsers(ids);
        require(rows.stream().map(r -> text(r.get("settlement_id"))+":CREDIT").collect(java.util.stream.Collectors.toSet()).equals(phases));
        var all=input.sources();
        return new FundEarnings(all.receipts().stream().filter(r -> !receipts.contains(r.get("receiptNo"))).toList(),
                all.tasks().stream().filter(r -> !tasks.contains(r.get("taskNo"))).toList(),
                all.ledgers().stream().filter(r -> !ledgers.contains(number(r.get("id")))).toList(),
                all.earningEvents().stream().filter(r -> !earnings.contains(number(r.get("id")))).toList());
    }

    private void validateOriginals(ComputeV2Event e,String asset,Cash after,Map<String,List<Map<String,Object>>> originals,FundEarnings sources,Set<Long> ledgerIds,Set<Long> earningIds) {
        var receipt=one(originals.get("receipts").stream().filter(r -> e.receiptNo().equals(r.get("receipt_no"))).toList(),"RECEIPT_MISSING");
        var task=one(originals.get("tasks").stream().filter(r -> e.taskNo().equals(r.get("task_no"))).toList(),"TASK_MISSING");
        String bizNo=asset.equals("USDT")?e.taskNo():e.evidence().dailyEntitlementKey();
        var ledgerRows=originals.get("ledgers").stream().filter(r -> bizNo.equals(r.get("biz_no"))).toList();
        var ledger=amount(e,asset)>0?one(ledgerRows,"LEDGER_MISSING"):null;
        if (amount(e,asset)==0)require(ledgerRows.isEmpty());
        var earning=one(originals.get("earnings").stream().filter(r -> e.receiptNo().equals(r.get("receipt_no"))).toList(),"EARNING_MISSING");
        require(number(receipt.get("user_id")) == e.userId() && number(receipt.get("user_device_id")) == e.deviceId()
                && e.taskNo().equals(receipt.get("task_no")) && "CREDITED".equals(receipt.get("earning_status"))
                && "PRODUCTION".equals(receipt.get("source_environment")) && number(receipt.get("is_deleted")) == 0
                && e.evidence().proofHash().equals(receipt.get("proof_hash"))
                && money(e.task().rewardUsdtMicros()).compareTo(decimal(receipt.get("reward_usdt"))) == 0
                && money(e.evidence().rewardNexMicros()).compareTo(decimal(receipt.get("reward_nex"))) == 0
                && business(e.evidence().completedAt()).equals(dateTime(receipt.get("completed_at")))
                && "COMPLETED".equals(task.get("status")) && "PRODUCTION".equals(task.get("source_environment"))
                && number(task.get("user_id")) == e.userId() && number(task.get("user_device_id")) == e.deviceId()
                && number(task.get("is_deleted")) == 0 && task.get("completed_at").equals(receipt.get("completed_at"))
                && decimal(task.get("reward_usdt")).compareTo(money(e.task().rewardUsdtMicros())) == 0);
        var value=money(amount(e,asset));
        if(ledger!=null)require("COMPUTE_TASK_REWARD".equals(ledger.get("biz_type")) && "SUCCESS".equals(ledger.get("status"))
                && "IN".equals(ledger.get("direction")) && asset.equals(ledger.get("asset"))
                && number(ledger.get("user_id")) == e.userId() && number(ledger.get("is_deleted")) == 0
                && decimal(ledger.get("amount")).compareTo(value) == 0
                && decimal(ledger.get("balance_after")).compareTo(asset.equals("USDT")?after.usdt():after.nex()) == 0);
        require("POSTED".equals(earning.get("status")) && asset.equals(earning.get("asset"))
                && number(earning.get("user_id")) == e.userId() && number(earning.get("user_device_id")) == e.deviceId()
                && number(earning.get("is_deleted")) == 0 && decimal(earning.get("amount")).compareTo(value) == 0
                && (asset.equals("USDT")?"EARN-"+e.taskNo():"EARN-NEX-"+e.receiptNo()).equals(earning.get("event_no")));
        // Confirm these originals also occur in the independently enumerated legacy owning reader.
        require(sources.receipts().stream().anyMatch(r -> number(r.get("id")) == number(receipt.get("id")))
                && sources.tasks().stream().anyMatch(r -> number(r.get("id")) == number(task.get("id")))
                && (ledger==null || sources.ledgers().stream().anyMatch(r -> number(r.get("id")) == number(ledger.get("id"))))
                && sources.earningEvents().stream().anyMatch(r -> number(r.get("id")) == number(earning.get("id"))));
        require((ledger==null || ledgerIds.add(number(ledger.get("id")))) && earningIds.add(number(earning.get("id"))));
    }

    private Map<String,List<Map<String,Object>>> originals(List<ComputeV2Event> events,String asset) {
        return Map.of("tasks",mapper.originalTasks(events.stream().map(ComputeV2Event::taskNo).toList()),
                "receipts",mapper.originalReceipts(events.stream().map(ComputeV2Event::receiptNo).toList()),
                "ledgers",mapper.originalLedgers(events.stream().map(e -> asset.equals("USDT")?e.taskNo():e.evidence().dailyEntitlementKey()).toList(),asset),
                "earnings",mapper.originalEarnings(events.stream().map(ComputeV2Event::receiptNo).toList(),asset));
    }
    private void wallet(JsonNode stage,Map<String,Object> raw,String side,String incarnation,long revision) {
        keys(stage,Set.of("id","customerId","incarnation","revision","version","usdtAvailable","nexAvailable","cregisRiskHeld","pendingWithdraw","sandbox","deleted","createdAt"));
        require(incarnation.equals(stage.path("incarnation").asText()) && stage.path("revision").longValue() == revision);
        for(var pair:Map.of("id","Id","customerId","UserId","version","Version","usdtAvailable","UsdtAvailable","nexAvailable","NexAvailable","cregisRiskHeld","CregisRiskHeld","pendingWithdraw","PendingWithdraw","createdAt","CreatedAt").entrySet())equal(stage.path(pair.getKey()),raw.get(side+pair.getValue()));
        require(zero(stage.path("sandbox")) && zero(stage.path("deleted")) && number(raw.get(side+"Sandbox")) == 0 && number(raw.get(side+"IsDeleted")) == 0);
    }
    private Cash cash(JsonNode stage) { return new Cash(stage.path("usdtAvailable").decimalValue(),stage.path("nexAvailable").decimalValue(),stage.path("pendingWithdraw").decimalValue(),stage.path("cregisRiskHeld").decimalValue()); }
    private boolean zero(JsonNode n) { return n.isBoolean() ? !n.booleanValue() : n.isNumber() && n.decimalValue().signum() == 0; }
    private void equal(JsonNode actual,Object expected) {
        try { require(canonical(json.readTree(encode(actual))).equals(canonical(json.readTree(encode(expected))))); }
        catch(java.io.IOException ex) { throw new IllegalArgumentException("Invalid batch source",ex); }
    }
    private JsonNode canonical(JsonNode value) {
        if(value.isNumber())return com.fasterxml.jackson.databind.node.DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());
        if(value.isArray()){var array=json.createArrayNode();value.forEach(v->array.add(canonical(v)));return array;}
        if(value.isObject()){var object=json.createObjectNode();var names=new TreeSet<String>();value.fieldNames().forEachRemaining(names::add);names.forEach(n->object.set(n,canonical(value.get(n))));return object;}
        return value;
    }
    private String encode(Object value) { try{return json.writeValueAsString(normalize(value));}catch(Exception e){throw new IllegalArgumentException("Invalid batch source",e);} }
    private ComputeV2Event decode(String value) { try{return json.readValue(value,ComputeV2Event.class);}catch(Exception e){throw new IllegalArgumentException("Invalid batch event",e);} }
    private static BigDecimal decimal(Object value) { return new BigDecimal(value.toString()); }
    private static String text(Object value) { require(value instanceof String && !((String)value).isBlank());return (String)value; }
    private static void keys(JsonNode node,Set<String> expected) { var actual=new HashSet<String>();node.fieldNames().forEachRemaining(actual::add);require(node.isObject() && actual.equals(expected)); }
    private static void require(boolean valid) { if(!valid)throw new IllegalArgumentException("Unproven compute v2 batch source"); }
}
