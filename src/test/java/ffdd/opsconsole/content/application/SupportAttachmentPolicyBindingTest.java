package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class SupportAttachmentPolicyBindingTest {
    @Test void dashedAndCamelCasePropertyNamesBindTheSamePolicy() {
        for (String[] names : new String[][] {
                {"allowed-mime-types", "max-bytes", "max-pixels", "ttl-seconds"},
                {"allowedMimeTypes", "maxBytes", "maxPixels", "ttlSeconds"}}) {
            var environment = new StandardEnvironment();
            String prefix = "nexion.support.attachments.";
            environment.getPropertySources().addFirst(new MapPropertySource("attachment-fixture", Map.of(
                    prefix + names[0], "image/png,image/jpeg", prefix + names[1], "1048576",
                    prefix + names[2], "1000000", prefix + names[3], "300")));
            assertConfigured(environment);
        }
    }

    @Test void canonicalEnvironmentNamesRemovePropertyDashes() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
                    "NEXION_SUPPORT_ATTACHMENTS_ALLOWEDMIMETYPES", "image/png,image/jpeg",
                    "NEXION_SUPPORT_ATTACHMENTS_MAXBYTES", "1048576",
                    "NEXION_SUPPORT_ATTACHMENTS_MAXPIXELS", "1000000",
                    "NEXION_SUPPORT_ATTACHMENTS_TTLSECONDS", "300")));
        assertConfigured(environment);
    }

    private void assertConfigured(StandardEnvironment environment) {
        var policy = Binder.get(environment).bind("nexion.support.attachments",
                Bindable.of(SupportAttachmentPolicy.class)).get();
        assertThat(policy.view().available()).isTrue();
        assertThat(policy.getAllowedMimeTypes()).containsExactly("image/png", "image/jpeg");
        assertThat(policy.getMaxBytes()).isEqualTo(1048576L);
        assertThat(policy.getMaxPixels()).isEqualTo(1000000L);
        assertThat(policy.getTtlSeconds()).isEqualTo(300L);
    }
}
