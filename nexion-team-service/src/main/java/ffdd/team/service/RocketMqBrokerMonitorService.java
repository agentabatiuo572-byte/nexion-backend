package ffdd.team.service;

import ffdd.team.dto.RocketMqBrokerMonitor;
import ffdd.team.dto.RocketMqConsumerClient;
import ffdd.team.dto.RocketMqConsumerConnection;
import ffdd.team.dto.RocketMqQueueOffset;
import ffdd.team.dto.RocketMqSubscription;
import ffdd.team.dto.RocketMqTopicQueueStats;
import ffdd.team.dto.RocketMqTopicStats;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.protocol.admin.ConsumeStats;
import org.apache.rocketmq.remoting.protocol.admin.OffsetWrapper;
import org.apache.rocketmq.remoting.protocol.admin.TopicOffset;
import org.apache.rocketmq.remoting.protocol.admin.TopicStatsTable;
import org.apache.rocketmq.remoting.protocol.body.Connection;
import org.apache.rocketmq.remoting.protocol.heartbeat.SubscriptionData;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RocketMqBrokerMonitorService {
    private final boolean enabled;
    private final String nameServer;
    private final String defaultTopic;
    private final String defaultConsumerGroup;

    public RocketMqBrokerMonitorService(
            @Value("${nexion.outbox.rocketmq.enabled:false}") boolean enabled,
            @Value("${nexion.outbox.rocketmq.name-server:127.0.0.1:9876}") String nameServer,
            @Value("${nexion.outbox.rocketmq.order-paid-topic:nexion-order-paid}") String defaultTopic,
            @Value("${nexion.outbox.rocketmq.consumer-group:nexion-team-order-paid}") String defaultConsumerGroup) {
        this.enabled = enabled;
        this.nameServer = nameServer;
        this.defaultTopic = defaultTopic;
        this.defaultConsumerGroup = defaultConsumerGroup;
    }

    public RocketMqBrokerMonitor inspectConsumer(String topic, String consumerGroup, boolean includeDlq) {
        String resolvedTopic = StringUtils.hasText(topic) ? topic : defaultTopic;
        String resolvedConsumerGroup = StringUtils.hasText(consumerGroup) ? consumerGroup : defaultConsumerGroup;

        RocketMqBrokerMonitor monitor = new RocketMqBrokerMonitor();
        monitor.setEnabled(enabled);
        monitor.setNameServer(nameServer);
        monitor.setTopic(resolvedTopic);
        monitor.setConsumerGroup(resolvedConsumerGroup);
        monitor.setDlqTopic(MixAll.getDLQTopic(resolvedConsumerGroup));

        DefaultMQAdminExt admin = new DefaultMQAdminExt("nexion-team-monitor-" + UUID.randomUUID().toString().replace("-", ""));
        admin.setNamesrvAddr(nameServer);
        try {
            admin.start();
            loadConsumeStats(admin, monitor);
            loadConnection(admin, monitor);
            if (includeDlq) {
                loadDlq(admin, monitor);
            }
        } catch (Exception ex) {
            monitor.getErrors().add("RocketMQ admin query failed: " + ex.getMessage());
        } finally {
            admin.shutdown();
        }
        monitor.setOk(monitor.getErrors().isEmpty());
        return monitor;
    }

    private void loadConsumeStats(DefaultMQAdminExt admin, RocketMqBrokerMonitor monitor) {
        try {
            ConsumeStats stats = admin.examineConsumeStats(monitor.getConsumerGroup(), monitor.getTopic());
            monitor.setTotalLag(stats.computeTotalDiff());
            monitor.setConsumeTps(stats.getConsumeTps());
            stats.getOffsetTable().entrySet().stream()
                    .sorted(Comparator
                            .comparing((Map.Entry<MessageQueue, OffsetWrapper> entry) -> entry.getKey().getBrokerName())
                            .thenComparingInt(entry -> entry.getKey().getQueueId()))
                    .map(entry -> mapQueueOffset(entry.getKey(), entry.getValue()))
                    .forEach(monitor.getOffsets()::add);
        } catch (Exception ex) {
            monitor.getErrors().add("Failed to load consumer offsets: " + ex.getMessage());
        }
    }

    private RocketMqQueueOffset mapQueueOffset(MessageQueue queue, OffsetWrapper offset) {
        RocketMqQueueOffset row = new RocketMqQueueOffset();
        row.setTopic(queue.getTopic());
        row.setBrokerName(queue.getBrokerName());
        row.setQueueId(queue.getQueueId());
        row.setBrokerOffset(offset.getBrokerOffset());
        row.setConsumerOffset(offset.getConsumerOffset());
        row.setPullOffset(offset.getPullOffset());
        row.setLag(Math.max(0, offset.getBrokerOffset() - offset.getConsumerOffset()));
        row.setLastTimestamp(offset.getLastTimestamp());
        return row;
    }

    private void loadConnection(DefaultMQAdminExt admin, RocketMqBrokerMonitor monitor) {
        try {
            var connectionInfo = admin.examineConsumerConnectionInfo(monitor.getConsumerGroup());
            RocketMqConsumerConnection connection = new RocketMqConsumerConnection();
            connection.setConsumeType(value(connectionInfo.getConsumeType()));
            connection.setMessageModel(value(connectionInfo.getMessageModel()));
            connection.setConsumeFromWhere(value(connectionInfo.getConsumeFromWhere()));
            connection.setConnectionCount(connectionInfo.getConnectionSet() == null ? 0 : connectionInfo.getConnectionSet().size());

            if (connectionInfo.getConnectionSet() != null) {
                connectionInfo.getConnectionSet().stream()
                        .sorted(Comparator.comparing(Connection::getClientId, Comparator.nullsLast(String::compareTo)))
                        .map(this::mapClient)
                        .forEach(connection.getClients()::add);
            }
            if (connectionInfo.getSubscriptionTable() != null) {
                connectionInfo.getSubscriptionTable().values().stream()
                        .sorted(Comparator.comparing(SubscriptionData::getTopic, Comparator.nullsLast(String::compareTo)))
                        .map(this::mapSubscription)
                        .forEach(connection.getSubscriptions()::add);
            }
            monitor.setConnection(connection);
        } catch (Exception ex) {
            monitor.getWarnings().add("Failed to load consumer connection info: " + ex.getMessage());
        }
    }

    private RocketMqConsumerClient mapClient(Connection source) {
        RocketMqConsumerClient client = new RocketMqConsumerClient();
        client.setClientId(source.getClientId());
        client.setClientAddr(source.getClientAddr());
        client.setLanguage(value(source.getLanguage()));
        client.setVersion(source.getVersion());
        return client;
    }

    private RocketMqSubscription mapSubscription(SubscriptionData source) {
        RocketMqSubscription subscription = new RocketMqSubscription();
        subscription.setTopic(source.getTopic());
        subscription.setSubString(source.getSubString());
        subscription.setExpressionType(source.getExpressionType());
        subscription.setClassFilterMode(source.isClassFilterMode());
        if (source.getTagsSet() != null) {
            source.getTagsSet().stream().sorted().forEach(subscription.getTags()::add);
        }
        return subscription;
    }

    private void loadDlq(DefaultMQAdminExt admin, RocketMqBrokerMonitor monitor) {
        RocketMqTopicStats dlq = new RocketMqTopicStats();
        dlq.setTopic(monitor.getDlqTopic());
        monitor.setDlq(dlq);
        try {
            TopicStatsTable stats = admin.examineTopicStats(monitor.getDlqTopic());
            long total = stats.getOffsetTable().entrySet().stream()
                    .map(entry -> mapTopicQueueStats(entry.getKey(), entry.getValue()))
                    .sorted(Comparator
                            .comparing(RocketMqTopicQueueStats::getBrokerName, Comparator.nullsLast(String::compareTo))
                            .thenComparing(RocketMqTopicQueueStats::getQueueId, Comparator.nullsLast(Integer::compareTo)))
                    .peek(row -> dlq.getQueues().add(row))
                    .mapToLong(row -> row.getMessages() == null ? 0 : row.getMessages())
                    .sum();
            dlq.setAvailable(true);
            dlq.setTotalMessages(total);
            monitor.setDlqMessages(total);
        } catch (Exception ex) {
            dlq.setAvailable(false);
            dlq.setTotalMessages(0L);
            monitor.setDlqMessages(0L);
            monitor.getWarnings().add("DLQ topic is not readable or has no route: " + ex.getMessage());
        }
    }

    private RocketMqTopicQueueStats mapTopicQueueStats(MessageQueue queue, TopicOffset offset) {
        RocketMqTopicQueueStats row = new RocketMqTopicQueueStats();
        row.setTopic(queue.getTopic());
        row.setBrokerName(queue.getBrokerName());
        row.setQueueId(queue.getQueueId());
        row.setMinOffset(offset.getMinOffset());
        row.setMaxOffset(offset.getMaxOffset());
        row.setMessages(Math.max(0, offset.getMaxOffset() - offset.getMinOffset()));
        row.setLastUpdateTimestamp(offset.getLastUpdateTimestamp());
        return row;
    }

    private String value(Object value) {
        return value == null ? null : value.toString();
    }
}
