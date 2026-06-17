package ffdd.opsconsole.shared.rocketmq.monitor;

public record RocketMqBrokerConsumerTarget(String name, String topic, String consumerGroup) {
}
