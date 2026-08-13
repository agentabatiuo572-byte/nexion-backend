package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.application.UserOtpDeliveryService;
import ffdd.opsconsole.finance.application.AppPayoutAddressService.SaveRequest;
import ffdd.opsconsole.finance.mapper.AppPayoutAddressMapper;
import ffdd.opsconsole.finance.mapper.AppPayoutAddressMapper.PayoutAddressRow;
import ffdd.opsconsole.finance.mapper.AppPayoutAddressMapper.UserContact;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AppPayoutAddressServiceTest {
    @Mock private AppPayoutAddressMapper mapper;
    @Mock private UserOtpDeliveryService otpDelivery;
    @Mock private AuditLogService audit;
    @Mock private AdminIdempotencyService idempotency;
    @Mock private PayoutAddressOtpAttemptService otpAttempts;
    @InjectMocks private AppPayoutAddressService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void executeClaimedAction() {
        lenient().when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
    }

    @Test
    void bep20AddressUsesTheSameEvmValidationAndDurableIdempotencyBoundary() {
        long userId = 7L;
        String address = "0x" + "ab".repeat(20);
        when(mapper.activeUser(userId)).thenReturn(userId);
        when(otpAttempts.verifyAndConsume(userId, "PAYOUT-ABC", "123456")).thenReturn(true);
        when(mapper.unsettledWithdrawalCount(userId)).thenReturn(0);
        when(mapper.lock(userId, "USDT-BEP20")).thenReturn(null, new PayoutAddressRow(
                "USDT-BEP20", address, "ACTIVE", LocalDateTime.now().plusHours(24),
                LocalDateTime.now(), LocalDateTime.now().plusDays(7), 0L));
        when(mapper.insert(userId, "USDT-BEP20", address)).thenReturn(1);

        service.save(userId, new SaveRequest("USDT-BEP20", address, "PAYOUT-ABC", "123456"), "cmd-1");

        verify(idempotency).execute(eq("USER_PAYOUT_ADDRESS:7"), eq("cmd-1"), anyString(),
                eq(ApiResult.class), any());
        verify(mapper).insert(userId, "USDT-BEP20", address);
    }

    @Test
    void invalidOtpIsRecordedOutsideTheRejectedCommandTransaction() {
        long userId = 8L;
        String address = "T" + "A".repeat(33);
        when(mapper.activeUser(userId)).thenReturn(userId);
        when(otpAttempts.verifyAndConsume(userId, "PAYOUT-DEF", "654321")).thenReturn(false);

        assertThatThrownBy(() -> service.save(userId,
                new SaveRequest("USDT-TRC20", address, "PAYOUT-DEF", "654321"), "cmd-2"))
                .isInstanceOf(BizException.class)
                .hasMessage("PAYOUT_ADDRESS_OTP_INVALID");

        verify(otpAttempts).verifyAndConsume(userId, "PAYOUT-DEF", "654321");
    }

    @Test
    void otpQuotaChecksAreSerializedByTheActiveUserRow() {
        long userId = 9L;
        when(mapper.lockActiveUser(userId)).thenReturn(userId);
        when(otpDelivery.available()).thenReturn(true);
        when(otpDelivery.verificationCode()).thenReturn("123456");
        when(mapper.recentOtpCount(userId)).thenReturn(0);
        when(mapper.todayOtpCount(userId)).thenReturn(0);
        when(mapper.userContact(userId)).thenReturn(new UserContact("+84", "900000000"));
        when(mapper.insertOtp(eq(userId), anyString(), anyString())).thenReturn(1);

        service.sendOtp(userId);

        verify(mapper).lockActiveUser(userId);
        verify(otpDelivery).deliver(eq("+84"), eq("900000000"), anyString(), anyString(), eq(5));
    }
}
