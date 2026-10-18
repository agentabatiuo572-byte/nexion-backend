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
            context.registerBean(ffdd.opsconsole.growth.mapper.WeeklyQuestSnapshotMapper.class,
                    () -> mock(ffdd.opsconsole.growth.mapper.WeeklyQuestSnapshotMapper.class));
            context.registerBean(ffdd.opsconsole.growth.facade.GrowthRhythmFacade.class,
                    () -> mock(ffdd.opsconsole.growth.facade.GrowthRhythmFacade.class));
            context.registerBean(ffdd.opsconsole.growth.facade.StreakPerkBusinessAvailabilityFacade.class,
                    () -> mock(ffdd.opsconsole.growth.facade.StreakPerkBusinessAvailabilityFacade.class));
            context.registerBean(WeeklyRegionPolicyService.class,()->mock(WeeklyRegionPolicyService.class));
            context.registerBean(ffdd.opsconsole.user.facade.UserWeeklyRegionFacade.class,
                    ()->mock(ffdd.opsconsole.user.facade.UserWeeklyRegionFacade.class));
            context.registerBean(java.time.Clock.class, java.time.Clock::systemUTC);
            context.register(WeeklyQuestSnapshotService.class, QuestCanonicalEventProjector.class, QuestCanonicalEventConsumer.class);

            context.refresh();

            assertThat(context.getBean(QuestCanonicalEventProjector.class)).isNotNull();
            assertThat(context.getBean(QuestCanonicalEventConsumer.class)).isNotNull();
            var weekly = context.getBean(WeeklyQuestSnapshotService.class);
            for (Class<?> type : new Class<?>[]{QuestCanonicalEventProjector.class, QuestCanonicalEventConsumer.class}) {
                assertThat(org.springframework.test.util.ReflectionTestUtils.getField(context.getBean(type), "weeklySnapshots"))
                        .isSameAs(weekly);
            }
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(weekly,"regionPolicy"))
                    .isSameAs(context.getBean(WeeklyRegionPolicyService.class));
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(weekly,"users"))
                    .isSameAs(context.getBean(ffdd.opsconsole.user.facade.UserWeeklyRegionFacade.class));
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(weekly, "availability"))
                    .isSameAs(context.getBean(ffdd.opsconsole.growth.facade.StreakPerkBusinessAvailabilityFacade.class));
        }
    }
}
