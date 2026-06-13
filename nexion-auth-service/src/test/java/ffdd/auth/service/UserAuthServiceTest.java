package ffdd.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.auth.domain.User;
import ffdd.auth.dto.RegisterSmsCodeRequest;
import ffdd.auth.dto.UserRegisterRequest;
import ffdd.auth.mapper.UserMapper;
import ffdd.auth.service.impl.UserAuthServiceImpl;
import ffdd.common.exception.BizException;
import ffdd.common.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class UserAuthServiceTest {
    private final UserMapper userMapper = org.mockito.Mockito.mock(UserMapper.class);
    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final UserAuthService service = new UserAuthServiceImpl(
            userMapper,
            new JwtTokenProvider("nexion-development-secret-key-change-me-please", 1440),
            jdbcTemplate);

    @Test
    void sendRegisterSmsCodeReturnsTtlWhenPhoneIsAvailable() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        var result = service.sendRegisterSmsCode(smsRequest());

        assertThat(result.getExpiresInSeconds()).isEqualTo(300);
    }

    @Test
    void sendRegisterSmsCodeRejectsRegisteredPhone() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingUser());

        assertThatThrownBy(() -> service.sendRegisterSmsCode(smsRequest()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void registerRejectsMissingOrWrongVerificationCode() {
        UserRegisterRequest request = registerRequest("000000");

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Verification code is incorrect");
    }

    @Test
    void registerWithFixedVerificationCodeCreatesUserAndReturnsToken() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        org.mockito.Mockito.doAnswer(invocation -> {
                    User user = invocation.getArgument(0);
                    user.setId(10001L);
                    return 1;
                })
                .when(userMapper)
                .insert(any(User.class));

        var result = service.register(registerRequest("123456"));

        assertThat(result.getToken()).isNotBlank();
        assertThat(result.getUserId()).isEqualTo(10001L);
        assertThat(result.getUserLevel()).isEqualTo("L1");
        assertThat(result.getSessionId()).startsWith("US-10001-");
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("INSERT INTO nx_user_session"),
                org.mockito.ArgumentMatchers.eq(10001L),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("uniapp / mobile app"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getPhone()).isEqualTo("590530123456");
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void registerRejectsDuplicatePhoneAfterVerificationCodePasses() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingUser());

        assertThatThrownBy(() -> service.register(registerRequest("123456")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void registerRejectsUnknownReferralCode() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        UserRegisterRequest request = registerRequest("123456");
        request.setReferralCode("NOPE");

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Referral code does not exist");
    }

    @Test
    void referralSponsorReturnsPublicSponsorSummary() {
        User sponsor = existingUser();
        sponsor.setId(10001L);
        sponsor.setNickname("Avery Sponsor");
        sponsor.setUserLevel("L3");
        sponsor.setVRank("V2");
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(sponsor);
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(8L);

        var result = service.referralSponsor("NX3456");

        assertThat(result.getReferralCode()).isEqualTo("NX3456");
        assertThat(result.getSponsorUserId()).isEqualTo(10001L);
        assertThat(result.getSponsorName()).isEqualTo("Avery Sponsor");
        assertThat(result.getVRank()).isEqualTo("V2");
        assertThat(result.getDirectReferrals()).isEqualTo(8L);
    }

    private RegisterSmsCodeRequest smsRequest() {
        RegisterSmsCodeRequest request = new RegisterSmsCodeRequest();
        request.setCountryCode("+1");
        request.setPhone("590530123456");
        return request;
    }

    private UserRegisterRequest registerRequest(String verificationCode) {
        UserRegisterRequest request = new UserRegisterRequest();
        request.setCountryCode("+1");
        request.setPhone("590530123456");
        request.setPassword("App123456");
        request.setVerificationCode(verificationCode);
        return request;
    }

    private User existingUser() {
        User user = new User();
        user.setId(1L);
        user.setCountryCode("+1");
        user.setPhone("590530123456");
        user.setReferralCode("NX3456");
        user.setStatus("ACTIVE");
        user.setIsDeleted(0);
        return user;
    }
}
