package ffdd.opsconsole.finance.compute.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import ffdd.opsconsole.device.compute.v2.ComputeV2Event;
import ffdd.opsconsole.device.compute.v2.ComputeV2Event.Kind;
import ffdd.opsconsole.device.compute.v2.ComputeV2Persistence;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.finance.mapper.ComputeV2SettlementMapper;
import ffdd.opsconsole.finance.mapper.ComputeV2SettlementMapper.WriteTarget;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** One committed checkpoint covers lifecycle, historical originals, allocations and actual fund revisions. */
@Service
public class ComputeV2SettlementService implements ComputeV2Persistence {
    static final String BUSINESS_TYPE = "SUPPORT_COMPUTE_V2_BATCH";
    private final ComputeV2SettlementMapper mapper;
    private final TransactionTemplate transaction;
    private final SupportFundJournalFacade fund;
    private final EventOutboxService outbox;
    private final ObjectMapper json;

    public ComputeV2SettlementService(ComputeV2SettlementMapper mapper, PlatformTransactionManager transactions,
            SupportFundJournalFacade fund, EventOutboxService outbox, ObjectMapper json) {
        this.mapper=mapper;
        transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.fund = fund; this.outbox = outbox;
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }

    @Override
    public PersistenceResult persistBatch(SealedBatch batch) {
        String batchId=batch.batchId(); List<ComputeV2Event> input=batch.events();
        if (batchId == null || batchId.isBlank() || batchId.length() > 192 || input == null
                || input.isEmpty() || input.size() > 200) throw failure("INVALID_BATCH");
        List<ComputeV2Event> events = input.stream().sorted(Comparator.comparing(ComputeV2Event::eventId)).toList();
        long customer = events.get(0).userId();
        if (events.stream().anyMatch(e -> e.userId() != customer)
                || events.stream().map(ComputeV2Event::eventId).distinct().count() != events.size()
                || events.stream().map(ComputeV2Event::deploymentScope).distinct().count() != 1)
            throw failure("MIXED_BATCH_IDENTITY");
        String contentHash = sha(encode(Map.of("windowStart",batch.windowStart(),"windowEnd",batch.windowEnd(),"events",events)));
        // The template returns only after physical COMMIT. Unknown commit outcomes are resolved by this same key.
        return transaction.execute(status -> persist(batch, events, customer, contentHash));
    }

