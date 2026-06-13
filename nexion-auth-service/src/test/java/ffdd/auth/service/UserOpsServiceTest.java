package ffdd.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.auth.domain.User;
import ffdd.auth.dto.UserImpersonationEndRequest;
import ffdd.auth.dto.UserImpersonationStartRequest;
import ffdd.auth.dto.UserPasswordResetLinkRequest;
import ffdd.auth.dto.UserSessionRevokeRequest;
import ffdd.auth.dto.UserStatusUpdateRequest;
import ffdd.auth.dto.UserTwoFactorAdminRequest;
import ffdd.auth.dto.UserUpdateRequest;
import ffdd.auth.mapper.UserMapper;
import ffdd.auth.service.impl.UserOpsServiceImpl;
import ffdd.common.exception.BizException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class UserOpsServiceTest {
    private final UserMapper userMapper = org.mockito.Mockito.mock(UserMapper.class);
    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final UserOpsService service = new UserOpsServiceImpl(userMapper, jdbcTemplate);

    @Test
    void searchReturnsMaskedTopTenUsers() {
        when(userMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(user(10001L)));

        var result = service.search("4892", 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUserId()).isEqualTo(10001L);
        assertThat(result.get(0).getPhoneMasked()).isEqualTo("+1****4892");
    }

    @Test
    void detailDoesNotExposePasswordHash() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user(10001L));
        mockLevelConfig();
        when(jdbcTemplate.queryForObject(any(String.class), any(Class.class), any())).thenReturn(new BigDecimal("2.50"));
        when(jdbcTemplate.queryForMap(any(String.class), any())).thenReturn(Map.of("walletAddress", "0x4892nexiondemo"));

        var result = service.detail(10001L);

        assertThat(result.getId()).isEqualTo(10001L);
        assertThat(result.getPhone()).isEqualTo("4150004892");
        assertThat(result.getBio()).isEqualTo("Building mobile mining habits.");
        assertThat(result.getTimezone()).isEqualTo("Asia/Singapore (UTC+8)");
        assertThat(result.getUserLevelName()).isEqualTo("New Contributor");
        assertThat(result.getNextUserLevel()).isEqualTo("L2");
        assertThat(result.getNextUserLevelName()).isEqualTo("Active Contributor");
        assertThat(result.getUserLevelProgressPercent()).isEqualTo(50);
        assertThat(result.getWalletAddress()).isEqualTo("0x4892nexiondemo");
        assertThat(result.getWalletPaired()).isTrue();
        assertThat(result).hasNoNullFieldsOrPropertiesExcept("avatarUrl", "sponsorUserId", "sponsorCode", "region", "createdAt", "updatedAt");
    }

    @Test
    void detailFallsBackWhenLevelConfigIsMissing() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user(10001L));
        when(jdbcTemplate.queryForList(any(String.class))).thenReturn(List.of());

        var result = service.detail(10001L);

        assertThat(result.getUserLevelName()).isEqualTo("L1");
        assertThat(result.getNextUserLevel()).isNull();
        assertThat(result.getUserLevelProgressPercent()).isEqualTo(0);
        assertThat(result.getWalletPaired()).isFalse();
    }

    @Test
    void updateOnlyAllowsProfileFields() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user(10001L));
        UserUpdateRequest request = new UserUpdateRequest();
        request.setNickname("New Name");
        request.setAvatarUrl("auth/users/avatar/avatar.jpg");
        request.setLanguage("zh-CN");
        request.setRegion("CN");
        request.setBio("Updated profile bio");
        request.setTimezone("Asia/Hong_Kong (UTC+8)");

        service.update(10001L, request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getNickname()).isEqualTo("New Name");
        assertThat(captor.getValue().getAvatarUrl()).isEqualTo("auth/users/avatar/avatar.jpg");
        assertThat(captor.getValue().getLanguage()).isEqualTo("zh-CN");
        assertThat(captor.getValue().getRegion()).isEqualTo("CN");
        assertThat(captor.getValue().getBio()).isEqualTo("Updated profile bio");
        assertThat(captor.getValue().getTimezone()).isEqualTo("Asia/Hong_Kong (UTC+8)");
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void updateAcceptsBuiltInAvatarKey() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user(10001L));
        UserUpdateRequest request = new UserUpdateRequest();
        request.setAvatarUrl("mech:cyan");

        service.update(10001L, request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getAvatarUrl()).isEqualTo("mech:cyan");
    }

    @Test
    void updateRejectsManualAvatarUrl() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user(10001L));
        UserUpdateRequest request = new UserUpdateRequest();
        request.setAvatarUrl("https://cdn.example.com/avatar.jpg");

        assertThatThrownBy(() -> service.update(10001L, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Avatar must be uploaded");
    }

    @Test
    void updateStatusRejectsUnknownStatus() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user(10001L));
        UserStatusUpdateRequest request = new UserStatusUpdateRequest();
        request.setStatus("LOCKED");

        assertThatThrownBy(() -> service.updateStatus(10001L, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("ACTIVE, DISABLED, or FROZEN");
    }

    @Test
    void requestPasswordResetLinkCreatesQueuedRequestWithoutPassword() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user(10001L));
        UserPasswordResetLinkRequest request = new UserPasswordResetLinkRequest();
        request.setOperator("pc-c2");
        request.setReason("user support request");

        var result = service.requestPasswordResetLink(10001L, request);

        assertThat(result.getUserId()).isEqualTo(10001L);
        assertThat(result.getResetRequestNo()).startsWith("RESET-10001-");
        assertThat(result.getDeliveryStatus()).isEqualTo("QUEUED");
        assertThat(result.getRecipientMasked()).isEqualTo("+1****4892");
        assertThat(result.getOperator()).isEqualTo("pc-c2");
        verify(userMapper, org.mockito.Mockito.never()).updateById(any(User.class));
    }

    @Test
    void startImpersonationCreatesServerSessionWithoutMutatingUser() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user(10001L));
        UserImpersonationStartRequest request = new UserImpersonationStartRequest();
        request.setOperator("pc-c2");
        request.setReason("support read-only check");
        request.setTtlMinutes(45);

        var result = service.startImpersonation(10001L, request);

        assertThat(result.getUserId()).isEqualTo(10001L);
        assertThat(result.getSessionNo()).startsWith("IMP-10001-");
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getOperator()).isEqualTo("pc-c2");
        assertThat(result.getTtlMinutes()).isEqualTo(30);
        assertThat(result.getExpiresAt()).isAfter(result.getStartedAt());
        verify(userMapper, org.mockito.Mockito.never()).updateById(any(User.class));
    }

    @Test
    void endImpersonationReturnsEndedSession() {
        UserImpersonationEndRequest request = new UserImpersonationEndRequest();
        request.setOperator("pc-c2");
        request.setReason("support finished");

        var result = service.endImpersonation("IMP-10001-ABCDEF123456", request);

        assertThat(result.getUserId()).isEqualTo(10001L);
        assertThat(result.getSessionNo()).isEqualTo("IMP-10001-ABCDEF123456");
        assertThat(result.getStatus()).isEqualTo("ENDED");
        assertThat(result.getEndedAt()).isNotNull();
    }

    @Test
    void listSessionsReturnsServerCanonicalSessionRows() {
        when(jdbcTemplate.queryForList(any(String.class), any(Long.class), any(Integer.class))).thenReturn(List.of(Map.of(
                "userId", 10001L,
                "refreshTokenId", "rt_10001",
                "deviceName", "iPhone 15",
                "clientIp", "104.28.1.1",
                "twoFactorEnabled", 1,
                "createdAt", LocalDateTime.now().minusMinutes(5),
                "expiresAt", LocalDateTime.now().plusDays(7))));

        var result = service.listSessions(10001L, 500);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUserId()).isEqualTo(10001L);
        assertThat(result.get(0).getRefreshTokenId()).isEqualTo("rt_10001");
        assertThat(result.get(0).getStatus()).isEqualTo("ACTIVE");
        assertThat(result.get(0).getTwoFactorEnabled()).isTrue();
        verify(jdbcTemplate).queryForList(contains("AND s.user_id = ?"), eq(10001L), eq(100));
    }

    @Test
    void revokeSessionWritesRevokedAtAndReturnsRevokedRow() {
        when(jdbcTemplate.update(any(String.class), any(String.class))).thenReturn(1);
        when(jdbcTemplate.queryForMap(any(String.class), any(String.class))).thenReturn(Map.of(
                "userId", 10001L,
                "refreshTokenId", "rt_10001",
                "deviceName", "iPhone 15",
                "clientIp", "104.28.1.1",
                "twoFactorEnabled", 1,
                "createdAt", LocalDateTime.now().minusMinutes(5),
                "expiresAt", LocalDateTime.now().plusDays(7),
                "revokedAt", LocalDateTime.now()));
        UserSessionRevokeRequest request = new UserSessionRevokeRequest();
        request.setOperator("pc-c5");
        request.setReason("risk operator forceout");

        var result = service.revokeSession("rt_10001", request);

        assertThat(result.getStatus()).isEqualTo("REVOKED");
        assertThat(result.getRevokedAt()).isNotBlank();
    }

    @Test
    void disableTwoFactorWritesUserSecurityAndReturnsDisabledState() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user(10001L));
        when(jdbcTemplate.update(contains("INSERT INTO nx_user_security"), eq(10001L))).thenReturn(1);
        UserTwoFactorAdminRequest request = new UserTwoFactorAdminRequest();
        request.setOperator("pc-c5");
        request.setReason("KYC second check passed");

        var result = service.disableTwoFactor(10001L, request);

        assertThat(result.getUserId()).isEqualTo(10001L);
        assertThat(result.getTwoFactorEnabled()).isFalse();
        assertThat(result.getOperator()).isEqualTo("pc-c5");
        assertThat(result.getReason()).isEqualTo("KYC second check passed");
        assertThat(result.getUpdatedAt()).isNotBlank();
        verify(jdbcTemplate).update(contains("two_factor_enabled = 0"), eq(10001L));
    }

    @Test
    void pageConvertsUsersToSafeResponses() {
        Page<User> page = Page.of(1, 10);
        page.setRecords(List.of(user(10001L)));
        page.setTotal(1);
        when(userMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);

        var result = service.page(1, 10, null);

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords()).extracting("id").containsExactly(10001L);
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setCountryCode("+1");
        user.setPhone("4150004892");
        user.setPasswordHash("$2a$10$secret");
        user.setNickname("Stella Miner");
        user.setReferralCode("NX4892");
        user.setKycStatus("PENDING");
        user.setUserLevel("L1");
        user.setVRank("V0");
        user.setStatus("ACTIVE");
        user.setLanguage("en-US");
        user.setBio("Building mobile mining habits.");
        user.setTimezone("Asia/Singapore (UTC+8)");
        user.setIsDeleted(0);
        return user;
    }

    private void mockLevelConfig() {
        when(jdbcTemplate.queryForList(any(String.class))).thenReturn(List.of(
                Map.of("levelCode", "L0", "levelName", "Visitor", "entryCondition", "Not registered", "sortOrder", 0, "status", 1),
                Map.of("levelCode", "L1", "levelName", "New Contributor", "entryCondition", "Registered with mobile compute access", "sortOrder", 1, "status", 1),
                Map.of("levelCode", "L2", "levelName", "Active Contributor", "entryCondition", "Earned 5+ USDT", "sortOrder", 2, "status", 1)));
    }
}
