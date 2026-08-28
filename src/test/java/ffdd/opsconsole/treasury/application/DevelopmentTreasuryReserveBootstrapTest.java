package ffdd.opsconsole.treasury.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;

class DevelopmentTreasuryReserveBootstrapTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-22T05:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void registersOnlyForDevWithoutProd() {
        Profile profile = DevelopmentTreasuryReserveBootstrap.class.getAnnotation(Profile.class);
        assertThat(profile.value()).containsExactly("dev & !prod");
        Profiles expression = Profiles.of(profile.value());
        assertThat(expression.matches("dev"::equals)).isTrue();
        assertThat(expression.matches(name -> java.util.Set.of("dev", "prod").contains(name))).isFalse();
        assertThat(expression.matches("prod"::equals)).isFalse();
    }

    @Test
    void delegatesTheAtomicTopUpToTheCanonicalD3Service() {
        OpsTreasuryService treasury = mock(OpsTreasuryService.class);
        when(treasury.ensureDevelopmentReserve(
                new BigDecimal("2000000"), LocalDate.of(2026, 8, 22)))
                .thenReturn(ApiResult.ok(Map.of(
                        "injected", true,
                        "amount", new BigDecimal("2000000.00"))));
        DevelopmentTreasuryReserveBootstrap bootstrap = new DevelopmentTreasuryReserveBootstrap(
                treasury, CLOCK, true, new BigDecimal("2000000"));

        assertThat(bootstrap.ensureReserve()).isEqualTo(1);
        verify(treasury).ensureDevelopmentReserve(
                new BigDecimal("2000000"), LocalDate.of(2026, 8, 22));
    }

    @Test
    void leavesAnAlreadyHealthyReserveUntouched() {
        OpsTreasuryService treasury = mock(OpsTreasuryService.class);
        when(treasury.ensureDevelopmentReserve(
                new BigDecimal("2000000"), LocalDate.of(2026, 8, 22)))
                .thenReturn(ApiResult.ok(Map.of("injected", false)));
        DevelopmentTreasuryReserveBootstrap bootstrap = new DevelopmentTreasuryReserveBootstrap(
                treasury, CLOCK, true, new BigDecimal("2000000"));

        assertThat(bootstrap.ensureReserve()).isZero();
        verify(treasury).ensureDevelopmentReserve(
                new BigDecimal("2000000"), LocalDate.of(2026, 8, 22));
    }

    @Test
    void failsStartupWhenTheCanonicalReserveCommandCannotEstablishCoverage() {
        OpsTreasuryService treasury = mock(OpsTreasuryService.class);
        when(treasury.ensureDevelopmentReserve(
                new BigDecimal("2000000"), LocalDate.of(2026, 8, 22)))
                .thenReturn(ApiResult.fail(503, "COVERAGE_DATA_UNAVAILABLE"));
        DevelopmentTreasuryReserveBootstrap bootstrap = new DevelopmentTreasuryReserveBootstrap(
                treasury, CLOCK, true, new BigDecimal("2000000"));

        assertThatThrownBy(bootstrap::ensureReserve)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DEVELOPMENT_TREASURY_RESERVE_UNAVAILABLE");
    }

    @Test
    void springConditionsExcludeProdAndDisabledDevContexts() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(OpsTreasuryService.class, () -> mock(OpsTreasuryService.class))
                .withBean(Clock.class, () -> CLOCK)
                .withUserConfiguration(DevelopmentTreasuryReserveBootstrap.class);

        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues(
                        "nexion.treasury.development-reserve.enabled=true",
                        "nexion.treasury.development-reserve.minimum-usdt=2000000")
                .run(context -> assertThat(context).doesNotHaveBean(DevelopmentTreasuryReserveBootstrap.class));
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues(
                        "nexion.treasury.development-reserve.enabled=false",
                        "nexion.treasury.development-reserve.minimum-usdt=2000000")
                .run(context -> assertThat(context).doesNotHaveBean(DevelopmentTreasuryReserveBootstrap.class));
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues(
                        "nexion.treasury.development-reserve.enabled=true",
                        "nexion.treasury.development-reserve.minimum-usdt=2000000")
                .run(context -> assertThat(context).hasSingleBean(DevelopmentTreasuryReserveBootstrap.class));
    }
}
