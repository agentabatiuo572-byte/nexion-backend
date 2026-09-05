package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.dto.UserLoginResponse;
import ffdd.opsconsole.auth.dto.UserRegistrationOtpRequest;
import ffdd.opsconsole.auth.dto.UserRegistrationRequest;
import ffdd.opsconsole.auth.captcha.CaptchaOtpGate;
import ffdd.opsconsole.auth.infrastructure.UserOtpSendGuardRecord;
import ffdd.opsconsole.auth.mapper.AppUserRegistrationMapper;
import ffdd.opsconsole.auth.mapper.TeamAncestorProjection;
import ffdd.opsconsole.auth.mapper.UserLoginGuardMapper;
import ffdd.opsconsole.growth.application.OpsReferralRewardService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.annotation.Transactional;

class AppUserRegistrationServiceTest {
    private final AppUserRegistrationMapper mapper = mock(AppUserRegistrationMapper.class);
    private final UserOpsMapper userMapper = mock(UserOpsMapper.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final UserOtpDeliveryService otpDeliveryService = mock(UserOtpDeliveryService.class);
    private final AppUserAuthService authService = mock(AppUserAuthService.class);
    private final EventOutboxService outboxService = mock(EventOutboxService.class);
    private final AppUserRegistrationTransactionExecutor transactionExecutor = mock(AppUserRegistrationTransactionExecutor.class);
    private final Environment environment = mock(Environment.class);
    private final OpsReferralRewardService referralRewardService = mock(OpsReferralRewardService.class);
    private final ffdd.opsconsole.platform.facade.PlatformConfigFacade configFacade = mock(ffdd.opsconsole.platform.facade.PlatformConfigFacade.class);
    private final UserLoginGuardMapper loginGuards = mock(UserLoginGuardMapper.class);
    private final CaptchaOtpGate captchaGate = mock(CaptchaOtpGate.class);
    private AppUserRegistrationService service;

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get())
                .when(transactionExecutor).execute(any());
        service = new AppUserRegistrationService(
                mapper, userMapper, passwordEncoder, otpDeliveryService, authService, outboxService,
                transactionExecutor, environment, referralRewardService, configFacade, loginGuards, captchaGate);
        when(configFacade.activeValue(any())).thenReturn(Optional.empty());
        when(loginGuards.lockOtpSendGuard(any())).thenAnswer(ignored -> freshOtpSendGuard());
        when(loginGuards.recordOtpSend(any(), any(), any(), anyInt(), any(), anyInt())).thenReturn(1);
        when(loginGuards.countRecentOtpSendEvents(any(), any())).thenReturn(0);
        when(loginGuards.insertOtpSendEvent(any(), any())).thenReturn(1);
        when(captchaGate.checkAndConsume(any(), any(), any(), anyInt()))
                .thenReturn(new CaptchaOtpGate.Decision(true, 0, "OK"));
        when(environment.getActiveProfiles()).thenReturn(new String[] { "prod" });
        when(mapper.insertTeamMemberProjection(anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(1);
        when(mapper.listActiveSponsorChain(anyLong(), anyInt())).thenReturn(List.of());
    }

    @Test
    void providerFailureInvalidatesTheChallengeWithoutRollingBackTheSharedThrottle() throws Exception {
        when(otpDeliveryService.available("+84")).thenReturn(true);
        when(otpDeliveryService.verificationCode("+84")).thenReturn("123456");
        when(mapper.insertChallengeInEnvironment(
                any(), eq("+84"), eq("901234567"), any(), eq("PRODUCTION"), eq("123456"), anyInt()))
                .thenReturn(1);
        doThrow(new IllegalStateException("ambiguous provider timeout"))
                .when(otpDeliveryService).deliver(any(), any(), any(), any(), anyInt());

        assertThatThrownBy(() -> service.sendOtp(
                new UserRegistrationOtpRequest("+84", "901234567"), "127.0.0.9"))
                .isInstanceOf(BizException.class)
                .hasMessage("USER_OTP_DELIVERY_FAILED");

        verify(loginGuards).insertOtpSendEvent(any(), any());
        verify(mapper, times(2)).invalidateActiveInEnvironment("+84", "901234567", "PRODUCTION");
        Transactional transaction = AppUserRegistrationService.class
                .getMethod("sendOtp", UserRegistrationOtpRequest.class, String.class)
                .getAnnotation(Transactional.class);
        assertThat(transaction.noRollbackFor()).contains(BizException.class);
    }

    @Test
    void registrationOtpRejectsCountryCodesOutsideTheFormalAppWhitelist() {
        var result = service.sendOtp(
                new UserRegistrationOtpRequest("+81", "09012345678"), "127.0.0.3");

        assertThat(result.getCode()).isEqualTo(422);
        verify(loginGuards, never()).initializeOtpSendGuard(any(), any());
        verify(otpDeliveryService, never()).deliver(any(), any(), any(), any(), anyInt());
    }

    @Test
    void registrationCaptchaIsCheckedBeforeAnyChallengeOrSmsIsCreated() {
        when(otpDeliveryService.available("+84")).thenReturn(true);
        when(captchaGate.checkAndConsume(any(), any(), any(), anyInt()))
                .thenReturn(new CaptchaOtpGate.Decision(false, 428, "USER_CAPTCHA_REQUIRED"));

        var result = service.sendOtp(new UserRegistrationOtpRequest("+84", "901234567"), "127.0.0.7");

        assertThat(result.getCode()).isEqualTo(428);
        assertThat(result.getMessage()).isEqualTo("USER_CAPTCHA_REQUIRED");
        verify(otpDeliveryService, never()).deliver(any(), any(), any(), any(), anyInt());
    }

    @Test
    void registrationOtpRejectsInvalidNationalNumberBeforeRateLimitMutation() {
        var result = service.sendOtp(
                new UserRegistrationOtpRequest("+86", "12800138000"), "127.0.0.3");

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("USER_REGISTRATION_PHONE_INVALID");
        verify(loginGuards, never()).initializeOtpSendGuard(any(), any());
        verify(otpDeliveryService, never()).deliver(any(), any(), any(), any(), anyInt());
    }

    @Test
    void registrationOtpPersistsAndDeliversTheCodeSelectedByTheDeliveryAuthority() {
        when(otpDeliveryService.available(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(otpDeliveryService.verificationCode(org.mockito.ArgumentMatchers.anyString())).thenReturn("123456");
        when(mapper.insertChallengeInEnvironment(
                any(), eq("+84"), eq("901234567"), eq("127.0.0.3"),
                eq("PRODUCTION"), eq("123456"), eq(5)))
                .thenReturn(1);

        var result = service.sendOtp(
                new UserRegistrationOtpRequest("+84", "901234567"), "127.0.0.3");

        assertThat(result.getCode()).isZero();
        verify(mapper).insertChallengeInEnvironment(
                any(), eq("+84"), eq("901234567"), eq("127.0.0.3"),
                eq("PRODUCTION"), eq("123456"), eq(5));
        verify(otpDeliveryService).deliver(
                eq("+84"), eq("901234567"), any(), eq("123456"), eq(5));
    }

    @Test
    void equivalentLocalPhoneFormsShareOneRegistrationOtpRateLimitIdentity() {
        when(otpDeliveryService.available(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(otpDeliveryService.verificationCode(org.mockito.ArgumentMatchers.anyString())).thenReturn("123456");
        when(mapper.insertChallengeInEnvironment(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(1);

        var localForm = service.sendOtp(
                new UserRegistrationOtpRequest("+84", "0901234567"), "198.51.100.31");
        var internationalForm = service.sendOtp(
                new UserRegistrationOtpRequest("84", "901234567"), "198.51.100.32");

        assertThat(localForm.getCode()).isZero();
        assertThat(internationalForm.getCode()).isZero();
        ArgumentCaptor<String> otpRateKey = ArgumentCaptor.forClass(String.class);
        verify(loginGuards, times(2)).initializeOtpSendGuard(otpRateKey.capture(), any());
        assertThat(otpRateKey.getAllValues()).containsOnly(otpRateKey.getAllValues().get(0));
    }

    @Test
    void registrationOtpConsumesConfiguredK2TtlCooldownAndRollingLimit() {
        when(configFacade.activeValue("auth.risk.otp_ttl_minutes")).thenReturn(Optional.of("7"));
        when(configFacade.activeValue("auth.risk.otp_send_cooldown_seconds")).thenReturn(Optional.of("90"));
        when(configFacade.activeValue("auth.risk.otp_send_day_limit")).thenReturn(Optional.of("6"));
        when(otpDeliveryService.available(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(otpDeliveryService.verificationCode(org.mockito.ArgumentMatchers.anyString())).thenReturn("123456");
        when(mapper.insertChallengeInEnvironment(any(), any(), any(), any(), any(), any(), eq(7))).thenReturn(1);

        var result = service.sendOtp(new UserRegistrationOtpRequest("+84", "901234567"), "127.0.0.3");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().resendAfterSec()).isEqualTo(90);
        verify(mapper).countRecentPhoneInEnvironment("+84", "901234567", "PRODUCTION", 90);
        verify(mapper).countDailyPhoneInEnvironment("+84", "901234567", "PRODUCTION");
        verify(mapper).insertChallengeInEnvironment(any(), any(), any(), any(), any(), any(), eq(7));
    }

    @Test
    void registrationOtpGuardRejectsConcurrentEquivalentRequestBeforeIssuingAnotherChallenge() {
        UserOtpSendGuardRecord throttled = freshOtpSendGuard();
        throttled.setLastSentAt(LocalDateTime.now());
        when(otpDeliveryService.available(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(loginGuards.lockOtpSendGuard(any())).thenReturn(throttled);

        var result = service.sendOtp(new UserRegistrationOtpRequest("+84", "901234567"), "127.0.0.3");

        assertThat(result.getCode()).isEqualTo(429);
        assertThat(result.getMessage()).isEqualTo("USER_REGISTRATION_OTP_COOLDOWN");
        verify(mapper, never()).insertChallengeInEnvironment(any(), any(), any(), any(), any(), any(), anyInt());
        verify(otpDeliveryService, never()).deliver(any(), any(), any(), any(), anyInt());
    }

    @Test
    void registrationOtpReportsTheSharedRollingDailyLimitInsteadOfCallingItCooldown() {
        when(otpDeliveryService.available(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(configFacade.activeValue("auth.risk.otp_send_day_limit")).thenReturn(Optional.of("5"));
        when(loginGuards.countRecentOtpSendEvents(any(), any())).thenReturn(5);

        var result = service.sendOtp(
                new UserRegistrationOtpRequest("+86", "18700003771"), "198.51.100.34");

        assertThat(result.getCode()).isEqualTo(429);
        assertThat(result.getMessage()).isEqualTo("USER_REGISTRATION_OTP_DAILY_LIMIT");
        verify(mapper, never()).insertChallengeInEnvironment(any(), any(), any(), any(), any(), any(), anyInt());
        verify(otpDeliveryService, never()).deliver(any(), any(), any(), any(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = { "test" })
    void strictIsolatedProfileAtomicallyMarksTheNewUserAndWalletAsSandbox(String profile) {
        when(environment.getActiveProfiles()).thenReturn(new String[] { profile });
        UserEntity sandboxSponsor = user(41L, "NXAB12CD34EF");
        sandboxSponsor.setSandbox(1);
        prepareSuccessfulRegistration(sandboxSponsor);

        ApiResult<UserLoginResponse> result = service.register(new UserRegistrationRequest(
                "+84", "987654321", "REG-H003", "123456", "NexPass9a", "NXAB12CD34EF"),
                "127.0.0.3");

        ArgumentCaptor<UserEntity> inserted = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).insert(inserted.capture());
        verify(userMapper).ensureRegisteredUserWallet(99L, 1);
        assertThat(result.getCode()).isZero();
        assertThat(inserted.getValue().getSandbox()).isEqualTo(1);
    }

    @Test
    void sandboxRegistrationWithoutSponsorCreatesAnIsolatedRootAccountAndWallet() {
        when(environment.getActiveProfiles()).thenReturn(new String[] { "test" });
        prepareRegistrationPrerequisites("REG-H003", "987654321", "127.0.0.3");
        when(passwordEncoder.encode("NexPass9a")).thenReturn("hash");
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(99L);
            return 1;
        }).when(userMapper).insert(any(UserEntity.class));
        when(authService.issueRegisteredSession(any(UserEntity.class), eq("127.0.0.3")))
                .thenReturn(ApiResult.ok(new UserLoginResponse(
                        "access", "Bearer", new UserLoginResponse.UserSession(
                                99L, "+84", "987654321", "Nexion 4321"))));

        ApiResult<UserLoginResponse> result = service.register(new UserRegistrationRequest(
                "+84", "987654321", "REG-H003", "123456", "NexPass9a", null), "127.0.0.3");

        ArgumentCaptor<UserEntity> inserted = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).insert(inserted.capture());
        verify(userMapper).ensureRegisteredUserWallet(99L, 1);
        verify(mapper).insertTeamMemberProjection(99L, 99L, 0, 1);
        verify(authService).issueRegisteredSession(inserted.getValue(), "127.0.0.3");
        verify(outboxService).publish(
                "USER_REGISTRATION", "99", "auth.register_completed", java.util.Map.of("userId", 99L));
        verify(outboxService, org.mockito.Mockito.times(1)).publish(any(), any(), any(), any());
        assertThat(result.getCode()).isZero();
        assertThat(inserted.getValue().getSandbox()).isEqualTo(1);
        assertThat(inserted.getValue().getSponsorUserId()).isNull();
        assertThat(inserted.getValue().getSponsorCode()).isNull();
    }

    @Test
    void strictIsolatedSandboxRegistrationDoesNotConsumeTheSharedLoopbackK1SignupQuota() {
        when(environment.getActiveProfiles()).thenReturn(new String[] { "test" });
        when(mapper.consumeValidChallengeInEnvironment(
                eq("REG-LOCAL"), eq("+84"), eq("0912681799"), eq("SANDBOX"), eq("123456"), anyInt())).thenReturn(1);
        when(mapper.consumedChallengeClientIpInEnvironment(
                "REG-LOCAL", "+84", "0912681799", "SANDBOX")).thenReturn("127.0.0.1");
        when(passwordEncoder.encode("NexPass9a")).thenReturn("hash");
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(109L);
            return 1;
        }).when(userMapper).insert(any(UserEntity.class));
        when(authService.issueRegisteredSession(any(UserEntity.class), eq("127.0.0.1")))
                .thenReturn(ApiResult.ok(new UserLoginResponse(
                        "access", "Bearer", new UserLoginResponse.UserSession(
                                109L, "+84", "0912681799", "Nexion 1799"))));

        ApiResult<UserLoginResponse> result = service.register(new UserRegistrationRequest(
                "+84", "0912681799", "REG-LOCAL", "123456", "NexPass9a", null), "127.0.0.1");

        assertThat(result.getCode()).isZero();
        verify(mapper, never()).k1ParamValueForUpdate(any());
        verify(mapper, never()).countRegisteredAccountsByClientIp24hInEnvironment(any(), any(), anyInt());
        verify(userMapper).ensureRegisteredUserWallet(109L, 1);
    }

    @Test
    void productionRegistrationStillRejectsWhenTheConfiguredK1IpLimitIsReached() {
        when(mapper.consumeValidChallengeInEnvironment(
                eq("REG-PROD-LIMIT"), eq("+84"), eq("0912681798"), eq("PRODUCTION"), eq("123456"), anyInt())).thenReturn(1);
        when(mapper.consumedChallengeClientIpInEnvironment(
                "REG-PROD-LIMIT", "+84", "0912681798", "PRODUCTION")).thenReturn("203.0.113.8");
        when(mapper.k1ParamValueForUpdate("maxSignupPerIp24h")).thenReturn("3");
        when(mapper.countRegisteredAccountsByClientIp24hInEnvironment(
                "203.0.113.8", "PRODUCTION", 0)).thenReturn(3);

        ApiResult<UserLoginResponse> result = service.register(new UserRegistrationRequest(
                "+84", "0912681798", "REG-PROD-LIMIT", "123456", "NexPass9a", null), "203.0.113.8");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("USER_REGISTRATION_K1_IP_LIMIT");
        verify(userMapper, never()).insert(any(UserEntity.class));
        verify(userMapper, never()).ensureRegisteredUserWallet(anyLong(), anyInt());
        verify(authService, never()).issueRegisteredSession(any(), any());
    }

    @Test
    void sandboxRegistrationRejectsAProductionSponsorBeforeUserOrWalletWrites() {
        when(environment.getActiveProfiles()).thenReturn(new String[] { "test" });
        prepareSuccessfulRegistration(user(41L, "NXAB12CD34EF"));

        ApiResult<UserLoginResponse> result = service.register(new UserRegistrationRequest(
                "+84", "987654321", "REG-H003", "123456", "NexPass9a", "NXAB12CD34EF"),
                "127.0.0.3");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("USER_REGISTRATION_SPONSOR_ENVIRONMENT_MISMATCH");
        verify(userMapper, never()).insert(any(UserEntity.class));
        verify(userMapper, never()).ensureRegisteredUserWallet(anyLong(), anyInt());
        verify(authService, never()).issueRegisteredSession(any(), any());
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @Test
    void productionRegistrationRejectsASandboxSponsorBeforeUserOrWalletWrites() {
        UserEntity sandboxSponsor = user(41L, "NXAB12CD34EF");
        sandboxSponsor.setSandbox(1);
        prepareSuccessfulRegistration(sandboxSponsor);

        ApiResult<UserLoginResponse> result = service.register(new UserRegistrationRequest(
                "+84", "987654321", "REG-H003", "123456", "NexPass9a", "NXAB12CD34EF"),
                "127.0.0.3");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("USER_REGISTRATION_SPONSOR_ENVIRONMENT_MISMATCH");
        verify(userMapper, never()).insert(any(UserEntity.class));
        verify(userMapper, never()).ensureRegisteredUserWallet(anyLong(), anyInt());
        verify(authService, never()).issueRegisteredSession(any(), any());
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = { "dev", "prod" })
    void canonicalProfileKeepsTheNewUserAndWalletOutOfSandbox(String profile) {
        when(environment.getActiveProfiles()).thenReturn(new String[] { profile });
        prepareSuccessfulRegistration(user(41L, "NXAB12CD34EF"));

        ApiResult<UserLoginResponse> result = service.register(new UserRegistrationRequest(
                "+84", "987654321", "REG-H003", "123456", "NexPass9a", "NXAB12CD34EF"),
                "127.0.0.3");

        ArgumentCaptor<UserEntity> inserted = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).insert(inserted.capture());
        verify(userMapper).ensureRegisteredUserWallet(99L, 0);
        assertThat(result.getCode()).isZero();
        assertThat(inserted.getValue().getSandbox()).isZero();
        assertThat(inserted.getValue().getClientIp()).isEqualTo("127.0.0.3");
    }

    @Test
    void unknownOrMixedProfilesFailClosedBeforeOtpOrAnyRegistrationWrite() {
        when(environment.getActiveProfiles()).thenReturn(new String[] { "dev", "prod" });

        ApiResult<UserLoginResponse> result = service.register(new UserRegistrationRequest(
                "+84", "987654321", "REG-H003", "123456", "NexPass9a", null), "127.0.0.3");

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("USER_REGISTRATION_PROFILE_FORBIDDEN");
        verify(transactionExecutor, never()).execute(any());
        verify(mapper, never()).consumeValidChallengeInEnvironment(any(), any(), any(), any(), any(), anyInt());
        verify(userMapper, never()).insert(any(UserEntity.class));
        verify(userMapper, never()).ensureRegisteredUserWallet(anyLong(), anyInt());
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @Test
    void unknownSingleProfileFailsClosedBeforeOtpOrAnyRegistrationWrite() {
        when(environment.getActiveProfiles()).thenReturn(new String[] { "staging" });

        ApiResult<UserLoginResponse> result = service.register(new UserRegistrationRequest(
                "+84", "987654321", "REG-H003", "123456", "NexPass9a", null), "127.0.0.3");

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("USER_REGISTRATION_PROFILE_FORBIDDEN");
        verify(transactionExecutor, never()).execute(any());
        verify(mapper, never()).consumeValidChallengeInEnvironment(any(), any(), any(), any(), any(), anyInt());
        verify(userMapper, never()).insert(any(UserEntity.class));
        verify(userMapper, never()).ensureRegisteredUserWallet(anyLong(), anyInt());
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    /**
     * 用户旅程：邀请人把服务端 canonical code 分享给新用户；新用户完成 OTP 后注册。
     * 同一 referral unique-key 的查找必须是单行锁，避免函数扫描把并发注册拖入锁环。
     */
    @Test
    void canonicalizesReferralCodeBeforeLockingSponsorAndPersistsTheSameAttribution() {
        UserEntity sponsor = user(41L, "NXAB12CD34EF");
        sponsor.setNickname("Alice Example");
        prepareSuccessfulRegistration(sponsor);
        TeamAncestorProjection direct = new TeamAncestorProjection();
        direct.setOwnerUserId(41L);
        direct.setLevel(1);
        when(mapper.listActiveSponsorChain(99L, 0)).thenReturn(List.of(direct));

        ApiResult<UserLoginResponse> result = service.register(new UserRegistrationRequest(
                "+84", "987654321", "REG-H003", "123456", "NexPass9a", "nx-ab12-cd34-ef"),
                "127.0.0.3");

        ArgumentCaptor<String> sponsorCode = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UserEntity> inserted = ArgumentCaptor.forClass(UserEntity.class);
        verify(mapper).findSponsorForUpdate(sponsorCode.capture());
        verify(userMapper).insert(inserted.capture());
        verify(userMapper).ensureRegisteredUserWallet(99L, 0);
        verify(mapper).insertTeamMemberProjection(99L, 99L, 0, 0);
        verify(mapper).insertTeamMemberProjection(41L, 99L, 1, 0);
        verify(authService).issueRegisteredSession(any(UserEntity.class), eq("127.0.0.3"));
        assertThat(result.getCode()).isZero();
        assertThat(sponsorCode.getValue()).isEqualTo("NXAB12CD34EF");
        assertThat(inserted.getValue().getSponsorUserId()).isEqualTo(41L);
        assertThat(inserted.getValue().getSponsorCode()).isEqualTo("NXAB12CD34EF");
        assertThat(result.getData().registrationReceipt().sponsorCode()).isEqualTo("NXAB12CD34EF");
        assertThat(result.getData().registrationReceipt().sponsorDisplayName()).isEqualTo("A•••");
        assertThat(result.getData().registrationReceipt().giftStatus()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void duplicateRegistrationDoesNotCreateASecondWalletSessionOrReferralBinding() {
        when(mapper.consumeValidChallengeInEnvironment(
                eq("REG-DUP"), eq("+84"), eq("987654322"), eq("PRODUCTION"), eq("123456"), anyInt())).thenReturn(1);
        UserEntity existing = user(77L, "NXEXISTING77");
        when(userMapper.selectOne(any())).thenReturn(existing);

        ApiResult<UserLoginResponse> result = service.register(new UserRegistrationRequest(
                "+84", "987654322", "REG-DUP", "123456", "NexPass9a", "NXEXISTING77"),
                "127.0.0.4");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("USER_REGISTRATION_ACCOUNT_EXISTS");
        verify(mapper, never()).findSponsorForUpdate(any());
        verify(userMapper, never()).insert(any(UserEntity.class));
        verify(userMapper, never()).ensureRegisteredUserWallet(anyLong(), anyInt());
        verify(authService, never()).issueRegisteredSession(any(), any());
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @Test
    void resolvesLegacyHyphenatedStoredCodeFromTrimmedMixedCaseInputThenLocksThatExactRow() {
        UserEntity legacySponsor = user(41L, "nx-ab12-cd34-ef");
        prepareSuccessfulRegistration(legacySponsor);
        when(mapper.findActiveSponsorsByCanonicalCode("NXAB12CD34EF")).thenReturn(List.of(legacySponsor));
        when(mapper.findSponsorForUpdate("nx-ab12-cd34-ef")).thenReturn(legacySponsor);

        ApiResult<UserLoginResponse> result = service.register(new UserRegistrationRequest(
                "+84", "987654321", "REG-H003", "123456", "NexPass9a", " nx-ab12-cd34-ef "),
                "127.0.0.3");

        assertThat(result.getCode()).isZero();
        verify(mapper).findSponsorForUpdate("nx-ab12-cd34-ef");
    }

    @Test
    void rejectsCanonicalReferralAmbiguityBeforeAnyUniqueRowLockOrSideEffect() {
        when(mapper.consumeValidChallengeInEnvironment(
                eq("REG-AMB"), eq("+84"), eq("987654323"), eq("PRODUCTION"), eq("123456"), anyInt())).thenReturn(1);
        when(mapper.consumedChallengeClientIpInEnvironment("REG-AMB", "+84", "987654323", "PRODUCTION")).thenReturn("127.0.0.5");
        when(mapper.k1ParamValueForUpdate("maxSignupPerIp24h")).thenReturn("3");
        when(mapper.countRegisteredAccountsByClientIp24hInEnvironment(eq("127.0.0.5"), any(), anyInt())).thenReturn(0);
        when(mapper.findActiveSponsorsByCanonicalCode("NXAB12CD34EF")).thenReturn(List.of(
                user(41L, "NXAB12CD34EF"), user(42L, "NX-AB12-CD34-EF")));

        ApiResult<UserLoginResponse> result = service.register(new UserRegistrationRequest(
                "+84", "987654323", "REG-AMB", "123456", "NexPass9a", "nx-ab12-cd34-ef"),
                "127.0.0.5");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("USER_REGISTRATION_SPONSOR_AMBIGUOUS");
        verify(mapper, never()).findSponsorForUpdate(any());
        verify(userMapper, never()).insert(any(UserEntity.class));
        verify(userMapper, never()).ensureRegisteredUserWallet(anyLong(), anyInt());
        verify(authService, never()).issueRegisteredSession(any(), any());
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @Test
    void retriesDeadlockInANewTransactionAndPublishesOneAtomicRegistrationOnlyAfterSuccess() {
        UserEntity sponsor = user(41L, "NXAB12CD34EF");
        prepareSuccessfulRegistration(sponsor);
        when(userMapper.insert(any(UserEntity.class)))
                .thenThrow(new DeadlockLoserDataAccessException("deadlock", new SQLException("deadlock")))
                .thenAnswer(invocation -> {
                    UserEntity user = invocation.getArgument(0);
                    user.setId(99L);
                    return 1;
                });

        ApiResult<UserLoginResponse> result = service.register(new UserRegistrationRequest(
                "+84", "987654321", "REG-H003", "123456", "NexPass9a", "NXAB12CD34EF"),
                "127.0.0.3");

        assertThat(result.getCode()).isZero();
        verify(transactionExecutor, org.mockito.Mockito.times(2)).execute(any());
        verify(userMapper, org.mockito.Mockito.times(2)).insert(any(UserEntity.class));
        verify(userMapper).ensureRegisteredUserWallet(99L, 0);
        verify(authService).issueRegisteredSession(any(UserEntity.class), eq("127.0.0.3"));
        verify(outboxService, org.mockito.Mockito.times(2)).publish(any(), any(), any(), any());
    }

    @Test
    void returnsExplicitRetryable503AfterBoundedLockTimeoutRetriesWithoutWalletOrSessionLeak() {
        org.mockito.Mockito.doThrow(new CannotAcquireLockException("lock timeout"))
                .when(transactionExecutor).execute(any());

        ApiResult<UserLoginResponse> result = service.register(new UserRegistrationRequest(
                "+84", "987654324", "REG-LOCK", "123456", "NexPass9a", null), "127.0.0.6");

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("USER_REGISTRATION_RETRYABLE_CONFLICT");
        verify(transactionExecutor, org.mockito.Mockito.times(3)).execute(any());
        verify(userMapper, never()).insert(any(UserEntity.class));
        verify(userMapper, never()).ensureRegisteredUserWallet(anyLong(), anyInt());
        verify(authService, never()).issueRegisteredSession(any(), any());
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @Test
    void concurrentAliasesForTheSameSponsorResolveThenLockTheSameStoredUniqueReferralRow() throws Exception {
        UserEntity legacySponsor = user(41L, "nx-ab12-cd34-ef");
        AtomicLong ids = new AtomicLong(100L);
        when(mapper.consumeValidChallengeInEnvironment(any(), eq("+84"), any(), any(), eq("123456"), anyInt())).thenReturn(1);
        when(mapper.consumedChallengeClientIpInEnvironment(any(), eq("+84"), any(), any())).thenReturn("127.0.0.8");
        when(mapper.k1ParamValueForUpdate("maxSignupPerIp24h")).thenReturn("3");
        when(mapper.countRegisteredAccountsByClientIp24hInEnvironment(eq("127.0.0.8"), any(), anyInt())).thenReturn(0);
        when(mapper.findActiveSponsorsByCanonicalCode("NXAB12CD34EF")).thenReturn(List.of(legacySponsor));
        when(mapper.findSponsorForUpdate("nx-ab12-cd34-ef")).thenReturn(legacySponsor);
        when(passwordEncoder.encode(any())).thenReturn("hash");
        doAnswer(invocation -> {
            UserEntity inserted = invocation.getArgument(0);
            inserted.setId(ids.incrementAndGet());
            return 1;
        }).when(userMapper).insert(any(UserEntity.class));
        when(authService.issueRegisteredSession(any(), any())).thenReturn(ApiResult.ok(
                new UserLoginResponse("access", "Bearer", new UserLoginResponse.UserSession(1L, "+84", "987654330", "NexGrid"))));

        List<ApiResult<UserLoginResponse>> results = concurrently(
                () -> service.register(request("REG-S1", "987654331", "NX-AB12-CD34-EF"), "127.0.0.8"),
                () -> service.register(request("REG-S2", "987654332", " nxab12cd34ef "), "127.0.0.8"));

        assertThat(results).extracting(ApiResult::getCode).containsOnly(0);
        verify(mapper, org.mockito.Mockito.times(2)).findSponsorForUpdate("nx-ab12-cd34-ef");
        verify(userMapper, org.mockito.Mockito.times(2)).ensureRegisteredUserWallet(anyLong(), eq(0));
        verify(authService, org.mockito.Mockito.times(2)).issueRegisteredSession(any(), any());
        verify(outboxService, org.mockito.Mockito.times(4)).publish(any(), any(), any(), any());
    }

    @Test
    void concurrentSamePhoneOtpCompareAndSetAllowsOnlyOneWalletSessionAndReferralSideEffect() throws Exception {
        AtomicBoolean otpAvailable = new AtomicBoolean(true);
        when(mapper.consumeValidChallengeInEnvironment(
                eq("REG-PHONE"), eq("+84"), eq("987654333"), eq("PRODUCTION"), eq("123456"), anyInt()))
                .thenAnswer(invocation -> otpAvailable.compareAndSet(true, false) ? 1 : 0);
        when(mapper.consumedChallengeClientIpInEnvironment("REG-PHONE", "+84", "987654333", "PRODUCTION")).thenReturn("127.0.0.9");
        when(mapper.k1ParamValueForUpdate("maxSignupPerIp24h")).thenReturn("3");
        when(mapper.countRegisteredAccountsByClientIp24hInEnvironment(eq("127.0.0.9"), any(), anyInt())).thenReturn(0);
        when(passwordEncoder.encode("NexPass9a")).thenReturn("hash");
        doAnswer(invocation -> {
            ((UserEntity) invocation.getArgument(0)).setId(111L);
            return 1;
        }).when(userMapper).insert(any(UserEntity.class));
        when(authService.issueRegisteredSession(any(), any())).thenReturn(ApiResult.ok(
                new UserLoginResponse("access", "Bearer", new UserLoginResponse.UserSession(111L, "+84", "987654333", "Nexion"))));

        List<ApiResult<UserLoginResponse>> results = concurrently(
                () -> service.register(request("REG-PHONE", "987654333", null), "127.0.0.9"),
                () -> service.register(request("REG-PHONE", "987654333", null), "127.0.0.9"));

        assertThat(results).extracting(ApiResult::getCode).containsExactlyInAnyOrder(0, 422);
        verify(userMapper, org.mockito.Mockito.times(1)).insert(any(UserEntity.class));
        verify(userMapper, org.mockito.Mockito.times(1)).ensureRegisteredUserWallet(111L, 0);
        verify(authService, org.mockito.Mockito.times(1)).issueRegisteredSession(any(), any());
        verify(outboxService, org.mockito.Mockito.times(1)).publish(any(), any(), any(), any());
    }

    private void prepareSuccessfulRegistration(UserEntity sponsor) {
        prepareRegistrationPrerequisites("REG-H003", "987654321", "127.0.0.3");
        when(mapper.findActiveSponsorsByCanonicalCode("NXAB12CD34EF")).thenReturn(List.of(sponsor));
        when(mapper.findSponsorForUpdate("NXAB12CD34EF")).thenReturn(sponsor);
        when(passwordEncoder.encode("NexPass9a")).thenReturn("hash");
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(99L);
            return 1;
        }).when(userMapper).insert(any(UserEntity.class));
        when(authService.issueRegisteredSession(any(UserEntity.class), eq("127.0.0.3")))
                .thenReturn(ApiResult.ok(new UserLoginResponse(
                        "access", "Bearer", new UserLoginResponse.UserSession(99L, "+84", "987654321", "Nexion 4321"))));
    }

    private void prepareRegistrationPrerequisites(String challengeNo, String phone, String clientIp) {
        when(mapper.consumeValidChallengeInEnvironment(
                eq(challengeNo), eq("+84"), eq(phone), any(), eq("123456"), anyInt())).thenReturn(1);
        when(mapper.consumedChallengeClientIpInEnvironment(eq(challengeNo), eq("+84"), eq(phone), any())).thenReturn(clientIp);
        when(mapper.k1ParamValueForUpdate("maxSignupPerIp24h")).thenReturn("3");
        when(mapper.countRegisteredAccountsByClientIp24hInEnvironment(eq(clientIp), any(), anyInt())).thenReturn(0);
    }

    private UserEntity user(Long id, String referralCode) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setReferralCode(referralCode);
        user.setStatus("ACTIVE");
        user.setIsDeleted(0);
        return user;
    }

    private UserRegistrationRequest request(String challengeNo, String phone, String sponsorCode) {
        return new UserRegistrationRequest("+84", phone, challengeNo, "123456", "NexPass9a", sponsorCode);
    }

    private UserOtpSendGuardRecord freshOtpSendGuard() {
        UserOtpSendGuardRecord guard = new UserOtpSendGuardRecord();
        guard.setDayStartedAt(LocalDateTime.now().minusHours(25));
        guard.setDaySendCount(0);
        return guard;
    }

    @SafeVarargs
    private final List<ApiResult<UserLoginResponse>> concurrently(
            Callable<ApiResult<UserLoginResponse>>... attempts) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(attempts.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ApiResult<UserLoginResponse>>> futures = java.util.Arrays.stream(attempts)
                    .map(attempt -> pool.submit(() -> {
                        start.await();
                        return attempt.call();
                    }))
                    .toList();
            start.countDown();
            return futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
        } finally {
            pool.shutdownNow();
        }
    }
}
