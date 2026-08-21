package ffdd.opsconsole.home.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.home.mapper.DevelopmentHomeSettlementMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    void seedsFiveIdempotentSettlementsInRealBusinessTablesForTheFixedDevelopmentAccount() {
        DevelopmentHomeSettlementMapper mapper = mock(DevelopmentHomeSettlementMapper.class);
        when(mapper.findDevelopmentUserId("+86", "18708173775")).thenReturn(60723152670L);
        when(mapper.findDevelopmentHomeDeviceId(60723152670L)).thenReturn(810L);
        when(mapper.insertSettledReceipt(any())).thenReturn(1);
        Clock clock = Clock.fixed(Instant.parse("2026-08-21T04:30:00Z"), ZoneId.of("Asia/Shanghai"));
        DevelopmentHomeSettlementBootstrap bootstrap = new DevelopmentHomeSettlementBootstrap(
                mapper, clock, "+86", "18708173775", true);

        assertThat(bootstrap.seedToday()).isEqualTo(5);

        ArgumentCaptor<DevelopmentHomeSettlementMapper.DevelopmentSettlement> rows =
                ArgumentCaptor.forClass(DevelopmentHomeSettlementMapper.DevelopmentSettlement.class);
        verify(mapper, org.mockito.Mockito.times(5)).insertCompletedTask(rows.capture());
        verify(mapper, org.mockito.Mockito.times(5)).insertSettledReceipt(any());
        java.math.BigDecimal total = rows.getAllValues().stream()
                .map(DevelopmentHomeSettlementMapper.DevelopmentSettlement::rewardUsdt)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        assertThat(total).isEqualByComparingTo("323.89");
        assertThat(rows.getAllValues()).allSatisfy(row -> {
            assertThat(row.sourceEnvironment()).isEqualTo("PRODUCTION");
            assertThat(row.taskNo()).startsWith("DEV-HOME-20260821-");
            assertThat(row.completedAt().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 21));
        });
    }

    @Test
    void doesNothingWhenTheConfiguredDevelopmentAccountIsMissing() {
        DevelopmentHomeSettlementMapper mapper = mock(DevelopmentHomeSettlementMapper.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-21T04:30:00Z"), ZoneId.of("Asia/Shanghai"));
        DevelopmentHomeSettlementBootstrap bootstrap = new DevelopmentHomeSettlementBootstrap(
                mapper, clock, "+86", "18708173775", true);

        assertThat(bootstrap.seedToday()).isZero();
        verify(mapper).findDevelopmentUserId("+86", "18708173775");
    }

    @Test
    void failsClosedWhenConfigurationAttemptsToRedirectTheFixedDevelopmentAccount() {
        DevelopmentHomeSettlementMapper mapper = mock(DevelopmentHomeSettlementMapper.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-21T04:30:00Z"), ZoneId.of("Asia/Shanghai"));
        DevelopmentHomeSettlementBootstrap bootstrap = new DevelopmentHomeSettlementBootstrap(
                mapper, clock, "+84", "19999999999", true);

        assertThat(bootstrap.seedToday()).isZero();

        verify(mapper, never()).findDevelopmentUserId(any(), any());
        verify(mapper, never()).insertCompletedTask(any());
        verify(mapper, never()).insertSettledReceipt(any());
    }

    @Test
    void createsAStableCanonicalDevelopmentDeviceWhenTheFixedAccountHasNoActiveOwnedDevice() {
        DevelopmentHomeSettlementMapper mapper = mock(DevelopmentHomeSettlementMapper.class);
        when(mapper.findDevelopmentUserId("+86", "18708173775")).thenReturn(60723152670L);
        when(mapper.findDevelopmentHomeDeviceId(60723152670L)).thenReturn(null, 811L);
        when(mapper.insertSettledReceipt(any())).thenReturn(1);
        Clock clock = Clock.fixed(Instant.parse("2026-08-21T04:30:00Z"), ZoneId.of("Asia/Shanghai"));
        DevelopmentHomeSettlementBootstrap bootstrap = new DevelopmentHomeSettlementBootstrap(
                mapper, clock, "+86", "18708173775", true);

        assertThat(bootstrap.seedToday()).isEqualTo(5);

        verify(mapper).ensureDevelopmentDevice(60723152670L, "DEV-HOME-PHONE-60723152670");
        verify(mapper, org.mockito.Mockito.times(2)).findDevelopmentHomeDeviceId(60723152670L);
    }
}
