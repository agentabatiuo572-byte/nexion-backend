package ffdd.auth.service;

import ffdd.auth.dto.AdminChangePasswordRequest;
import ffdd.auth.dto.AdminLoginRequest;
import ffdd.auth.dto.AdminLoginResponse;
import ffdd.auth.dto.AdminProfileResponse;
import ffdd.auth.dto.AdminProfileUpdateRequest;

public interface AdminAuthService {
    AdminLoginResponse login(AdminLoginRequest request);

    AdminProfileResponse current();

    AdminProfileResponse updateProfile(AdminProfileUpdateRequest request);

    void changePassword(AdminChangePasswordRequest request);
}
