package ffdd.auth.service.impl;

import ffdd.auth.dto.UserLoginRequest;
import ffdd.auth.dto.UserLoginResponse;
import ffdd.auth.dto.UserRegisterRequest;
import ffdd.auth.service.UserAuthService;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class UserAuthServiceImpl implements UserAuthService {
    @Override
    public UserLoginResponse register(UserRegisterRequest request) {
        return new UserLoginResponse(mockToken(), 10001L, "Stella Miner", "NX4892", "L1", "V0");
    }

    @Override
    public UserLoginResponse login(UserLoginRequest request) {
        return new UserLoginResponse(mockToken(), 10001L, "Stella Miner", "NX4892", "L2", "V1");
    }

    private String mockToken() {
        return "dev-" + UUID.randomUUID();
    }
}

