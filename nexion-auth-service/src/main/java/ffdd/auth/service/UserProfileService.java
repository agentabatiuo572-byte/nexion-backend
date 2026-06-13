package ffdd.auth.service;

import ffdd.auth.dto.UserResponse;
import ffdd.auth.dto.UserPreferenceResponse;
import ffdd.auth.dto.UserPreferenceUpdateRequest;
import ffdd.auth.dto.UserUpdateRequest;

public interface UserProfileService {
    UserResponse current();

    UserResponse update(UserUpdateRequest request);

    UserPreferenceResponse currentPreferences();

    UserPreferenceResponse updatePreferences(UserPreferenceUpdateRequest request);
}
