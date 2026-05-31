package ffdd.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.auth.domain.User;
import ffdd.auth.dto.UserQueryRequest;
import ffdd.auth.dto.UserResponse;
import ffdd.auth.dto.UserSearchResponse;
import ffdd.auth.dto.UserStatusUpdateRequest;
import ffdd.auth.dto.UserUpdateRequest;
import ffdd.auth.mapper.UserMapper;
import ffdd.auth.service.UserOpsService;
import ffdd.common.exception.BizException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UserOpsServiceImpl implements UserOpsService {
    private static final Set<String> ALLOWED_STATUSES = Set.of("ACTIVE", "DISABLED", "FROZEN");

    private final UserMapper userMapper;

    @Override
    public Page<UserResponse> page(long current, long size, UserQueryRequest query) {
        Page<User> users = userMapper.selectPage(Page.of(current, size), buildQuery(query));
        Page<UserResponse> result = Page.of(users.getCurrent(), users.getSize(), users.getTotal());
        result.setRecords(users.getRecords().stream().map(this::toResponse).toList());
        return result;
    }

    @Override
    public List<UserSearchResponse> search(String keyword, int limit) {
        String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.length() < 2) {
            return List.of();
        }
        int safeLimit = Math.min(Math.max(limit, 1), 10);
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getIsDeleted, 0)
                .and(wrapper -> wrapper
                        .like(User::getPhone, normalized)
                        .or()
                        .like(User::getNickname, normalized)
                        .or()
                        .like(User::getReferralCode, normalized))
                .orderByDesc(User::getId)
                .last("LIMIT " + safeLimit));
        return users.stream()
                .map(user -> new UserSearchResponse(
                        user.getId(),
                        user.getNickname(),
                        maskPhone(user.getCountryCode(), user.getPhone()),
                        user.getReferralCode(),
                        user.getUserLevel(),
                        user.getVRank(),
                        user.getStatus()))
                .toList();
    }

    @Override
    public UserResponse detail(Long id) {
        return toResponse(requireUser(id));
    }

    @Override
    public UserResponse update(Long id, UserUpdateRequest request) {
        User user = requireUser(id);
        if (request.getNickname() != null) {
            String nickname = request.getNickname().trim();
            if (!StringUtils.hasText(nickname)) {
                throw new BizException("Nickname cannot be blank");
            }
            user.setNickname(nickname);
        }
        if (request.getAvatarUrl() != null) {
            user.setAvatarUrl(normalizedOptional(request.getAvatarUrl()));
        }
        if (request.getLanguage() != null) {
            user.setLanguage(normalizedOptional(request.getLanguage()));
        }
        if (request.getRegion() != null) {
            user.setRegion(normalizedOptional(request.getRegion()));
        }
        if (request.getBio() != null) {
            user.setBio(normalizedOptional(request.getBio()));
        }
        if (request.getTimezone() != null) {
            user.setTimezone(normalizedOptional(request.getTimezone()));
        }
        userMapper.updateById(user);
        return detail(id);
    }

    @Override
    public UserResponse updateStatus(Long id, UserStatusUpdateRequest request) {
        User user = requireUser(id);
        String status = request.getStatus().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(status)) {
            throw new BizException("User status must be ACTIVE, DISABLED, or FROZEN");
        }
        user.setStatus(status);
        userMapper.updateById(user);
        return detail(id);
    }

    private User requireUser(Long id) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getId, id)
                .eq(User::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (user == null) {
            throw new BizException("User does not exist");
        }
        return user;
    }

    private LambdaQueryWrapper<User> buildQuery(UserQueryRequest query) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .eq(User::getIsDeleted, 0);
        if (query == null) {
            return wrapper.orderByDesc(User::getId);
        }
        return wrapper
                .like(StringUtils.hasText(query.getPhone()), User::getPhone, query.getPhone())
                .like(StringUtils.hasText(query.getNickname()), User::getNickname, query.getNickname())
                .like(StringUtils.hasText(query.getReferralCode()), User::getReferralCode, query.getReferralCode())
                .eq(StringUtils.hasText(query.getStatus()), User::getStatus, normalizedUpper(query.getStatus()))
                .eq(StringUtils.hasText(query.getKycStatus()), User::getKycStatus, normalizedUpper(query.getKycStatus()))
                .eq(StringUtils.hasText(query.getUserLevel()), User::getUserLevel, normalizedUpper(query.getUserLevel()))
                .eq(StringUtils.hasText(query.getVRank()), User::getVRank, normalizedUpper(query.getVRank()))
                .orderByDesc(User::getId);
    }

    private UserResponse toResponse(User user) {
        UserResponse response = new UserResponse();
        BeanUtils.copyProperties(user, response);
        response.setPhoneMasked(maskPhone(user.getCountryCode(), user.getPhone()));
        return response;
    }

    private String maskPhone(String countryCode, String phone) {
        if (!StringUtils.hasText(phone)) {
            return "-";
        }
        String suffix = phone.length() <= 4 ? phone : phone.substring(phone.length() - 4);
        return (StringUtils.hasText(countryCode) ? countryCode : "") + "****" + suffix;
    }

    private String normalizedOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private String normalizedUpper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
