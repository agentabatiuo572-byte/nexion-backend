package ffdd.opsconsole.content.application;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

/** Explicit alternate fixture namespace; the original S4 defaults remain unchanged. */
@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "support.patch.isolated", havingValue = "true")
class SupportIsolatedRuntime {
    static String database() {
        return "true".equals(System.getenv("SUPPORT_PATCH_ISOLATED")) ? "cs_advisor_patch" : "cs_redesign";
    }
    static int port() {
        return "cs_advisor_patch".equals(database()) ? 18130 : 18129;
    }
    @Bean static BeanFactoryPostProcessor disableScheduledJobs() {
        return factory -> {
            if (!"cs_advisor_patch".equals(database())) throw new IllegalStateException("Patch isolation required");
            ((BeanDefinitionRegistry) factory).removeBeanDefinition(
                    "org.springframework.context.annotation.internalScheduledAnnotationProcessor");
        };
    }
}
