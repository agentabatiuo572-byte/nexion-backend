package ffdd.opsconsole.device.compute.v2;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageQueue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One bounded poller, minute freezer and durable SQL checkpoint. Redis application never commits MQ offsets. */
@Slf4j
@Component
@ConditionalOnProperty(name = "nexion.compute-v2.enabled",havingValue = "true")
public class ComputeV2Checkpoint {
    private final ComputeV2MessageLog messageLog;
    private final ComputeV2RedisStore store;
    private final ComputeV2Persistence persistence;
    private final ComputeV2Properties config;
    private final Clock clock;
    private final Map<MessageQueue,NavigableMap<Long,Pending>> queues = new HashMap<>();
    private final Map<MessageQueue,Long> nextOffsets = new HashMap<>();
    private final Map<MessageQueue,Long> recoveryEnds = new HashMap<>();
    private final Map<String,Long> retryAfter = new HashMap<>();
    private static final Set<String> BLOCKED = Set.of("COMPUTE_V2_ACCOUNT_NOT_ACTIVE","COMPUTE_V2_WALLET_IDENTITY_CHANGED",
            "COMPUTE_V2_DEVICE_IDENTITY_CHANGED","COMPUTE_V2_ACCOUNT_MISSING","COMPUTE_V2_WALLET_MISSING",
            "COMPUTE_V2_DEVICE_MISSING","COMPUTE_V2_WALLET_NOT_PRODUCTION","COMPUTE_V2_ORPHAN_VERIFIED_EVENT",
            "COMPUTE_V2_ALLOCATION_REPLAY_MISMATCH","COMPUTE_V2_FUND_BINDING_MISMATCH","COMPUTE_V2_WALLET_JOURNAL_NOT_CAPTURED");
    private final AtomicLong lastDurabilityCheck = new AtomicLong();
    private final AtomicBoolean bootstrapped = new AtomicBoolean();
    private record Pending(ComputeV2Event event, boolean applied, boolean persisted) {
        String eventId() { return event.eventId(); }
    }

