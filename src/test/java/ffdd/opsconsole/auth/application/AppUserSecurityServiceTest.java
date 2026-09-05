package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.dto.AppPasswordChangeRequest;
import ffdd.opsconsole.auth.dto.AppAccountDeletionRequest;
import ffdd.opsconsole.auth.dto.AppAccountDeletionCancelRequest;
import ffdd.opsconsole.auth.dto.AppTwoFactorUpdateRequest;
import ffdd.opsconsole.auth.dto.AppTwoFactorChallengeRequest;
import ffdd.opsconsole.auth.mapper.AppUserSecurityMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

class AppUserSecurityServiceTest {
    private final AppUserSecurityMapper security = mock(AppUserSecurityMapper.class);
    private final AuthSessionMapper sessions = mock(AuthSessionMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final UserOtpDeliveryService otpDelivery = mock(UserOtpDeliveryService.class);
    private final AppUserSecurityVerificationGuard verificationGuard = mock(AppUserSecurityVerificationGuard.class);
    private final UserOpsMapper users = mock(UserOpsMapper.class);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final AppUserSecurityService service = new AppUserSecurityService(
            security, sessions, passwords, audit, otpDelivery, verificationGuard, users);

    @BeforeEach
    void defaults() {
        when(security.passwordHashForUpdate(42L)).thenReturn(passwords.encode("OldPassword1"));
        when(security.twoFactorEnabled(42L)).thenReturn(false);
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setCountryCode("+84");
        user.setPhone("901234567");
        when(users.selectById(42L)).thenReturn(user);
        when(otpDelivery.available(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(verificationGuard.allowed(eq(42L), any())).thenReturn(true);
        when(verificationGuard.recordFailure(eq(42L), any()))
                .thenReturn(new AppUserSecurityVerificationGuard.VerificationFailure(1, false));
    }

    @Test
    void securityMutationsCommitGuardCountersAndAuditsWhenBusinessValidationRejects() throws Exception {
        for (String method : List.of("changePassword", "updateTwoFactor")) {
            Class<?>[] parameters = method.equals("changePassword")
                    ? new Class<?>[] {Long.class, String.class, AppPasswordChangeRequest.class}
                    : new Class<?>[] {Long.class, AppTwoFactorUpdateRequest.class};
            Transactional transactional = AppUserSecurityService.class
                    .getMethod(method, parameters)
                    .getAnnotation(Transactional.class);

            assertThat(transactional).isNotNull();
            assertThat(Arrays.asList(transactional.noRollbackFor()))
                    .containsExactly(AppUserSecurityService.PreWriteRejection.class);
        }
    }

    @Test
    void overviewUsesOnlyServerSessionsAndMarksTheJwtSessionCurrent() {
        LocalDateTime now = LocalDateTime.now();
        UserSessionEntity current = session("current", "Nexion H5", "203.0.113.8", now);
        UserSessionEntity other = session("other", "Chrome on Windows", "198.51.100.9", now.minusHours(2));
        when(sessions.currentUserSession(42L, "current", 30)).thenReturn(current);
        when(sessions.pageOtherUserSessions(42L, "current", 30, null)).thenReturn(List.of(other));
        when(security.passwordChangedAt(42L)).thenReturn(now.minusDays(3));

        var state = service.overview(42L, "current");

        assertThat(state.sessions()).hasSize(2);
        assertThat(state.sessions().get(0).current()).isTrue();
        assertThat(state.sessions().get(0).ipMasked()).isEqualTo("203.0.113.*");
        assertThat(state.sessions().get(1).current()).isFalse();
        assertThat(state.passwordChangedAt()).isEqualTo(now.minusDays(3));
    }

    @Test
    void overviewFollowsConfiguredIdleTtlAndRejectsInvalidConfiguration() {
        when(security.sessionIdleDaysConfig()).thenReturn(" 14 ", null, "oops", "6", "91");
        service.overview(42L, "current");
        verify(sessions).pageOtherUserSessions(42L, "current", 14, null);
        for (int i = 0; i < 4; i++) service.overview(42L, "current");
        verify(sessions, org.mockito.Mockito.times(4)).pageOtherUserSessions(42L, "current", 30, null);
    }

    @Test
    void sessionPagesUseStableIdsAndDoNotRepeatTheCurrentSession() {
        var rows = java.util.stream.LongStream.rangeClosed(1, 21).mapToObj(n -> {
            var row = session("session-" + n, "Phone", "203.0.113.8", LocalDateTime.now());
            row.setId(100L - n);
            return row;
        }).toList();
        when(sessions.pageOtherUserSessions(42L, "current", 30, null)).thenReturn(rows);
        var first = service.overview(42L, "current");
        assertThat(first.sessions()).hasSize(20);
        assertThat(first.nextCursor()).isEqualTo("80");
        when(sessions.pageOtherUserSessions(42L, "current", 30, 80L)).thenReturn(List.of(rows.get(20)));
        assertThat(service.overview(42L, "current", "80").sessions()).hasSize(1);
        verify(sessions, org.mockito.Mockito.times(1)).currentUserSession(42L, "current", 30);
        assertThatThrownBy(() -> service.overview(42L, "current", "-1")).hasMessage("SECURITY_CURSOR_INVALID");
    }

    @Test
    void passwordReceiptReplaysWithoutChangingHashOrRevokingAgain() {
        LocalDateTime changedAt = LocalDateTime.now();
        when(security.passwordHashForUpdate(42L)).thenReturn(passwords.encode("NewPassword2"));
        when(security.passwordChangeReceipt(42L, "current", "password-key"))
                .thenReturn(new ffdd.opsconsole.auth.dto.AppSecurityMutationResponse(null, changedAt, 2));
        var result = service.changePassword(42L, "current", "password-key",
                new AppPasswordChangeRequest("OldPassword1", "NewPassword2"));
        assertThat(result.passwordChangedAt()).isEqualTo(changedAt);
        assertThat(result.revokedSessionCount()).isEqualTo(2);
        verify(security, never()).updatePasswordHash(any(), any());
        verify(sessions, never()).revokeOtherUserSessions(any(), any());
        verify(audit, never()).recordRequired(any());
    }

    @Test
    void passwordReceiptReadIsBoundToUserButSurvivesSessionRotation() throws Exception {
        var receipt = new ffdd.opsconsole.auth.dto.AppSecurityMutationResponse(null, LocalDateTime.now(), 2);
        when(security.passwordChangeReceipt(42L, "new-session", "password-key")).thenReturn(receipt);
        assertThat(service.passwordCommandReceipt(42L, "new-session", "password-key")).isSameAs(receipt);
        assertThat(service.passwordCommandReceipt(43L, "other-session", "password-key")).isNull();
        var sql = AppUserSecurityMapper.class.getMethod("passwordChangeReceipt", Long.class, String.class, String.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value()[0];
        assertThat(sql).contains("user_id=#{userId}", "command_key=#{commandKey}").doesNotContain("session_id=#{sessionId}");
        verify(security, never()).updatePasswordHash(any(), any());
    }

    @Test
    void passwordChangePersistsReceiptOnlyAfterTheAuditedMutation() {
        LocalDateTime changedAt = LocalDateTime.now();
        when(security.updatePasswordHash(eq(42L), any())).thenReturn(1);
        when(security.markPasswordChanged(42L)).thenReturn(1);
        when(security.passwordChangedAt(42L)).thenReturn(changedAt);
        when(sessions.revokeOtherUserSessions(42L, "current")).thenReturn(2);
        when(security.insertPasswordChangeReceipt(42L, "current", "password-key", changedAt, 2)).thenReturn(1);
        var result = service.changePassword(42L, "current", "password-key",
                new AppPasswordChangeRequest("OldPassword1", "NewPassword2"));
        assertThat(result.passwordChangedAt()).isEqualTo(changedAt);
        var order = org.mockito.Mockito.inOrder(security, sessions, audit);
        order.verify(security).updatePasswordHash(eq(42L), any());
        order.verify(sessions).revokeOtherUserSessions(42L, "current");
        order.verify(audit).recordRequired(any());
        order.verify(security).insertPasswordChangeReceipt(42L, "current", "password-key", changedAt, 2);
    }

    @Test
    void passwordReceiptCannotBeReusedForDifferentInput() {
        when(security.passwordChangeReceipt(42L, "current", "password-key"))
                .thenReturn(new ffdd.opsconsole.auth.dto.AppSecurityMutationResponse(null, LocalDateTime.now(), 2));
        assertThatThrownBy(() -> service.changePassword(42L, "current", "password-key",
                new AppPasswordChangeRequest("OldPassword1", "OtherPassword2")))
                .hasMessage("PASSWORD_COMMAND_INPUT_CHANGED");
        verify(security, never()).updatePasswordHash(any(), any());
        verify(sessions, never()).revokeOtherUserSessions(any(), any());
    }

    @Test
    void revokeOneIsScopedToTheAuthenticatedUserAndCannotRevokeCurrentSession() {
        assertThatThrownBy(() -> service.revokeSession(42L, "current", "current"))
                .hasMessage("CURRENT_SESSION_REVOKE_FORBIDDEN");
        verify(sessions, never()).revokeOwnedUserSession(any(), any());

        when(sessions.revokeOwnedUserSession(42L, "other")).thenReturn(1);
        var result = service.revokeSession(42L, "current", "other");

        assertThat(result.revokedSessionCount()).isEqualTo(1);
        verify(sessions).revokeOwnedUserSession(42L, "other");
        verify(audit).recordRequired(any());
    }

    @Test
    void missingOrForeignSessionNeverReportsSuccess() {
        when(sessions.revokeOwnedUserSession(42L, "foreign")).thenReturn(0);

        assertThatThrownBy(() -> service.revokeSession(42L, "current", "foreign"))
                .hasMessage("SESSION_NOT_ACTIVE_OR_NOT_OWNED");
        verify(audit, never()).recordRequired(any());
    }

    @Test
    void malformedSessionIdIsRejectedBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.revokeSession(42L, "current", "../foreign session"))
                .hasMessage("SESSION_ID_INVALID");

        verify(sessions, never()).revokeOwnedUserSession(any(), any());
    }

    @Test
    void revokeOthersKeepsTheCurrentJwtSession() {
        when(sessions.revokeOtherUserSessions(42L, "current")).thenReturn(3);

        var result = service.revokeOtherSessions(42L, "current");

        assertThat(result.revokedSessionCount()).isEqualTo(3);
        verify(sessions).revokeOtherUserSessions(42L, "current");
        verify(audit).recordRequired(any());
    }

    @Test
    void cancellationIsScopedToTheAuthenticatedUserAndUsesVersionCas() {
        when(security.latestAccountDeletionRequest(42L)).thenReturn(Map.of(
                "requestNo", "ADR-0123456789abcdef0123456789abcdef",
                "status", "IN_REVIEW", "version", 1L, "requestedAt", LocalDateTime.now()), Map.of(
                "requestNo", "ADR-0123456789abcdef0123456789abcdef",
                "status", "CANCELLED", "version", 2L, "requestedAt", LocalDateTime.now()));
        when(security.transitionAccountDeletion("ADR-0123456789abcdef0123456789abcdef", "IN_REVIEW", "CANCELLED",
                1L, "USER_REQUESTED_CANCEL", null)).thenReturn(1);
        when(security.accountDeletionRequestForUpdate(42L, "cancel-key")).thenReturn(null);
        Map<String, Object> result = service.cancelAccountDeletion(
                42L, "current", "cancel-key", new AppAccountDeletionCancelRequest(1L, "USER_REQUESTED_CANCEL"));

        assertThat(result).containsEntry("status", "CANCELLED");
        verify(security).transitionAccountDeletion("ADR-0123456789abcdef0123456789abcdef", "IN_REVIEW", "CANCELLED",
                1L, "USER_REQUESTED_CANCEL", null);
    }

    @Test
    void passwordChangeVerifiesCurrentPasswordThenRevokesOtherSessions() {
        when(security.updatePasswordHash(eq(42L), any())).thenReturn(1);
        when(security.markPasswordChanged(42L)).thenReturn(1);
        when(sessions.revokeOtherUserSessions(42L, "current")).thenReturn(2);

        var result = service.changePassword(
                42L, "current", new AppPasswordChangeRequest("OldPassword1", "NewPassword2"));

        assertThat(result.revokedSessionCount()).isEqualTo(2);
        verify(security).updatePasswordHash(eq(42L), any());
        verify(security).markPasswordChanged(42L);
        verify(audit).recordRequired(any());
    }

    @Test
    void wrongCurrentPasswordCannotChangePasswordOrRevokeSessions() {
        assertThatThrownBy(() -> service.changePassword(
                42L, "current", new AppPasswordChangeRequest("wrong", "NewPassword2")))
                .hasMessage("CURRENT_PASSWORD_INVALID");

        verify(security, never()).updatePasswordHash(any(), any());
        verify(sessions, never()).revokeOtherUserSessions(any(), any());
        verify(verificationGuard).recordFailure(42L, "PASSWORD_CHANGE");
    }

    @Test
    void rateLimitedCurrentPasswordCheckStopsBeforeHashVerification() {
        when(verificationGuard.allowed(42L, "TWO_FACTOR_UPDATE")).thenReturn(false);

        assertThatThrownBy(() -> service.updateTwoFactor(
                42L, new AppTwoFactorUpdateRequest(true, "OldPassword1")))
                .hasMessage("USER_SECURITY_VERIFICATION_RATE_LIMITED");

        verify(security, never()).passwordHashForUpdate(any());
        verify(security, never()).upsertTwoFactor(any(), anyBoolean());
        verify(verificationGuard).allowed(42L, "TWO_FACTOR_UPDATE");
    }

    @Test
    void oversizedCurrentPasswordIsRejectedBeforeHashVerification() {
        String oversized = "x".repeat(65);

        assertThatThrownBy(() -> service.changePassword(
                42L, "current", new AppPasswordChangeRequest(oversized, "NewPassword2")))
                .hasMessage("CURRENT_PASSWORD_INVALID");
        assertThatThrownBy(() -> service.updateTwoFactor(
                42L, new AppTwoFactorUpdateRequest(true, oversized)))
                .hasMessage("CURRENT_PASSWORD_INVALID");

        verify(security, never()).updatePasswordHash(any(), any());
        verify(security, never()).upsertTwoFactor(any(), anyBoolean());
    }

    @Test
    void twoFactorChallengeRequiresPasswordAndAvailableDelivery() {
        when(otpDelivery.available("+84")).thenReturn(false);
        assertThatThrownBy(() -> service.sendTwoFactorChallenge(
                42L, new AppTwoFactorChallengeRequest(true, "OldPassword1")))
                .hasMessage("USER_OTP_DELIVERY_UNAVAILABLE");
        verify(security, never()).upsertTwoFactor(any(), anyBoolean());

        when(otpDelivery.available("+84")).thenReturn(true);
        when(otpDelivery.verificationCode("+84")).thenReturn("123456");
        when(users.createLoginOtpChallenge(eq(42L), any(), eq("123456"), eq(10))).thenReturn(1);
        Map<String, Object> challenge = service.sendTwoFactorChallenge(
                42L, new AppTwoFactorChallengeRequest(true, "OldPassword1"));
        assertThat(challenge).containsKeys("challengeNo", "expiresInSeconds", "phoneMasked");
        verify(otpDelivery).deliver(eq("+84"), eq("901234567"), any(), eq("123456"), eq(10));

        String challengeNo = String.valueOf(challenge.get("challengeNo"));
        when(users.consumeValidSecurityOtp(42L, challengeNo, "123456")).thenReturn(1);
        when(security.upsertTwoFactor(42L, true)).thenReturn(1);
        var result = service.updateTwoFactor(42L,
                new AppTwoFactorUpdateRequest(true, "OldPassword1", challengeNo, "123456"));

        assertThat(result.twoFactorEnabled()).isTrue();
        verify(security).upsertTwoFactor(42L, true);
        verify(audit, org.mockito.Mockito.times(2)).recordRequired(any());
    }

    @Test
    void twoFactorAvailabilityIsCheckedForTheUsersOwnCountry() {
        UserEntity vietnamUser = new UserEntity();
        vietnamUser.setId(42L);
        vietnamUser.setCountryCode("+84");
        vietnamUser.setPhone("901234567");
        when(users.selectById(42L)).thenReturn(vietnamUser);
        when(otpDelivery.available("+84")).thenReturn(false);

        assertThatThrownBy(() -> service.sendTwoFactorChallenge(
                42L, new AppTwoFactorChallengeRequest(true, "OldPassword1")))
                .hasMessage("USER_OTP_DELIVERY_UNAVAILABLE");

        verify(otpDelivery).available("+84");
        verify(security, never()).upsertTwoFactor(any(), anyBoolean());
    }

    @Test
    void twoFactorChallengeRejectsInvalidPhoneBeforeOtpStateMutation() {
        UserEntity invalid = new UserEntity();
        invalid.setId(42L);
        invalid.setCountryCode("+86");
        invalid.setPhone("12800138000");
        when(users.selectById(42L)).thenReturn(invalid);

        assertThatThrownBy(() -> service.sendTwoFactorChallenge(
                42L, new AppTwoFactorChallengeRequest(true, "OldPassword1")))
                .hasMessage("USER_PHONE_INVALID");

        verify(otpDelivery, never()).available(any());
        verify(users, never()).createLoginOtpChallenge(any(), any(), any(), anyInt());
        verify(otpDelivery, never()).deliver(any(), any(), any(), any(), anyInt());
    }

    @Test
    void twoFactorMutationRequiresAValidOtpBoundToTheRequestedDirection() {
        assertThatThrownBy(() -> service.updateTwoFactor(
                42L, new AppTwoFactorUpdateRequest(true, "OldPassword1", "SEC2FA-D-foreign", "123456")))
                .hasMessage("USER_TWO_FACTOR_OTP_REQUIRED");
        assertThatThrownBy(() -> service.updateTwoFactor(
                42L, new AppTwoFactorUpdateRequest(true, "OldPassword1", "SEC2FA-E-expired", "123456")))
                .hasMessage("USER_TWO_FACTOR_OTP_INVALID_OR_EXPIRED");
        verify(security, never()).upsertTwoFactor(any(), anyBoolean());
    }

    @Test
    void accountDeletionCreatesOneServerTrackedRequestAfterPasswordVerification() {
        when(security.insertAccountDeletionRequest(any(), eq(42L), eq("delete-key"))).thenReturn(1);
        when(security.accountDeletionRequestForUpdate(42L, "delete-key")).thenReturn(Map.of(
                "requestNo", "ADR-0123456789abcdef0123456789abcdef",
                "status", "REQUESTED",
                "requestedAt", LocalDateTime.now()));

        Map<String, Object> result = service.requestAccountDeletion(
                42L, "current", "delete-key", new AppAccountDeletionRequest("OldPassword1", "DELETE"));

        assertThat(result).containsEntry("status", "REQUESTED");
        verify(security).passwordHashForUpdate(42L);
        verify(security).insertAccountDeletionRequest(any(), eq(42L), eq("delete-key"));
        verify(audit).recordRequired(any());
        verify(sessions, never()).revokeAllUserSessions(any());
    }

    @Test
    void accountDeletionRejectsMissingExplicitConfirmationBeforePasswordRead() {
        assertThatThrownBy(() -> service.requestAccountDeletion(
                42L, "current", "delete-key", new AppAccountDeletionRequest("OldPassword1", "yes")))
                .hasMessage("ACCOUNT_DELETION_CONFIRMATION_REQUIRED");

        verify(security, never()).insertAccountDeletionRequest(any(), any(), any());
    }

    private UserSessionEntity session(String id, String device, String ip, LocalDateTime lastActiveAt) {
        UserSessionEntity row = new UserSessionEntity();
        row.setUserId(42L);
        row.setRefreshTokenId(id);
        row.setDeviceName(device);
        row.setClientIp(ip);
        row.setLastActiveAt(lastActiveAt);
        row.setExpiresAt(lastActiveAt.plusDays(30));
        row.setCreatedAt(lastActiveAt.minusMinutes(1));
        row.setIsDeleted(0);
        return row;
    }
}
