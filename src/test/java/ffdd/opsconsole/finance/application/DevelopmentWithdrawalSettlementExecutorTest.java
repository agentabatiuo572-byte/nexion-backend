package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;

class DevelopmentWithdrawalSettlementExecutorTest {

    @Test
    void componentIsStrictlyLimitedToDevelopmentProfile() {
        Profile profile = DevelopmentWithdrawalSettlementExecutor.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("dev & !prod");
        Profiles expression = Profiles.of(profile.value());
        assertThat(expression.matches("dev"::equals)).isTrue();
        assertThat(expression.matches("prod"::equals)).isFalse();
        assertThat(expression.matches(name -> java.util.Set.of("dev", "prod").contains(name))).isFalse();
    }

    @Test
    void reviewedDevelopmentWithdrawalIsSubmittedAndConfirmedWithoutProvider() {
        WithdrawalPayoutMapper mapper = mock(WithdrawalPayoutMapper.class);
        WithdrawalPayoutFinalizer finalizer = mock(WithdrawalPayoutFinalizer.class);
        var row = row();
        when(mapper.claimableDevelopment(any(LocalDateTime.class), anyInt())).thenReturn(List.of(row));
        when(mapper.claim(eq(row.withdrawalNo()), any(), any())).thenReturn(1);
        when(mapper.payout(row.withdrawalNo())).thenReturn(row);
        when(finalizer.submitted(eq(row), anyLong(), eq("dev-simulator"))).thenReturn(true);

        new DevelopmentWithdrawalSettlementExecutor(mapper, finalizer).process();

        verify(finalizer).submitted(eq(row), anyLong(), eq("dev-simulator"));
        verify(finalizer).completeDevelopmentSimulation(eq(row), anyLong());
    }

    @Test
    void lostClaimCannotProduceAnyPayoutState() {
        WithdrawalPayoutMapper mapper = mock(WithdrawalPayoutMapper.class);
        WithdrawalPayoutFinalizer finalizer = mock(WithdrawalPayoutFinalizer.class);
        var row = row();
        when(mapper.claimableDevelopment(any(LocalDateTime.class), anyInt())).thenReturn(List.of(row));
        when(mapper.claim(eq(row.withdrawalNo()), any(), any())).thenReturn(0);

        new DevelopmentWithdrawalSettlementExecutor(mapper, finalizer).process();

        verify(mapper, never()).payout(any());
        verify(finalizer, never()).submitted(any(), anyLong(), any());
        verify(finalizer, never()).completeDevelopmentSimulation(any(), anyLong());
    }

    private WithdrawalPayoutMapper.PayoutRow row() {
        return new WithdrawalPayoutMapper.PayoutRow(
                "WD-DEV-1", 42L, "USDT-TRC20", "TDevelopmentOnlyAddress1111111111111",
                new BigDecimal("20.000000"), new BigDecimal("19.000000"), BigDecimal.ZERO,
                "REVIEW_PASSED", LocalDateTime.now(), null, "WD-DEV-1", null, 0);
    }
}