    public ComputeV2Checkpoint(ComputeV2MessageLog messageLog,ComputeV2RedisStore store,
            ComputeV2Persistence persistence,ComputeV2Properties config,Clock clock) {
        this.messageLog = messageLog; this.store = store; this.persistence = persistence; this.config = config; this.clock = clock;
    }
    @PostConstruct public synchronized void initialize() throws Exception {
        recover();
    }
    private void recover() throws Exception {
        bootstrapped.set(false); store.ready(false); queues.clear(); nextOffsets.clear(); recoveryEnds.clear(); retryAfter.clear();
        for (var event : persistence.recover(config.getDeploymentScope())) store.restorePersisted(event);
        var consumer = messageLog.consumer();
        for (var queue : consumer.fetchMessageQueues(config.getTopic()))
            recoveryEnds.put(queue,messageLog.producer().maxOffset(queue));
        for (var queue : consumer.assignment()) {
            Long committed = consumer.committed(queue);
            consumer.seek(queue,committed == null || committed < 0 ? 0 : committed);
        }
        store.execute("initialize",Long.toString(clock.millis()/60000),Long.toString(clock.millis()));
        bootstrapped.set(true);
    }
    @Scheduled(fixedDelay = 100,initialDelay = 100,scheduler="computeV2TaskScheduler")
    public synchronized void tick() {
        try {
            if (!bootstrapped.get() || !store.initialized()) recover();
            if (clock.millis()-lastDurabilityCheck.get() >= 30000) {
                messageLog.verifyDurability(); lastDurabilityCheck.set(clock.millis());
            }
            var consumer = messageLog.consumer(); var assigned = consumer.assignment();
            queues.keySet().retainAll(assigned); nextOffsets.keySet().retainAll(assigned);
            int buffered = queues.values().stream().mapToInt(Map::size).sum();
            for (var offsets : queues.values()) {
                for (var entry : offsets.entrySet()) {
                    var pending = entry.getValue();
                    if (!pending.applied()) {
                        apply(pending.event());
                        entry.setValue(new Pending(pending.event(),true,store.persisted(pending.eventId())));
                    }
                }
            }
            boolean canPoll = buffered+consumer.getPullBatchSize() <= config.getPullBufferCapacity();
            int perQueueCapacity = Math.max(consumer.getPullBatchSize(),config.getPullBufferCapacity()/32);
            Set<MessageQueue> paused = new HashSet<>(),resumed = new HashSet<>();
            for (var queue : assigned) {
                int size = queues.containsKey(queue) ? queues.get(queue).size() : 0;
                if (!canPoll || size+consumer.getPullBatchSize()>perQueueCapacity) paused.add(queue); else resumed.add(queue);
            }
            consumer.pause(paused); consumer.resume(resumed);
            if (canPoll) {
                for (var message : consumer.poll(50)) {
                    MessageQueue queue = new MessageQueue(message.getTopic(),message.getBrokerName(),message.getQueueId());
                    if (!consumer.assignment().contains(queue)) continue;
                    var offsets = queues.computeIfAbsent(queue,q -> new TreeMap<>());
                    if (!nextOffsets.containsKey(queue)) {
                        Long committed = consumer.committed(queue);
                        nextOffsets.put(queue,committed == null || committed < 0 ? message.getQueueOffset() : committed);
                    }
                    if (message.getQueueOffset() < nextOffsets.get(queue)) continue;
                    if (!offsets.containsKey(message.getQueueOffset()) && offsets.size()>=perQueueCapacity) {
                        consumer.pause(Set.of(queue)); consumer.seek(queue,nextOffsets.get(queue)); continue;
                    }
                    ComputeV2Event event = store.decode(new String(message.getBody(),StandardCharsets.UTF_8),ComputeV2Event.class);
                    if (!config.getDeploymentScope().equals(event.deploymentScope()))
                        throw new IllegalStateException("COMPUTE_V2_FOREIGN_SCOPE_MESSAGE");
                    // Remember before applying: an application failure leaves this exact offset uncommittable.
                    offsets.put(message.getQueueOffset(),new Pending(event,false,false));
                    apply(event);
                    offsets.put(message.getQueueOffset(),new Pending(event,true,store.persisted(event.eventId())));
                    recoveryEnds.computeIfPresent(queue,(q,end) -> message.getQueueOffset()+1 >= end ? null : end);
                }
            }
            assigned = consumer.assignment();
            for (var queue : List.copyOf(recoveryEnds.keySet())) {
                Long end = recoveryEnds.get(queue);
                Long committed = consumer.committed(queue);
                if (end != null && (end == 0 || committed != null && committed >= end)) recoveryEnds.remove(queue);
            }
            if (!store.ready() && !assigned.isEmpty() && recoveryEnds.isEmpty()) store.ready(true);
            store.execute("freeze",Long.toString(clock.instant().getEpochSecond()));
            persistFrozen();
            commitContiguous();
            var retry = store.sorted("outgoing-age").stream().findFirst();
            if (retry.isPresent()) {
                var event = store.decode(store.field("outgoing",retry.get()),ComputeV2Event.class);
                messageLog.send(event); store.apply(event,clock.instant());
            }
        } catch (Exception ex) {
            // No offset or money ACK on failure. A stopped checkpoint also stops admission at the backlog bound.
            log.error("compute-v2 checkpoint failed type={}",ex.getClass().getSimpleName());
        }
    }
    /** Cold duplicates use immutable SQL evidence after the bounded posted hot cache expires. */
    private void apply(ComputeV2Event event) {
        if (store.value("seen:"+event.eventId()) == null && store.field("pending",event.eventId()) == null) {
            var committed = persistence.findLifecycle(event.eventId());
            if (committed.isPresent()) {
                if (!store.encode(committed.get()).equals(store.encode(event))) throw new IllegalStateException("COMPUTE_V2_EVENT_PAYLOAD_CONFLICT");
                store.markPersisted(List.of(event));
                if (event.evidence()!=null && event.evidence().rewardNexMicros()>0) store.daily(event.evidence().dailyEntitlementKey(),event.eventId());
                return;
            }
        }
        if (store.field("generation",event.taskNo()) == null) store.restore(event);
        store.apply(event,clock.instant());
    }
    private void persistFrozen() {
        for (String window : store.sorted("frozen-index")) {
            if (retryAfter.getOrDefault(window,0L)>System.nanoTime()) continue;
            try {
            Instant windowEnd = Instant.ofEpochSecond(store.score("frozen-index",window).longValue());
            Map<Long,List<ComputeV2Event>> users = new TreeMap<>();
            store.entries("frozen:"+window).values().forEach(value -> {
                var event = store.decode(value.toString(),ComputeV2Event.class);
                users.computeIfAbsent(event.userId(),x -> new ArrayList<>()).add(event);
            });
            boolean complete = true;
            for (var entry : users.entrySet()) {
                String retryKey = window+":"+entry.getKey();
                if (retryAfter.getOrDefault(retryKey,0L)>System.nanoTime()) { complete = false; continue; }
                List<ComputeV2Event> attempted = List.of();
                try {
                var events = entry.getValue();
                events.sort(Comparator.comparing(ComputeV2Event::occurredAt).thenComparing(ComputeV2Event::eventId));
                for (int start = 0; start < events.size(); start += 200) {
                    var batch = List.copyOf(events.subList(start,Math.min(start+200,events.size())));
                    attempted = batch;
                    String batchId = batchId(config.getDeploymentScope()+":"+windowEnd.getEpochSecond(),batch);
                    var sealed = new ComputeV2Persistence.SealedBatch(batchId,windowEnd.minusSeconds(60),windowEnd,batch);
                    var result = persistence.persistBatch(sealed);
                    Set<String> expected = new HashSet<>(batch.stream().map(ComputeV2Event::eventId).toList());
                    if (!expected.equals(new HashSet<>(result.eventIds())))
                        throw new IllegalStateException("COMPUTE_V2_INCOMPLETE_SQL_ACK");
                    store.markPersisted(batch);
                    for (var queue : queues.values()) queue.replaceAll((offset,pending) ->
                            expected.contains(pending.eventId()) ? new Pending(pending.event(),true,true) : pending);
                }
                retryAfter.remove(retryKey);
                } catch (RuntimeException failure) {
                    complete = false;
                    if (failure instanceof IllegalStateException && BLOCKED.contains(failure.getMessage())) {
                        List<String> args = new ArrayList<>(List.of("block",failure.getMessage()));
                        attempted.stream().filter(e -> e.kind()==ComputeV2Event.Kind.VERIFIED).map(ComputeV2Event::eventId).forEach(args::add);
                        if (args.size()>2) store.execute(args.toArray(String[]::new));
                    }
                    retryLater(retryKey,failure);
                }
            }
            if (complete) { store.delete("frozen:"+window); store.removeSorted("frozen-index",window); retryAfter.remove(window); }
            } catch (RuntimeException failure) { retryLater(window,failure); }
        }
    }
    private void retryLater(String window,RuntimeException failure) {
        if (!retryAfter.containsKey(window)) log.error("compute-v2 account checkpoint held window={} type={}",window,failure.getClass().getSimpleName());
        retryAfter.put(window,System.nanoTime()+1_000_000_000L);
    }
    private void commitContiguous() throws Exception {
        Map<MessageQueue,Long> commits = new HashMap<>();
        Set<MessageQueue> assigned = messageLog.consumer().assignment();
        for (var entry : queues.entrySet()) {
            if (!assigned.contains(entry.getKey())) continue;
            long next = nextOffsets.get(entry.getKey());
            while (entry.getValue().containsKey(next) && entry.getValue().get(next).persisted()) next++;
            if (next > nextOffsets.get(entry.getKey())) commits.put(entry.getKey(),next);
        }
        if (commits.isEmpty()) return;
        messageLog.consumer().commitSync(commits,true);
        commits.forEach((queue,next) -> { nextOffsets.put(queue,next); queues.get(queue).headMap(next,false).clear(); });
    }
    static String batchId(String scope,List<ComputeV2Event> events) {
        String text = scope+"\n"+String.join("\n",events.stream().map(ComputeV2Event::eventId).sorted().toList());
        try { return "CV2B-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
