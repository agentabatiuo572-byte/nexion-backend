package ffdd.auth.service;

import ffdd.auth.dto.UserResponse;
import ffdd.auth.dto.UserUpdateRequest;

public interface UserProfileService {
    UserResponse current();

    UserResponse update(UserUpdateRequest request);
}
