package ffdd.auth.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.auth.dto.UserQueryRequest;
import ffdd.auth.dto.UserResponse;
import ffdd.auth.dto.UserSearchResponse;
import ffdd.auth.dto.UserStatusUpdateRequest;
import ffdd.auth.dto.UserUpdateRequest;
import ffdd.auth.service.UserOpsService;
import ffdd.common.api.ApiResult;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/users")
@RequiredArgsConstructor
public class UserOpsController {
    private final UserOpsService userOpsService;

    @GetMapping("/page")
    @PreAuthorize("hasAuthority('PERM_USER_READ')")
    public ApiResult<Page<UserResponse>> page(@ModelAttribute UserQueryRequest query,
                                              @RequestParam(defaultValue = "1") long current,
                                              @RequestParam(defaultValue = "20") long size) {
        return ApiResult.ok(userOpsService.page(current, size, query));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('PERM_USER_READ')")
    public ApiResult<List<UserSearchResponse>> search(@RequestParam String keyword,
                                                      @RequestParam(defaultValue = "10") int limit) {
        return ApiResult.ok(userOpsService.search(keyword, limit));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_USER_READ')")
    public ApiResult<UserResponse> detail(@PathVariable Long id) {
        return ApiResult.ok(userOpsService.detail(id));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_USER_WRITE')")
    public ApiResult<UserResponse> update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) {
        return ApiResult.ok(userOpsService.update(id, request));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('PERM_USER_WRITE')")
    public ApiResult<UserResponse> updateStatus(@PathVariable Long id, @Valid @RequestBody UserStatusUpdateRequest request) {
        return ApiResult.ok(userOpsService.updateStatus(id, request));
    }
}
