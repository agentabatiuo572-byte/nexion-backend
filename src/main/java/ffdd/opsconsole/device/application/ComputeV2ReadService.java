package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.compute.v2.ComputeV2Event;
import ffdd.opsconsole.device.compute.v2.ComputeV2Runtime;
import ffdd.opsconsole.device.dto.AppTaskAssignmentView;
import ffdd.opsconsole.device.mapper.ComputeV2ReadMapper;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account-owned projection only. Reading never issues, starts, acknowledges or settles work. */
@Service
public class ComputeV2ReadService {
    private static final String SCOPE = "TEST";
    private static final String BATCH_ID = "[a-f0-9]{64}:(?:USDT|UVT)";
    private final AppTaskAssignmentService legacy;
    private final Optional<ComputeV2Runtime> runtime;
    private final Environment environment;
    private final ComputeV2ReadMapper mapper;
    private final ObjectMapper json;

    public ComputeV2ReadService(AppTaskAssignmentService legacy, Optional<ComputeV2Runtime> runtime,
            Environment environment, ComputeV2ReadMapper mapper, ObjectMapper json) {
        this.legacy=legacy; this.runtime=runtime; this.environment=environment; this.mapper=mapper; this.json=json;
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> assignments(Long userId) {
        requireUser(userId);
        if (!environment.getProperty("nexion.compute-v2.enabled",Boolean.class,false) || runtime.isEmpty()
                || !SCOPE.equals(environment.getProperty("nexion.compute-v2.deployment-scope",""))) unavailable();
        Set<Long> enrolled = enrolledDevices();
        if (enrolled.isEmpty()) unavailable();
        // Capture Redis before the first SQL read establishes this transaction's consistent snapshot.
        ComputeV2Runtime.PendingVerifiedSnapshot pendingSnapshot=null;
        RuntimeException pendingFailure=null;
        try { pendingSnapshot=runtime.get().pendingVerifiedSnapshot(userId); }
        catch (RuntimeException ex) { pendingFailure=ex; }
        var result = legacy.assignments(userId);
        if (result.getCode()!=0 || result.getData()==null) throw new BizException(result.getCode(),result.getMessage());
        var original = result.getData();
        if (!original.serverCanonical() || !"server".equals(original.source())
                || !"PRODUCTION".equals(original.sourceEnvironment()) || !"".equals(original.runId())) invalid();
        if (original.devices().stream().noneMatch(device -> enrolled.contains(device.deviceId()))) unavailable();
        if (pendingFailure!=null) throw new BizException(503,"COMPUTE_V2_STATE_UNAVAILABLE");
        Map<Long,ComputeV2Runtime.RuntimeView> views = new LinkedHashMap<>();
        Set<String> taskNos = new LinkedHashSet<>();
        for (var device : original.devices()) {
            if (enrolled.contains(device.deviceId())) {
                ComputeV2Runtime.RuntimeView view;
                try { view=runtime.get().view(userId,device.deviceId()).orElse(null); }
                catch (RuntimeException ex) { throw new BizException(503,"COMPUTE_V2_STATE_UNAVAILABLE"); }
                if (view!=null) {
                    var event=view.event();
                    if (event.userId()!=userId || event.deviceId()!=device.deviceId()
                            || !device.instanceNo().equals(event.instanceNo()) || !SCOPE.equals(event.deploymentScope())) invalid();
                    views.put(device.deviceId(),view);
                    if (event.kind()==ComputeV2Event.Kind.VERIFIED) taskNos.add(event.taskNo());
                }
            }
            for (var history : device.recentTasks()) if (history.taskNo().startsWith("CV2-")) taskNos.add(history.taskNo());
        }
        Map<String,PostedTask> posted=postedTasks(userId,taskNos);
        var recoveringLegacy=new HashSet<String>();
        var recoveringDevices=new HashSet<Long>();
        var ownedDevices=original.devices().stream().collect(java.util.stream.Collectors.toMap(device->device.deviceId(),device->device));
        // serverNow uses the legacy service's business Clock, not the database UTC clock.
        var staleBefore=original.serverNow().minusMinutes(10);
        var legacyRequests=ownedDevices.isEmpty()?List.<ComputeV2ReadMapper.LegacyCompletionRequest>of()
                :mapper.legacyCompletionRequests(userId,original.devices().stream().map(device->device.deviceId()).toList());
        for (var request : legacyRequests) {
            var device=ownedDevices.get(request.deviceId());
            if (device==null || !device.instanceNo().equals(request.instanceNo()) || request.taskNo().startsWith("CV2-")) invalid();
            var task=device.currentTask();
            if ((task==null || (task.taskNo().equals(request.taskNo()) && task.completedAt()==null && task.receiptNo()==null
                    && Set.of("CLAIMED","RUNNING","PAUSED").contains(task.status())))
                    && ("UNKNOWN".equals(request.state()) || ("PROCESSING".equals(request.state())
                    && request.updatedAt()!=null && request.updatedAt().isBefore(staleBefore)))) {
                recoveringLegacy.add(request.taskNo()); recoveringDevices.add(device.deviceId());
            }
        }
        List<Map<String,Object>> devices = new ArrayList<>();
        for (var device : original.devices()) {
            Map<String,Object> current=device.currentTask()==null ? null : legacyTask(device.currentTask(),posted,recoveringLegacy);
            var history=new LinkedHashMap<String,Map<String,Object>>();
            for (var task : device.recentTasks()) history.put(task.taskNo(),legacyTask(task,posted,Set.of()));
            var view=views.get(device.deviceId());
            if (view!=null) {
                var task=runtimeTask(view,posted.get(view.event().taskNo()));
                if ("COMPLETED".equals(task.get("status"))) {
                    history.put(view.event().taskNo(),task);
                    if (current!=null && view.event().taskNo().equals(current.get("taskNo"))) current=null;
                } else {
                    if (current!=null && !view.event().taskNo().equals(current.get("taskNo")))
                        throw new BizException(503,"COMPUTE_V2_TASK_IDENTITY_CONFLICT");
                    current=task;
                }
            }
            var recent=history.values().stream().sorted(Comparator.comparingLong((Map<String,Object> task) -> epoch(task.get("completedAt"))).reversed()).limit(10).toList();
            boolean recovery=recoveringDevices.contains(device.deviceId()) && (current==null || "RECOVERING".equals(current.get("status")));
            devices.add(fields("deviceId",device.deviceId(),"instanceNo",device.instanceNo(),"deviceType",device.deviceType(),
                    "lockUntil",device.lockUntil(),"currentTask",current,"recentTasks",recent,
                    "recoveryPending",recovery,"recoveryReason",recovery?"LEGACY_COMPLETE_OUTCOME_UNKNOWN":null));
        }
        ComputeV2Runtime.PendingSummary pending=unpostedSummary(userId,pendingSnapshot);
        if (pending.pendingUsdtMicros()<0 || pending.pendingNexMicros()<0 || pending.pendingTaskCount()<0
                || pending.pendingNexTaskCount()<0 || pending.pendingNexTaskCount()>pending.pendingTaskCount()) invalid();
        var response=authority();
        response.putAll(fields("protocolVersion",2,"serverNow",original.serverNow(),"devices",devices,"pendingEarned",List.of(
                fields("asset","USDT","amount",money(pending.pendingUsdtMicros()),"taskCount",pending.pendingTaskCount()),
                fields("asset","NEX","amount",money(pending.pendingNexMicros()),"taskCount",pending.pendingNexTaskCount()))));
        return response;
    }

    private ComputeV2Runtime.PendingSummary unpostedSummary(long userId,ComputeV2Runtime.PendingVerifiedSnapshot snapshot) {
        if (snapshot==null || snapshot.summary()==null || snapshot.events()==null || snapshot.events().size()>30_000) invalid();
        var events=new LinkedHashMap<String,ComputeV2Event>();
        long usdt=0,nex=0,nexCount=0;
        try {
            for (var event : snapshot.events()) {
                if (event.userId()!=userId || !SCOPE.equals(event.deploymentScope()) || event.kind()!=ComputeV2Event.Kind.VERIFIED
                        || event.evidence()==null || events.putIfAbsent(event.eventId(),event)!=null) invalid();
                usdt=Math.addExact(usdt,event.task().rewardUsdtMicros());
                nex=Math.addExact(nex,event.evidence().rewardNexMicros());
                if (event.evidence().rewardNexMicros()>0) nexCount++;
            }
            var summary=snapshot.summary();
            if (usdt!=summary.pendingUsdtMicros() || nex!=summary.pendingNexMicros()
                    || events.size()!=summary.pendingTaskCount() || nexCount!=summary.pendingNexTaskCount()) invalid();
            long count=events.size();
            var ids=new ArrayList<>(events.keySet());
            var committedAssets=new HashMap<String,Set<String>>();
            for (int offset=0;offset<ids.size();offset+=200) {
                var page=ids.subList(offset,Math.min(offset+200,ids.size()));
                var rows=mapper.pendingAllocations(userId,SCOPE,page);
                if (rows.size()>page.size()*2) invalid();
                for (var row : rows) {
                    String id=String.valueOf(row.get("event_id")),asset=String.valueOf(row.get("asset"));
                    var event=events.get(id);
                    if (event==null || !page.contains(id) || !event.taskNo().equals(row.get("task_no"))
                            || !event.receiptNo().equals(row.get("receipt_no")) || event.deviceId()!=number(row.get("user_device_id"))
                            || !committedAssets.computeIfAbsent(id,key->new HashSet<>()).add(asset)) invalid();
                    long amount=number(row.get("amount_micros"));
                    if ("USDT".equals(asset)) {
                        if (amount!=event.task().rewardUsdtMicros()) invalid();
                        usdt=Math.subtractExact(usdt,amount); count--;
                    } else if ("UVT".equals(asset)) {
                        if (amount<=0 || amount!=event.evidence().rewardNexMicros()) invalid();
                        nex=Math.subtractExact(nex,amount); nexCount--;
                    } else invalid();
                }
            }
            for (var entry : committedAssets.entrySet()) {
                boolean needsNex=events.get(entry.getKey()).evidence().rewardNexMicros()>0;
                if (!entry.getValue().contains("USDT") || entry.getValue().contains("UVT")!=needsNex) invalid();
            }
            if (usdt<0 || nex<0 || count<0 || nexCount<0 || nexCount>count) invalid();
            return new ComputeV2Runtime.PendingSummary(usdt,nex,count,nexCount);
        } catch (ArithmeticException ex) { throw new BizException(503,"COMPUTE_V2_STATE_INVALID"); }
    }

    @Transactional(readOnly=true)
    public Map<String,Object> settlements(Long userId,String cursor,int limit) {
        requireHistoryScope(userId);
        if (limit<1 || limit>50) throw new BizException(422,"COMPUTE_V2_PAGE_INVALID");
        Object postedAt=null;
        if (cursor!=null) {
            requireBatchId(cursor);
            var boundary=batchRows(userId,cursor);
            if (boundary.size()!=1) throw new BizException(422,"COMPUTE_V2_CURSOR_INVALID");
            postedAt=boundary.get(0).get("posted_at_utc");
        }
        var rows=mapper.batches(userId,SCOPE,postedAt,cursor,limit+1);
        var page=rows.stream().limit(limit).map(this::batch).toList();
        var response=authority();
        response.putAll(fields("records",page,"nextCursor",rows.size()>limit ? page.get(page.size()-1).get("batchNo") : null));
        return response;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> settlement(Long userId,String batchNo) {
        requireHistoryScope(userId); requireBatchId(batchNo);
        var rows=batchRows(userId,batchNo);
        if (rows.isEmpty()) throw new BizException(404,"COMPUTE_V2_SETTLEMENT_NOT_FOUND");
        if (rows.size()!=1) invalid();
        var row=rows.get(0);
        var response=authority(); response.putAll(batch(row));
        var allocations=mapper.allocations(userId,SCOPE,batchNo);
        if (allocations.size()>200 || allocations.size()!=number(row.get("task_count"))) invalid();
        long total=0; var identities=new HashSet<String>(); var items=new ArrayList<Map<String,Object>>();
        for (var allocation : allocations) {
            String taskNo=String.valueOf(allocation.get("task_no"));
            if (!identities.add(taskNo)) invalid();
            long micros=number(allocation.get("amount_micros"));
            total=Math.addExact(total,micros);
            items.add(fields("taskNo",taskNo,"receiptNo",allocation.get("receipt_no"),"deviceId",allocation.get("user_device_id"),
                    "taskType",allocation.get("task_type"),"amount",money(micros),"completedAt",utc(allocation.get("completed_at_utc"))));
        }
        if (total!=number(row.get("total_micros"))) invalid();
        response.put("items",items); return response;
    }

    private List<Map<String,Object>> batchRows(long userId,String batchNo) {
        return mapper.batch(userId,SCOPE,batchNo);
    }

    private Map<String,Object> batch(Map<String,Object> row) {
        long count=number(row.get("task_count"));
        Instant start=utc(row.get("window_start_utc")),end=utc(row.get("window_end_utc"));
        if (count<1 || count>200 || !start.plusSeconds(60).equals(end)) invalid();
        String asset=String.valueOf(row.get("asset"));
        if (!"USDT".equals(asset) && !"UVT".equals(asset)) invalid();
        return fields("batchNo",row.get("settlement_id"),"asset","UVT".equals(asset)?"NEX":asset,"amount",money(number(row.get("total_micros"))),
                "taskCount",count,"status","POSTED","windowStart",start,"windowEnd",end,"postedAt",utc(row.get("posted_at_utc")));
    }

    private record PostedTask(String batchNo,Instant postedAt,ComputeV2Event event) { }
    private Map<String,PostedTask> postedTasks(long userId,Set<String> taskNos) {
        if (taskNos.isEmpty()) return Map.of();
        var rows=mapper.postedTasks(userId,SCOPE,new ArrayList<>(taskNos));
        var result=new HashMap<String,PostedTask>();
        for (var row : rows) {
            try {
                var event=json.readValue(String.valueOf(row.get("payload")),ComputeV2Event.class);
                String taskNo=String.valueOf(row.get("task_no"));
                if (event.userId()!=userId || !SCOPE.equals(event.deploymentScope()) || !event.taskNo().equals(taskNo)
                        || event.kind()!=ComputeV2Event.Kind.VERIFIED || result.containsKey(taskNo)) invalid();
                result.put(taskNo,new PostedTask(String.valueOf(row.get("settlement_id")),utc(row.get("posted_at_utc")),event));
            } catch (JsonProcessingException ex) { throw new BizException(503,"COMPUTE_V2_STATE_INVALID"); }
        }
        return result;
    }

    private Map<String,Object> runtimeTask(ComputeV2Runtime.RuntimeView view,PostedTask posted) {
        var e=view.event(); var task=e.task();
        String status=switch (view.status()==null ? "" : view.status()) {
            case "COMPUTING","RUNNING" -> "RUNNING";
            case "ISSUING","STARTING","QUEUED" -> "QUEUED";
            case "VERIFYING" -> "VERIFYING";
            case "RECOVERING","FAILING" -> "RECOVERING";
            case "COMPLETED" -> "COMPLETED";
            case "FAILED" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            default -> throw new BizException(503,"COMPUTE_V2_STATE_INVALID");
        };
        boolean verified=e.kind()==ComputeV2Event.Kind.VERIFIED;
        if ("COMPLETED".equals(status) && !verified) invalid();
        Instant completed=verified ? e.evidence().completedAt() : null;
        String blockedReason=null;
        if (verified && posted==null) {
            try { blockedReason=runtime.orElseThrow().settlementBlock(e.taskNo(),e.generation()).orElse(null); }
            catch (RuntimeException ex) { throw new BizException(503,"COMPUTE_V2_STATE_UNAVAILABLE"); }
            if (blockedReason!=null && blockedReason.isBlank()) invalid();
        }
        var result=authority();
        result.putAll(fields("protocolVersion",2,"taskNo",e.taskNo(),"deviceId",e.deviceId(),"taskId",task.taskConfigId(),
                "taskName",task.taskName(),"taskClass",task.taskClass(),"model",task.modelName(),"client",task.clientName(),"status",status,
                "rewardUsdt",money(task.rewardUsdtMicros()),"requiredSeconds",task.requiredSeconds(),"queuedAt",task.issuedAt(),
                "startedAt",task.startedAt(),"completableAt",task.startedAt()==null ? null : task.startedAt().plusSeconds(task.requiredSeconds()),
                "completedAt",completed,"actualDurationSec",verified ? BigDecimal.valueOf(Duration.between(task.startedAt(),completed).toMillis(),3) : null,
                "settlementStatus",posted!=null ? "POSTED" : verified ? blockedReason==null ? "PENDING" : "BLOCKED" : "NONE","settlementBatchNo",posted==null ? null : posted.batchNo(),
                "settledAt",posted==null ? null : posted.postedAt(),"receiptNo",posted==null ? null : e.receiptNo(),"configVersion",task.configVersion(),
                "proofNonce",null,"proofExpiresAt",null,"pausedAt",null,"pausedSeconds",0));
        if (blockedReason!=null) result.put("settlementBlockReason",blockedReason);
        return result;
    }

    private Map<String,Object> legacyTask(AppTaskAssignmentView task,Map<String,PostedTask> posted,Set<String> recovering) {
        var saved=posted.get(task.taskNo());
        if (saved!=null) return runtimeTask(new ComputeV2Runtime.RuntimeView(saved.event(),"COMPLETED",null,null,0,0),saved);
        boolean recovery=recovering.contains(task.taskNo());
        var result=authority();
        result.putAll(fields("protocolVersion",2,"taskNo",task.taskNo(),"deviceId",task.deviceId(),"taskId",task.taskId(),"taskName",task.taskName(),
                "taskClass",task.taskClass(),"model",task.model(),"client",task.client(),"status",recovery?"RECOVERING":task.status(),"rewardUsdt",task.rewardUsdt(),
                "requiredSeconds",task.requiredSeconds(),"startedAt",task.startedAt(),"completableAt",recovery?null:task.completableAt(),"completedAt",task.completedAt(),
                "receiptNo",task.receiptNo(),"proofNonce",task.proofNonce(),"proofExpiresAt",task.proofExpiresAt(),"pausedAt",task.pausedAt(),"pausedSeconds",task.pausedSeconds(),
                "queuedAt",null,"actualDurationSec",null,"settlementStatus","COMPLETED".equals(task.status())?"POSTED":"NONE", "settlementBatchNo",null,"settledAt",null,"configVersion",null));
        return result;
    }

    private Set<Long> enrolledDevices() {
        String raw=environment.getProperty("nexion.compute-v2.device-ids","").trim();
        if (raw.isEmpty()) return Set.of();
        var ids=new HashSet<Long>();
        for (String token : raw.split(",",-1)) {
            if (!token.trim().matches("[0-9]{1,19}")) throw new BizException(503,"COMPUTE_V2_CONFIG_INVALID");
            try { long id=Long.parseLong(token.trim()); if (id<=0) invalid(); ids.add(id); }
            catch (NumberFormatException ex) { throw new BizException(503,"COMPUTE_V2_CONFIG_INVALID"); }
        }
        if (ids.size()>6000) throw new BizException(503,"COMPUTE_V2_CONFIG_INVALID");
        return ids;
    }
    private void requireHistoryScope(Long userId) {
        requireUser(userId);
        ProductionDeviceRuntimeGate.requireProduction(environment,"COMPUTE_V2_RUNTIME_UNSUPPORTED");
        if (!SCOPE.equals(environment.getProperty("nexion.compute-v2.deployment-scope",""))) unavailable();
    }
    private static void requireUser(Long userId) { if (userId==null || userId<=0) throw new BizException(403,"USER_SUBJECT_REQUIRED"); }
    private static void requireBatchId(String value) { if (value==null || !value.matches(BATCH_ID)) throw new BizException(422,"COMPUTE_V2_BATCH_INVALID"); }
    private static void unavailable() { throw new BizException(404,"COMPUTE_V2_NOT_AVAILABLE"); }
    private static void invalid() { throw new BizException(503,"COMPUTE_V2_STATE_INVALID"); }
    private static long number(Object value) { if (!(value instanceof Number number) || number.longValue()<0) { invalid(); return 0; } return number.longValue(); }
    private static String money(long micros) { if (micros<0) invalid(); return BigDecimal.valueOf(micros,6).toPlainString(); }
    private static Instant utc(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
        if (value instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC);
        if (value instanceof Instant instant) return instant;
        invalid(); return null;
    }
    private static long epoch(Object value) {
        if (value instanceof Instant instant) return instant.toEpochMilli();
        if (value instanceof LocalDateTime time) return time.atZone(DateTimeFormatConfig.BUSINESS_ZONE).toInstant().toEpochMilli();
        return 0;
    }
    private static LinkedHashMap<String,Object> authority() {
        return fields("source","server","sourceEnvironment","PRODUCTION","runId","","serverCanonical",true,"deploymentScope",SCOPE);
    }
    private static LinkedHashMap<String,Object> fields(Object... values) {
        var result=new LinkedHashMap<String,Object>();
        for (int i=0;i<values.length;i+=2) result.put((String)values[i],values[i+1]);
        return result;
    }
}
