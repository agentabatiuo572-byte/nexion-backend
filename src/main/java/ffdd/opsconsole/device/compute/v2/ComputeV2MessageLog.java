package ffdd.opsconsole.device.compute.v2;

import ffdd.opsconsole.shared.rocketmq.RocketMqAclHookFactory;
import ffdd.opsconsole.shared.rocketmq.RocketMqAclProperties;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.rocketmq.client.consumer.DefaultLitePullConsumer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;

/** Synchronous RocketMQ acknowledgement. Strict disk/retention guarantees are an explicit deployment option. */
public class ComputeV2MessageLog implements AutoCloseable {
    private final ComputeV2Properties config;
    private final ComputeV2RedisStore store;
    private final DefaultMQProducer producer;
    private final DefaultLitePullConsumer consumer;
    private final DefaultMQAdminExt admin;
    private final AtomicBoolean durable = new AtomicBoolean();

    public ComputeV2MessageLog(ComputeV2Properties config,ComputeV2RedisStore store,RocketMqAclProperties acl) {
        this.config = config; this.store = store;
        var hook = RocketMqAclHookFactory.createOrNull(acl);
        producer = new DefaultMQProducer(config.getProducerGroup(),hook);
        consumer = new DefaultLitePullConsumer(config.getConsumerGroup(),hook);
        admin = hook == null ? new DefaultMQAdminExt() : new DefaultMQAdminExt(hook);
        String instance = UUID.randomUUID().toString();
        producer.setInstanceName("compute-v2-producer-"+instance);
        consumer.setInstanceName("compute-v2-consumer-"+instance);
        admin.setInstanceName("compute-v2-admin-"+instance);
        producer.setNamesrvAddr(config.getNamesrvAddr()); consumer.setNamesrvAddr(config.getNamesrvAddr());
        admin.setNamesrvAddr(config.getNamesrvAddr());
        producer.setClientCallbackExecutorThreads(2); consumer.setClientCallbackExecutorThreads(2); admin.setClientCallbackExecutorThreads(1);
        producer.setVipChannelEnabled(false); consumer.setVipChannelEnabled(false); admin.setVipChannelEnabled(false);
        producer.setRetryTimesWhenSendFailed(0); producer.setRetryAnotherBrokerWhenNotStoreOK(false);
        producer.setMaxMessageSize(64*1024); producer.setSendMsgTimeout(config.getSendTimeoutMillis());
        consumer.setAutoCommit(false); consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setPullBatchSize(Math.min(16,Math.max(1,config.getPullBufferCapacity()/32)));
        consumer.setPullThreadNums(32); consumer.setPollTimeoutMillis(100);
        consumer.setPullThresholdForAll(config.getPullBufferCapacity()); consumer.setPullThresholdForQueue(Math.max(1,config.getPullBufferCapacity()/32));
        consumer.setPullThresholdSizeForQueue(8); consumer.setConsumeMaxSpan(1000);
    }
    public void start() throws Exception {
        config.validate(); store.ready(false);
        try {
            admin.start(); producer.start(); verifyDurability();
            consumer.subscribe(config.getTopic(),"*"); consumer.start();
        } catch (Exception ex) { close(); throw ex; }
    }
    public void verifyDurability() throws Exception {
        durable.set(false);
        var route = admin.examineTopicRouteInfo(config.getTopic());
        if (route.getBrokerDatas() == null || route.getBrokerDatas().isEmpty())
            throw new IllegalStateException("COMPUTE_V2_TOPIC_NOT_PROVISIONED");
        int reads = route.getQueueDatas().stream().mapToInt(q -> q.getReadQueueNums()).sum();
        int writes = route.getQueueDatas().stream().mapToInt(q -> q.getWriteQueueNums()).sum();
        if (reads != 32 || writes != 32) throw new IllegalStateException("COMPUTE_V2_TOPIC_REQUIRES_32_QUEUES");
        if (!config.isStrictDurability()) { durable.set(true); return; }
        for (var broker : route.getBrokerDatas()) {
            String address = broker.getBrokerAddrs().get(0L);
            if (address == null) throw new IllegalStateException("COMPUTE_V2_MASTER_REQUIRED");
            Properties actual = admin.getBrokerConfig(address);
            if (!"SYNC_FLUSH".equals(actual.getProperty("flushDiskType"))
                    || !"false".equalsIgnoreCase(actual.getProperty("cleanFileForciblyEnable"))
                    || Integer.parseInt(actual.getProperty("fileReservedTime","0")) < 168)
                throw new IllegalStateException("COMPUTE_V2_BROKER_DURABILITY_REQUIRED");
        }
        durable.set(true);
    }
    public void send(ComputeV2Event event) {
        if (!durable.get()) throw new IllegalStateException("COMPUTE_V2_BROKER_NOT_VERIFIED");
        if (!config.getDeploymentScope().equals(event.deploymentScope())) throw new IllegalArgumentException("Wrong deployment");
        try {
            byte[] body = store.encode(event).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (body.length > 64*1024) throw new IllegalArgumentException("Compute event too large");
            Message message = new Message(config.getTopic(),event.kind().name(),event.eventId(),body);
            message.setWaitStoreMsgOK(true);
            var result = producer.send(message,(queues,msg,owner) -> {
                var ordered = queues.stream().sorted(Comparator.comparing(org.apache.rocketmq.common.message.MessageQueue::getBrokerName)
                        .thenComparingInt(org.apache.rocketmq.common.message.MessageQueue::getQueueId)).toList();
                return ordered.get(Math.floorMod(Long.hashCode((Long) owner),ordered.size()));
            },event.userId(),config.getSendTimeoutMillis());
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK)
                throw new IllegalStateException("COMPUTE_V2_DURABLE_SEND_UNKNOWN");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("COMPUTE_V2_DURABLE_SEND_UNKNOWN",ex);
        } catch (Exception ex) { throw new IllegalStateException("COMPUTE_V2_DURABLE_SEND_UNKNOWN",ex); }
    }
    public DefaultLitePullConsumer consumer() { return consumer; }
    public DefaultMQProducer producer() { return producer; }
    @Override public void close() {
        durable.set(false);
        consumer.shutdown(); producer.shutdown(); admin.shutdown();
    }
}
