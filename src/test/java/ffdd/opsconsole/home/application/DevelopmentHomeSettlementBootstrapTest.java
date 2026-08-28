package ffdd.opsconsole.home.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.home.mapper.DevelopmentHomeSettlementMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;

class DevelopmentHomeSettlementBootstrapTest {
    @Test
    void registersOnlyForDevWithoutProdEvenWhenProfilesAreMixed() {
        Profile profile = DevelopmentHomeSettlementBootstrap.class.getAnnotation(Profile.class);
        assertThat(profile.value()).containsExactly("dev & !prod");

        Profiles expression = Profiles.of(profile.value());
        assertThat(expression.matches("dev"::equals)).isTrue();
        assertThat(expression.matches(name -> java.util.Set.of("dev", "prod").contains(name))).isFalse();
        assertThat(expression.matches("prod"::equals)).isFalse();
    }

    @Test
    void runCreatesOnlyTheStablePhoneThenUsesTaskProgression() {
        DevelopmentHomeSettlementMapper mapper = mock(DevelopmentHomeSettlementMapper.class);
        when(mapper.findDevelopmentUserId("+86", "18708173775")).thenReturn(60723152670L);
        when(mapper.findDevelopmentHomeDeviceId(60723152670L)).thenReturn(null);
        when(mapper.developmentE3CapacityConfig()).thenReturn(java.util.List.of());
        DevelopmentHomeSettlementBootstrap bootstrap = new DevelopmentHomeSettlementBootstrap(
                mapper, Clock.fixed(Instant.parse("2026-08-27T04:00:00Z"), ZoneId.of("Asia/Shanghai")),
                "+86", "18708173775", true);

        bootstrap.run(null);

        verify(mapper).ensureDevelopmentDevice(60723152670L, "DEV-HOME-PHONE-60723152670");
        verify(mapper, never()).insertCompletedTask(org.mockito.ArgumentMatchers.any());
        verify(mapper, never()).insertSettledReceipt(org.mockito.ArgumentMatchers.any());
        verify(mapper, never()).insertDevelopmentPurchasedTask(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void removedMidnightAndDailyOrderSettlementAreNotScheduledOrReachable() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/home/application/DevelopmentHomeSettlementBootstrap.java"));

        assertThat(source).doesNotContain("seedToday(", "seedPurchasedDevices(",
                "DEV-HOME-202", "DEV-ORDER-");
        assertThat(source).contains("advanceTasks()", "TASK_PREFIX = \"DEV-TASK-\"");
    }
}
