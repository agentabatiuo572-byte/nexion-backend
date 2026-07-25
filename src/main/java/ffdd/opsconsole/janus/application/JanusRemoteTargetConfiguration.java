package ffdd.opsconsole.janus.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JanusRemoteTargetConfiguration {
    @Bean
    JanusRemoteTargetNetworkGuard janusRemoteTargetNetworkGuard() {
        return new DnsJanusRemoteTargetNetworkGuard();
    }
}
