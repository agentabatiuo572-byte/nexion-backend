package ffdd.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.auth.domain.User;
import ffdd.auth.dto.UserStatusUpdateRequest;
import ffdd.auth.dto.UserUpdateRequest;
import ffdd.auth.mapper.UserMapper;
import ffdd.auth.service.impl.UserOpsServiceImpl;
import ffdd.common.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UserOpsServiceTest {
    private final UserMapper userMapper = org.mockito.Mockito.mock(UserMapper.class);
    private final UserOpsService service = new UserOpsServiceImpl(userMapper);

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

        var result = service.detail(10001L);

        assertThat(result.getId()).isEqualTo(10001L);
        assertThat(result.getPhone()).isEqualTo("4150004892");
        assertThat(result.getBio()).isEqualTo("Building mobile mining habits.");
        assertThat(result.getTimezone()).isEqualTo("Asia/Singapore (UTC+8)");
        assertThat(result).hasNoNullFieldsOrPropertiesExcept("avatarUrl", "sponsorUserId", "sponsorCode", "region", "createdAt", "updatedAt");
    }

    @Test
    void updateOnlyAllowsProfileFields() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user(10001L));
        UserUpdateRequest request = new UserUpdateRequest();
        request.setNickname("New Name");
        request.setLanguage("zh-CN");
        request.setRegion("CN");
        request.setBio("Updated profile bio");
        request.setTimezone("Asia/Hong_Kong (UTC+8)");

        service.update(10001L, request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getNickname()).isEqualTo("New Name");
        assertThat(captor.getValue().getLanguage()).isEqualTo("zh-CN");
        assertThat(captor.getValue().getRegion()).isEqualTo("CN");
        assertThat(captor.getValue().getBio()).isEqualTo("Updated profile bio");
        assertThat(captor.getValue().getTimezone()).isEqualTo("Asia/Hong_Kong (UTC+8)");
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
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
}
