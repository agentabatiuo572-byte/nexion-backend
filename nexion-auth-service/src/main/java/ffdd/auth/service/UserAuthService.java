package ffdd.auth.service;

import ffdd.auth.dto.UserLoginRequest;
import ffdd.auth.dto.UserLoginResponse;
import ffdd.auth.dto.UserRegisterRequest;
import ffdd.auth.dto.RegisterSmsCodeRequest;
import ffdd.auth.dto.RegisterSmsCodeResponse;
import ffdd.auth.dto.ReferralSponsorResponse;

public interface UserAuthService {
    RegisterSmsCodeResponse sendRegisterSmsCode(RegisterSmsCodeRequest request);

    UserLoginResponse register(UserRegisterRequest request);

    UserLoginResponse login(UserLoginRequest request);

    ReferralSponsorResponse referralSponsor(String referralCode);
}
