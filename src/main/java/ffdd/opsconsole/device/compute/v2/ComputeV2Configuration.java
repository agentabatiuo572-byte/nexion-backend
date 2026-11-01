package ffdd.opsconsole.device.compute.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.rocketmq.RocketMqAclProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@ConditionalOnProperty(name = "nexion.compute-v2.enabled",havingValue = "true")
public class ComputeV2Configuration {
    @Bean(name="computeV2TaskScheduler")
    ThreadPoolTaskScheduler computeV2TaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("compute-v2-"); scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30); return scheduler;
    }
    @Bean ComputeV2RedisStore computeV2RedisStore(StringRedisTemplate redis,ObjectMapper json,ComputeV2Properties config) {
        return new ComputeV2RedisStore(redis,json,config);
    }
    @Bean(initMethod = "start",destroyMethod = "close")
    ComputeV2MessageLog computeV2MessageLog(ComputeV2Properties config,ComputeV2RedisStore store,RocketMqAclProperties acl) {
        return new ComputeV2MessageLog(config,store,acl);
    }
}
