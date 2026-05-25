package ffdd.earnings.config;

import ffdd.common.rocketmq.RocketMqAclHookFactory;
import ffdd.common.rocketmq.RocketMqAclProperties;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.remoting.RPCHook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "nexion.outbox.rocketmq", name = "enabled", havingValue = "true")
public class OutboxRocketConfig {
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQProducer earningsOutboxRocketProducer(
            @Value("${nexion.outbox.rocketmq.name-server:127.0.0.1:9876}") String nameServer,
            @Value("${nexion.outbox.rocketmq.earnings-producer-group:nexion-earnings-outbox}")
                    String producerGroup,
            RocketMqAclProperties aclProperties) {
        RPCHook rpcHook = RocketMqAclHookFactory.createOrNull(aclProperties);
        DefaultMQProducer producer = rpcHook == null
                ? new DefaultMQProducer(producerGroup)
                : new DefaultMQProducer(producerGroup, rpcHook);
        producer.setNamesrvAddr(nameServer);
        producer.setRetryTimesWhenSendFailed(2);
        producer.setRetryTimesWhenSendAsyncFailed(2);
        return producer;
    }
}