    private PersistenceResult persist(SealedBatch batch, List<ComputeV2Event> events, long customer, String contentHash) {
        String batchId=batch.batchId();
        var user = one(mapper.lockUser(customer), "ACCOUNT_MISSING");
        var existing = checkpoint(batchId);
        if (existing != null) {
            if (!contentHash.equals(existing.get("content_sha256")) || number(existing.get("user_id")) != customer)
                throw failure("BATCH_CONTENT_CHANGED");
            return result(existing, true);
        }
        Instant posted = mapper.nowUtc().toInstant(ZoneOffset.UTC);
        List<ComputeV2Event> fresh = new ArrayList<>();
        var persistedEvents=new HashMap<String,String>();
        for(var row:mapper.lockEvents(events.stream().map(ComputeV2Event::eventId).toList()))
            persistedEvents.put(row.get("event_id").toString(),row.get("payload_sha256").toString());
        for (var event : events) {
            String previous=persistedEvents.get(event.eventId());
            if (previous==null) fresh.add(event);
            else if (!sha(encode(event)).equals(previous))
                throw failure("EVENT_CONTENT_CHANGED");
        }
        validateTaskIdentity(fresh);
        var completed = fresh.stream().filter(e -> e.kind() == Kind.VERIFIED).toList();
        if (!completed.isEmpty()) {
            if (!"ACTIVE".equals(user.get("status")) || number(user.get("sandbox")) != 0 || number(user.get("is_deleted")) != 0)
                throw failure("ACCOUNT_NOT_ACTIVE");
            Map<String,Object> wallet = wallet(customer);
            var devices=new HashMap<Long,Map<String,Object>>();
            var deviceIds=completed.stream().map(ComputeV2Event::deviceId).distinct().sorted().toList();
            for(var device:mapper.lockDevices(deviceIds))devices.put(number(device.get("id")),device);
            for (var event : completed) {
                if (number(wallet.get("id")) != event.task().walletId()
                        || !wallet.get("incarnation").equals(event.task().walletIncarnation()))
                    throw failure("WALLET_IDENTITY_CHANGED");
                // A normal post-completion deactivation is intentionally irrelevant to an already verified earning.
                var device = devices.get(event.deviceId());
                if(device==null)throw failure("DEVICE_MISSING");
                if (number(device.get("user_id")) != customer || !event.instanceNo().equals(device.get("instance_no"))
                        || !"PRODUCTION".equals(device.get("source_environment")) || !"".equals(device.get("run_id"))
                        || number(device.get("is_deleted")) != 0) throw failure("DEVICE_IDENTITY_CHANGED");
            }
            persistOriginals(completed, posted);
            settleCurrency(batch, customer, "USDT", completed, posted);
            var uvt = completed.stream().filter(e -> e.evidence().rewardNexMicros() > 0).toList();
            if (!uvt.isEmpty()) settleCurrency(batch, customer, "UVT", uvt, posted);
            publish(completed, posted);
        }
        var rows = new ArrayList<Object[]>();
        for (var e : fresh) rows.add(new Object[]{e.eventId(), batchId, e.deploymentScope(), e.taskNo(), e.generation(), e.userId(),
                e.deviceId(), e.kind().name(), utc(e.occurredAt()), sha(encode(e)), encode(e)});
        insert(WriteTarget.EVENTS,rows);
        var allocations = new ArrayList<String>();
        var verifiedEvents=events.stream().filter(e->e.kind()==Kind.VERIFIED).toList();
        var taskAllocations=new HashMap<String,List<Map<String,Object>>>();
        if(!verifiedEvents.isEmpty())for(var a:mapper.allocationsForTasks(verifiedEvents.stream().map(ComputeV2Event::taskNo).toList()))
            taskAllocations.computeIfAbsent(a.get("task_no").toString(),ignored->new ArrayList<>()).add(a);
        for (var e : events) if (e.kind() == Kind.VERIFIED) {
            var actual = taskAllocations.getOrDefault(e.taskNo(),List.of());
            int expected = e.evidence().rewardNexMicros() > 0 ? 2 : 1;
            if (actual.size() != expected) throw failure("ORPHAN_VERIFIED_EVENT");
            for (var a : actual) {
                String asset = a.get("asset").toString();
                if (!e.eventId().equals(a.get("event_id")) || number(a.get("amount_micros")) != amount(e, asset))
                    throw failure("ALLOCATION_REPLAY_MISMATCH");
                allocations.add(a.get("allocation_id").toString());
            }
        }
        var eventIds = events.stream().map(ComputeV2Event::eventId).toList();
        insert(WriteTarget.CHECKPOINTS,Collections.singletonList(new Object[]{batchId, customer, contentHash, encode(eventIds), encode(allocations),utc(batch.windowStart()),utc(batch.windowEnd()),utc(posted)}));
        return new PersistenceResult(batchId, eventIds, allocations, posted, false);
    }

    private void validateTaskIdentity(List<ComputeV2Event> events) {
        if(events.isEmpty())return;
        var tasks=events.stream().map(ComputeV2Event::taskNo).distinct().toList();
        var histories=new HashMap<String,List<ComputeV2Event>>();
        for(var row:mapper.taskHistory(tasks)){
            var old=decode(row.get("payload").toString(),ComputeV2Event.class);
            histories.computeIfAbsent(old.taskNo(),ignored->new ArrayList<>()).add(old);
        }
        var prior = new HashMap<String,ComputeV2Event>();
        for (var e : events) {
            for(var old:histories.getOrDefault(e.taskNo(),List.of()))sameTask(old,e);
            var old = prior.putIfAbsent(e.taskNo(), e);
            if (old != null) sameTask(old, e);
        }
    }

