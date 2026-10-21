package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.mapper.AppPhoneNumberChangeMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.security.UserSessionRevocationService;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AppPhoneNumberChangeServiceTest {
    private final AppPhoneNumberChangeMapper mapper = mock(AppPhoneNumberChangeMapper.class);
    private final AppUserSecurityService security = mock(AppUserSecurityService.class);
    private final AppUserSecurityVerificationGuard guard = mock(AppUserSecurityVerificationGuard.class);
    private final UserOpsMapper users = mock(UserOpsMapper.class);
    private final UserOtpDeliveryService delivery = mock(UserOtpDeliveryService.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final UserSessionRevocationService revocations = mock(UserSessionRevocationService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AppPhoneNumberChangeService service = new AppPhoneNumberChangeService(
            mapper, security, guard, users, delivery, config, revocations, audit);

    @Test
    void policyDefaultsToRolling365DaysAndFiveChangesPerUser() {
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        when(mapper.countCompleted(42L, 365)).thenReturn(3);

        Map<String, Object> policy = service.policy(42L);

        assertThat(policy).containsEntry("windowDays", 365).containsEntry("maxChanges", 5)
                .containsEntry("usedChanges", 3).containsEntry("remainingChanges", 2);
        verify(mapper).countCompleted(42L, 365);
    }

    @Test
    void startRefusesTheSixthChangeBeforeSendingCodes() {
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        when(mapper.countCompleted(42L, 365)).thenReturn(5);
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setCountryCode("86");
        user.setPhone("18708173778");
        user.setStatus("ACTIVE");
        when(users.selectById(42L)).thenReturn(user);

        assertThatThrownBy(() -> service.start(42L, "Password1", "86", "13800000001"))
                .hasMessage("USER_PHONE_CHANGE_LIMIT_REACHED");
        verify(delivery, never()).deliver(anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void repeatedStartRecoversTheExistingChallengeAfterAResponseIsLost() {
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setCountryCode("86");
        user.setPhone("18708173778");
        user.setStatus("ACTIVE");
        when(users.selectById(42L)).thenReturn(user);
        when(mapper.latestOpen(42L)).thenReturn(Map.of(
                "changeNo", "PHONE-1234567890abcdef1234567890abcdef",
                "oldCountryCode", "86", "oldPhone", "18708173778",
                "newCountryCode", "86", "newPhone", "13800000001",
                "expiresInSeconds", 300));
        when(mapper.recentStarts(42L)).thenReturn(1);

        Map<String, Object> result = service.start(42L, "Password1", "86", "13800000001");

        assertThat(result).containsEntry("changeNo", "PHONE-1234567890abcdef1234567890abcdef")
                .containsEntry("expiresInSeconds", 300);
        verify(delivery, never()).deliver(anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void confirmationRechecksLimitEvenWhenChallengeWasIssuedEarlier() {
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        when(mapper.countCompleted(42L, 365)).thenReturn(5);
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setCountryCode("86");
        user.setPhone("18708173778");
        user.setStatus("ACTIVE");
        when(users.selectById(42L)).thenReturn(user);
        String changeNo = "PHONE-1234567890abcdef1234567890abcdef";
        when(mapper.lock(42L, changeNo)).thenReturn(Map.of(
                "oldCountryCode", "86", "oldPhone", "18708173778",
                "newCountryCode", "86", "newPhone", "13800000001",
                "expiresAt", LocalDateTime.now().plusMinutes(5), "attemptCount", 0));
        when(mapper.open(42L, changeNo)).thenReturn(1);

        assertThatThrownBy(() -> service.confirm(42L, "session", changeNo, "123456", "654321"))
                .hasMessage("USER_PHONE_CHANGE_LIMIT_REACHED");
        verify(mapper, never()).changeUserPhone(org.mockito.ArgumentMatchers.anyLong(),
                anyString(), anyString(), anyString(), anyString());
    }
}
