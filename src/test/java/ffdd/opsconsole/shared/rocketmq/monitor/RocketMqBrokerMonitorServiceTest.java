package ffdd.opsconsole.shared.rocketmq.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.shared.rocketmq.RocketMqAclProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class RocketMqBrokerMonitorServiceTest {
    @Test
    void rejectsBlankTopicOrGroupWithoutOpeningAdminClient() {
        RocketMqBrokerMonitorService service = new RocketMqBrokerMonitorService(
                false, "127.0.0.1:9876", new RocketMqAclProperties());

        RocketMqBrokerMonitor monitor = service.inspectConsumer("bad", " ", "group", true);

        assertThat(monitor.isOk()).isFalse();
        assertThat(monitor.isEnabled()).isFalse();
        assertThat(monitor.getNameServer()).isEqualTo("127.0.0.1:9876");
        assertThat(monitor.getAcl()).isEqualTo("enabled=false");
        assertThat(monitor.getErrors()).containsExactly("topic and consumerGroup are required");
    }

    @Test
    void supportsMultiTargetAggregationWithPerTargetNames() {
        RocketMqBrokerMonitorService service = new RocketMqBrokerMonitorService(
                false, "127.0.0.1:9876", new RocketMqAclProperties());

        List<RocketMqBrokerMonitor> monitors = service.inspectConsumers(List.of(
                new RocketMqBrokerConsumerTarget("one", "", "group-one"),
                new RocketMqBrokerConsumerTarget("two", "topic-two", "")), false);

        assertThat(monitors).hasSize(2);
        assertThat(monitors).extracting(RocketMqBrokerMonitor::getName).containsExactly("one", "two");
        assertThat(monitors).allSatisfy(monitor -> {
            assertThat(monitor.isOk()).isFalse();
            assertThat(monitor.getErrors()).contains("topic and consumerGroup are required");
        });
    }

    @Test
    void exposesOnlyMaskedAclDiagnostics() {
        RocketMqAclProperties acl = new RocketMqAclProperties();
        acl.setEnabled(true);
        acl.setAccessKey("abc123456");
        acl.setSecretKey("should-not-appear");
        acl.setSecurityToken("token-should-not-appear");
        RocketMqBrokerMonitorService service = new RocketMqBrokerMonitorService(true, "127.0.0.1:9876", acl);

        RocketMqBrokerMonitor monitor = service.inspectConsumer("acl", "", "group", false);

        assertThat(monitor.getAcl()).isEqualTo("enabled=true, accessKey=abc***, securityToken=true");
        assertThat(monitor.toString()).doesNotContain("should-not-appear", "token-should-not-appear");
    }
}
