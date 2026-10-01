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
        if("true".equals(System.getenv("CS_ENHANCE_CORE_ENABLED")))return "cs_enhance_20261001";
        return "true".equals(System.getenv("SUPPORT_PATCH_ISOLATED")) ? "cs_advisor_patch" : "cs_redesign";
    }
    static int port() {
        if("cs_enhance_20261001".equals(database()))return 18141;
        return "cs_advisor_patch".equals(database()) ? 18130 : 18129;
    }
    @Bean static BeanFactoryPostProcessor disableScheduledJobs() {
        return factory -> {
            if (!java.util.Set.of("cs_advisor_patch","cs_enhance_20261001").contains(database())) throw new IllegalStateException("Patch isolation required");
            ((BeanDefinitionRegistry) factory).removeBeanDefinition(
                    "org.springframework.context.annotation.internalScheduledAnnotationProcessor");
        };
    }
}
