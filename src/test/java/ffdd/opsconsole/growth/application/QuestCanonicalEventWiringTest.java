package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper;
import ffdd.opsconsole.growth.mapper.QuestCanonicalEventBindingMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class QuestCanonicalEventWiringTest {
    @Test
    void springSelectsTheExplicitPrimaryConstructorsWhenLegacyTestConstructorsAlsoExist() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(QuestCanonicalEventBindingMapper.class,
                    () -> mock(QuestCanonicalEventBindingMapper.class));
            context.registerBean(DayOneInstanceMapper.class, () -> mock(DayOneInstanceMapper.class));
            context.registerBean(QuestCompletionFactConsumer.class, () -> mock(QuestCompletionFactConsumer.class));
            context.registerBean(EventConsumerDeliveryService.class, () -> mock(EventConsumerDeliveryService.class));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(QuestCanonicalEventProjector.class, QuestCanonicalEventConsumer.class);

            context.refresh();

            assertThat(context.getBean(QuestCanonicalEventProjector.class)).isNotNull();
            assertThat(context.getBean(QuestCanonicalEventConsumer.class)).isNotNull();
        }
    }
}
