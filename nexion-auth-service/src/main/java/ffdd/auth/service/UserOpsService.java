package ffdd.auth.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.auth.dto.UserQueryRequest;
import ffdd.auth.dto.UserResponse;
import ffdd.auth.dto.UserSearchResponse;
import ffdd.auth.dto.UserStatusUpdateRequest;
import ffdd.auth.dto.UserUpdateRequest;
import java.util.List;

public interface UserOpsService {
    Page<UserResponse> page(long current, long size, UserQueryRequest query);

    List<UserSearchResponse> search(String keyword, int limit);

    UserResponse detail(Long id);

    UserResponse update(Long id, UserUpdateRequest request);

    UserResponse updateStatus(Long id, UserStatusUpdateRequest request);
}
