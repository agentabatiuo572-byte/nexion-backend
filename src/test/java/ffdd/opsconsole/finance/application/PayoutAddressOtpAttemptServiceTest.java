package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.AppPayoutAddressMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PayoutAddressOtpAttemptServiceTest {
    @Mock private AppPayoutAddressMapper mapper;
    @InjectMocks private PayoutAddressOtpAttemptService service;

    @Test
    void consumesAValidOtpWithoutAddingAFailure() {
        when(mapper.consumeOtp(7L, "PAYOUT-OK", "123456")).thenReturn(1);

        assertThat(service.verifyAndConsume(7L, "PAYOUT-OK", "123456")).isTrue();

        verify(mapper, never()).incrementOtpFailure(7L, "PAYOUT-OK");
    }

    @Test
    void recordsAnInvalidOtpInTheSameIndependentTransaction() {
        when(mapper.consumeOtp(8L, "PAYOUT-BAD", "654321")).thenReturn(0);

        assertThat(service.verifyAndConsume(8L, "PAYOUT-BAD", "654321")).isFalse();

        verify(mapper).incrementOtpFailure(8L, "PAYOUT-BAD");
    }
}