    private void sameTask(ComputeV2Event a, ComputeV2Event b) {
        if (a.userId() != b.userId() || a.deviceId() != b.deviceId() || a.generation() != b.generation()
                || !a.instanceNo().equals(b.instanceNo()) || !a.deploymentScope().equals(b.deploymentScope()))
            throw failure("TASK_IDENTITY_CHANGED");
        var x = json.valueToTree(a.task()); var y = json.valueToTree(b.task());
        for (String field : List.of("startedAt", "leaseExpiresAt", "proofExpiresAt")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode)x).remove(field);
            ((com.fasterxml.jackson.databind.node.ObjectNode)y).remove(field);
        }
        if (!x.equals(y) || a.task().startedAt() != null && b.task().startedAt() != null
                && (!a.task().startedAt().equals(b.task().startedAt())
                    || !a.task().leaseExpiresAt().equals(b.task().leaseExpiresAt())
                    || !a.task().proofExpiresAt().equals(b.task().proofExpiresAt())))
            throw failure("TASK_SNAPSHOT_CHANGED");
        if ((a.kind() == Kind.FAILED || a.kind() == Kind.FENCED) && b.kind() == Kind.VERIFIED
                || (b.kind() == Kind.FAILED || b.kind() == Kind.FENCED) && a.kind() == Kind.VERIFIED)
            throw failure("TASK_TERMINAL_CONFLICT");
    }

    private void persistOriginals(List<ComputeV2Event> events, Instant posted) {
        var tasks = new ArrayList<Object[]>(); var receipts = new ArrayList<Object[]>();
        for (var e : events) {
            var t = e.task(); var proof = e.evidence();
            tasks.add(new Object[]{e.taskNo(),e.userId(),e.deviceId(),t.taskClass(),t.taskConfigId(),t.taskName(),t.modelName(),
                    money(t.rewardUsdtMicros()),t.requiredSeconds(),t.taskLockMinutes(),business(t.proofExpiresAt()),business(proof.completedAt()),
                    "PRODUCTION",t.clientName(),"COMPLETED",business(t.startedAt()),business(t.startedAt()),business(t.leaseExpiresAt()),
                    1,3,business(proof.completedAt()),business(t.issuedAt()),business(posted),0});
            receipts.add(new Object[]{e.userId(),e.deviceId(),e.taskNo(),e.receiptNo(),t.taskClass(),t.clientName(),
                    money(t.rewardUsdtMicros()),money(proof.rewardNexMicros()),"CREDITED","PRODUCTION",proof.proofHash(),
                    business(proof.completedAt()),business(posted),business(posted),0});
        }
        insert(WriteTarget.TASKS,tasks);
        insert(WriteTarget.RECEIPTS,receipts);
    }

    private void settleCurrency(SealedBatch sealed, long customer, String asset, List<ComputeV2Event> events, Instant posted) {
        String batch=sealed.batchId();
        long total = 0;
        for (var e : events) total = Math.addExact(total, amount(e, asset));
        String settlementId = sha(batch) + ":" + asset;
        String operationKey = operationKey(settlementId);
        var binding = fund.begin(List.of(customer));
        var before = wallet(customer);
        if (total > 0) {
            if (mapper.creditWallet(number(before.get("id")),customer,before.get("incarnation").toString(),number(before.get("revision")),asset,money(total),business(posted)) != 1)
                throw failure("WALLET_UPDATE_CONFLICT");
        }
        var after = wallet(customer);
        long expected = total > 0 ? 1 : 0;
        if (number(after.get("revision")) != number(before.get("revision")) + expected
                || number(after.get("version")) != number(before.get("version")) + expected
                || !before.get("incarnation").equals(after.get("incarnation"))) throw failure("WALLET_JOURNAL_NOT_CAPTURED");
        insert(WriteTarget.SETTLEMENTS,Collections.singletonList(new Object[]{settlementId,batch,customer,asset,total,before.get("id"),before.get("incarnation"),before.get("revision"),after.get("revision"),operationKey,utc(sealed.windowStart()),utc(sealed.windowEnd()),utc(posted)}));
        var allocations = new ArrayList<Object[]>(); var ledgers = new ArrayList<Object[]>(); var earnings = new ArrayList<Object[]>();
        var fundEvents = new ArrayList<Event>();
        BigDecimal finalBalance = (BigDecimal)after.get(asset.equals("USDT") ? "usdtAvailable" : "nexAvailable");
        for (var e : events) {
            long micros = amount(e, asset); String allocation = allocationId(e.eventId(), asset);
            String daily = asset.equals("UVT") ? e.evidence().dailyEntitlementKey() : null;
            allocations.add(new Object[]{allocation,settlementId,e.eventId(),e.taskNo(),e.receiptNo(),customer,e.deviceId(),asset,micros,daily,utc(e.evidence().completedAt()),utc(posted)});
            // Each child names the one actual final balance; no invented intermediate wallet revision.
            if (micros > 0) ledgers.add(new Object[]{customer,asset.equals("USDT") ? e.taskNo() : daily,"COMPUTE_TASK_REWARD",asset,"IN",money(micros),finalBalance,"SUCCESS","Compute V2 batch " + settlementId,business(posted),business(posted),0});
            earnings.add(new Object[]{asset.equals("USDT") ? "EARN-" + e.taskNo() : "EARN-NEX-" + e.receiptNo(),customer,e.deviceId(),e.receiptNo(),asset,money(micros),"POSTED",business(posted),business(posted),business(posted),0});
            if (micros > 0) fundEvents.add(new Event("COMPUTE-V2-ALLOCATION:" + allocation,Action.EARNINGS,null,new Account(customer,asset,"available"),null,money(micros),null));
        }
        insert(WriteTarget.ALLOCATIONS,allocations);
        insert(WriteTarget.LEDGERS,ledgers);
        insert(WriteTarget.EARNINGS,earnings);
        var source = new LinkedHashMap<String,Object>();
        source.put("schemaVersion", "COMPUTE_V2_BATCH_1"); source.put("settlement", settlement(settlementId));
        source.put("allocations", allocationRows(settlementId)); source.put("events", events);
        source.put("originals", originals(events,asset));
        var proof = Map.of("schemaVersion","COMPUTE_V2_BATCH_1","walletStages",List.of(before,after),
                "eventIds",fundEvents.stream().map(Event::id).toList());
        var receipt = fund.bind(binding,new SupportFundJournalFacade.ReceiptInput(operationKey,BUSINESS_TYPE,"CREDIT",
                "nx_compute_v2_settlement",settlementId,"PRODUCTION",encode(fundEvents),encode(source),encode(proof)));
        if (receipt == null || !operationKey.equals(receipt.operationKey()) || receipt.journalLegs() != expected)
            throw failure("FUND_BINDING_MISMATCH");
    }

    private void publish(List<ComputeV2Event> events, Instant posted) {
        long customer = events.get(0).userId();
        var attribution = one(mapper.attribution(customer),"EVENT_ATTRIBUTION_MISSING");
        for (var e : events) {
            Map<String,Object> payload = Map.of("task_id",e.task().taskConfigId(),"task_no",e.taskNo(),"device_id",e.deviceId(),
                    "receipt_no",e.receiptNo(),"amount_usdt",money(e.task().rewardUsdtMicros()));
            for (String type : List.of("task.completed","earnings.credited")) outbox.publishUserEvent("COMPUTE_TASK",e.taskNo(),type,customer,
                    attribution.get("phase").toString(),Math.toIntExact(number(attribution.get("age"))),attribution.get("cohort").toString(),payload);
        }
    }

    @Override public Optional<PersistenceResult> findCommitted(String batchId) {
        var row = checkpoint(batchId); return row == null ? Optional.empty() : Optional.of(result(row,true));
    }

    @Override public Optional<ComputeV2Event> findIssued(long userId,long deviceId,String requestId) {
        if(userId<=0 || deviceId<=0 || requestId==null || requestId.isBlank() || requestId.length()>192)
            throw failure("INVALID_REQUEST_IDENTITY");
        return retained(mapper.issued(userId,deviceId,requestId)).map(event->{
            if(event.kind()!=Kind.ISSUED || event.userId()!=userId || event.deviceId()!=deviceId || !requestId.equals(event.task().requestId()))
                throw failure("HISTORY_REQUEST_IDENTITY_CHANGED");
            return event;
        });
    }

    @Override public Optional<ComputeV2Event> findLifecycle(String eventId) {
        if(eventId==null || eventId.isBlank() || eventId.length()>192)throw failure("INVALID_EVENT_IDENTITY");
        return retained(mapper.event(eventId)).map(event->{
            if(!eventId.equals(event.eventId()))throw failure("HISTORY_EVENT_IDENTITY_CHANGED");
            return event;
        });
    }

    private Optional<ComputeV2Event> retained(List<Map<String,Object>> rows) {
        if(rows.isEmpty())return Optional.empty();
        var row=one(rows,"AMBIGUOUS_HISTORY_EVENT");
        var event=decode(row.get("payload").toString(),ComputeV2Event.class);
        if(!sha(encode(event)).equals(row.get("payload_sha256")))throw failure("HISTORY_EVENT_CONTENT_CHANGED");
        return Optional.of(event);
    }

    @Override public List<ComputeV2Event> recover(String scope) {
        if (scope == null || scope.isBlank()) throw failure("DEPLOYMENT_SCOPE_REQUIRED");
        return mapper.recover(scope)
                .stream().map(r -> decode(r.get("payload").toString(),ComputeV2Event.class)).toList();
    }

    Map<String,Object> settlement(String id) { return one(mapper.settlement(id),"SETTLEMENT_MISSING"); }
    List<Map<String,Object>> allocationRows(String id) { return mapper.allocations(id); }
    private Map<String,Object> checkpoint(String id) { var rows = mapper.checkpoint(id); return rows.isEmpty() ? null : one(rows,"AMBIGUOUS_CHECKPOINT"); }
    private PersistenceResult result(Map<String,Object> row, boolean replayed) {
        try { return new PersistenceResult(row.get("batch_id").toString(),json.readValue(row.get("event_ids").toString(),new TypeReference<List<String>>(){}),
                json.readValue(row.get("allocation_ids").toString(),new TypeReference<List<String>>(){}),dateTime(row.get("posted_at_utc")).toInstant(ZoneOffset.UTC),replayed); }
        catch (Exception e) { throw new IllegalStateException("COMPUTE_V2_CHECKPOINT_INVALID",e); }
    }
    private Map<String,Object> wallet(long customer) {
        var row = one(mapper.wallet(customer),"WALLET_MISSING");
        if (number(row.get("sandbox")) != 0 || number(row.get("deleted")) != 0) throw failure("WALLET_NOT_PRODUCTION");
        return row;
    }
    Map<String,Object> originals(List<ComputeV2Event> events, String asset) {
        var taskNos=events.stream().map(ComputeV2Event::taskNo).toList();
        var receiptNos=events.stream().map(ComputeV2Event::receiptNo).toList();
        var bizNos=events.stream().map(e->asset.equals("USDT")?e.taskNo():e.evidence().dailyEntitlementKey()).toList();
        return Map.of("tasks",mapper.originalTasks(taskNos),"receipts",mapper.originalReceipts(receiptNos),
                "ledgers",mapper.originalLedgers(bizNos,asset),"earnings",mapper.originalEarnings(receiptNos,asset));
    }
    private void insert(WriteTarget target,List<Object[]> rows) {
        if (rows.isEmpty()) return;
        var bound=new ArrayList<Map<String,Object>>();
        for(var row:rows){var values=new HashMap<String,Object>();for(int i=0;i<row.length;i++)values.put("c"+i,row[i]);bound.add(values);}
        if (mapper.insertRows(target,bound) != rows.size())
            throw failure("BULK_INSERT_MISMATCH");
    }
    String encode(Object value) {
        try { return json.writeValueAsString(normalize(value)); }
        catch (Exception e) { throw new IllegalStateException("COMPUTE_V2_SERIALIZATION_FAILED",e); }
    }
    static Object normalize(Object value) {
        if (value instanceof Timestamp t) return t.toLocalDateTime().toString();
        if (value instanceof LocalDateTime t) return t.toString();
        if (value instanceof Map<?,?> m) { var result = new TreeMap<String,Object>(); m.forEach((k,v) -> result.put(k.toString(),normalize(v))); return result; }
        if (value instanceof Collection<?> c) return c.stream().map(ComputeV2SettlementService::normalize).toList();
        return value;
    }
    private <T> T decode(String value,Class<T> type) {
        try { return json.readValue(value,type); }
        catch (Exception e) { throw new IllegalStateException("COMPUTE_V2_EVENT_INVALID",e); }
    }
    static long amount(ComputeV2Event e,String asset) { return asset.equals("USDT") ? e.task().rewardUsdtMicros() : e.evidence().rewardNexMicros(); }
    static BigDecimal money(long micros) { return BigDecimal.valueOf(micros,6); }
    static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC); }
    static LocalDateTime business(Instant instant) { return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS),DateTimeFormatConfig.BUSINESS_ZONE); }
    static LocalDateTime dateTime(Object value) { if(value instanceof Timestamp t)return t.toLocalDateTime();if(value instanceof LocalDateTime t)return t;throw failure("DATETIME_REQUIRED"); }
    static long number(Object value) { if (value instanceof Boolean b) return b ? 1 : 0; if (!(value instanceof Number)) throw failure("NUMBER_REQUIRED"); return new BigDecimal(value.toString()).longValueExact(); }
    static Map<String,Object> one(List<Map<String,Object>> rows,String error) { if (rows.size() != 1) throw failure(error); return rows.get(0); }
    static String allocationId(String eventId,String asset) { return sha(eventId + ":" + asset); }
    static String operationKey(String settlement) { return "COMPUTE-V2:" + settlement; }
    static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static IllegalStateException failure(String code) { return new IllegalStateException("COMPUTE_V2_" + code); }
}
