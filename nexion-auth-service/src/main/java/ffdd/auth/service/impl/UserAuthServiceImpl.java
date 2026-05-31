package ffdd.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.auth.domain.User;
import ffdd.auth.dto.RegisterSmsCodeRequest;
import ffdd.auth.dto.RegisterSmsCodeResponse;
import ffdd.auth.dto.UserLoginRequest;
import ffdd.auth.dto.UserLoginResponse;
import ffdd.auth.dto.UserRegisterRequest;
import ffdd.auth.mapper.UserMapper;
import ffdd.auth.service.UserAuthService;
import ffdd.common.exception.BizException;
import ffdd.common.security.JwtTokenProvider;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UserAuthServiceImpl implements UserAuthService {
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final List<String> USER_AUTHORITIES = List.of("ROLE_USER");
    private static final String REGISTER_SMS_CODE = "123456";
    private static final int REGISTER_SMS_CODE_TTL_SECONDS = 300;

    private final UserMapper userMapper;
    private final JwtTokenProvider tokenProvider;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public RegisterSmsCodeResponse sendRegisterSmsCode(RegisterSmsCodeRequest request) {
        if (findByPhone(request.getCountryCode(), request.getPhone()) != null) {
            throw new BizException("Phone number is already registered");
        }
        return new RegisterSmsCodeResponse(REGISTER_SMS_CODE_TTL_SECONDS);
    }

    @Override
    public UserLoginResponse register(UserRegisterRequest request) {
        if (!REGISTER_SMS_CODE.equals(request.getVerificationCode())) {
            throw new BizException("Verification code is incorrect");
        }
        User existing = findByPhone(request.getCountryCode(), request.getPhone());
        if (existing != null) {
            throw new BizException("Phone number is already registered");
        }

        User sponsor = findSponsor(request.getReferralCode());
        User user = new User();
        user.setCountryCode(request.getCountryCode());
        user.setPhone(request.getPhone());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(defaultNickname(request.getPhone()));
        user.setReferralCode(nextReferralCode(request.getPhone()));
        if (sponsor != null) {
            user.setSponsorUserId(sponsor.getId());
            user.setSponsorCode(sponsor.getReferralCode());
        }
        user.setKycStatus("PENDING");
        user.setUserLevel("L1");
        user.setVRank("V0");
        user.setStatus(STATUS_ACTIVE);
        user.setLanguage("en-US");
        user.setRegion(null);
        user.setIsDeleted(0);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            throw new BizException("Phone number or referral code is already registered");
        }
        return buildLoginResponse(user);
    }

    @Override
    public UserLoginResponse login(UserLoginRequest request) {
        User user = findByPhone(request.getCountryCode(), request.getPhone());
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BizException("Phone number or password is incorrect");
        }
        if (!STATUS_ACTIVE.equals(user.getStatus())) {
            throw new BizException(403, "User account is not active");
        }
        return buildLoginResponse(user);
    }

    private User findByPhone(String countryCode, String phone) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getCountryCode, countryCode)
                .eq(User::getPhone, phone)
                .eq(User::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private User findSponsor(String referralCode) {
        if (!StringUtils.hasText(referralCode)) {
            return null;
        }
        User sponsor = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getReferralCode, referralCode)
                .eq(User::getStatus, STATUS_ACTIVE)
                .eq(User::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (sponsor == null) {
            throw new BizException("Referral code does not exist");
        }
        return sponsor;
    }

    private UserLoginResponse buildLoginResponse(User user) {
        String subjectName = user.getCountryCode() + user.getPhone();
        String token = tokenProvider.createToken(user.getId(), "USER", subjectName, USER_AUTHORITIES);
        return new UserLoginResponse(
                token,
                user.getId(),
                user.getNickname(),
                user.getAvatarUrl(),
                user.getReferralCode(),
                user.getUserLevel(),
                user.getVRank());
    }

    private String defaultNickname(String phone) {
        String suffix = phone.length() > 4 ? phone.substring(phone.length() - 4) : phone;
        return "Nexion User " + suffix;
    }

    private String nextReferralCode(String phone) {
        String suffix = phone.length() > 4 ? phone.substring(phone.length() - 4) : phone;
        for (int i = 0; i < 10; i++) {
            String candidate = "NX" + suffix + ThreadLocalRandom.current().nextInt(100, 1000);
            if (referralCodeAvailable(candidate)) {
                return candidate;
            }
        }
        String fallback = "NX" + Long.toString(System.currentTimeMillis(), 36).toUpperCase(Locale.ROOT);
        if (referralCodeAvailable(fallback)) {
            return fallback;
        }
        return "NX" + Long.toString(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE), 36)
                .toUpperCase(Locale.ROOT);
    }

    private boolean referralCodeAvailable(String referralCode) {
        return userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getReferralCode, referralCode)) == 0;
    }
}
