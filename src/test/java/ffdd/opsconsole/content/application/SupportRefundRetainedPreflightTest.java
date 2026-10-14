package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** Read the retained refund evidence before paying Spring startup cost; no business writes. */
@EnabledIfEnvironmentVariable(named="CS_NET_CONTRIBUTION_COMMITTED_ENABLED",matches="true")
class SupportRefundRetainedPreflightTest {
    @Test void retainedRowsMatchOriginalReceiptBeforeSpringStartup() throws Exception {
        var runtime=new SupportNetContributionRefundRuntimeTest(){@Override void saveIntent(){}};
        var builder=Jackson2ObjectMapperBuilder.json();
        new DateTimeFormatConfig().nexionDateTimeJacksonCustomizer().customize(builder);
        runtime.json=builder.build();
        runtime.verifyRetainedOrigin();
        assertThat(runtime.nativeObservations).containsKey("retainedOrigin");
        assertThat(runtime.committedCommands).isEmpty();
    }
}
