package ffdd.auth.service;

import ffdd.auth.dto.UserLoginRequest;
import ffdd.auth.dto.UserLoginResponse;
import ffdd.auth.dto.UserRegisterRequest;

public interface UserAuthService {
    UserLoginResponse register(UserRegisterRequest request);

    UserLoginResponse login(UserLoginRequest request);
}

