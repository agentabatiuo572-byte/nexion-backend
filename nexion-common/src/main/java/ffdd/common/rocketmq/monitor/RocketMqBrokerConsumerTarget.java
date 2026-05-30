package ffdd.common.rocketmq.monitor;

public record RocketMqBrokerConsumerTarget(String name, String topic, String consumerGroup) {
}
