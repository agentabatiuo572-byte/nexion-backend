package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.platform.dto.PermissionDictionaryQueryRequest;
import ffdd.opsconsole.platform.dto.PermissionDictionaryView;
import ffdd.opsconsole.platform.mapper.AdminPermissionMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

/** A8 权限字典（只读）。platform 包首个分页 service：手动 count + limit/offset。 */
@ApplicationService
@RequiredArgsConstructor
public class OpsPlatformPermissionDictionaryService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminPermissionMapper permissionMapper;

    public ApiResult<PageResult<PermissionDictionaryView>> list(PermissionDictionaryQueryRequest query) {
        PermissionDictionaryQueryRequest normalized = normalize(query);
        long total = permissionMapper.countPermissions(
                normalized.keyword(), normalized.domain(), normalized.permType());
        if (total == 0) {
            return ApiResult.ok(new PageResult<>(0, normalized.pageNum(), normalized.pageSize(), List.of()));
        }
        int limit = normalized.pageSize();
        long requestedOffset = (normalized.pageNum() - 1L) * normalized.pageSize();
        if (requestedOffset >= total || requestedOffset > Integer.MAX_VALUE) {
            return ApiResult.ok(new PageResult<>(total, normalized.pageNum(), normalized.pageSize(), List.of()));
        }
        int offset = (int) requestedOffset;
        List<PermissionDictionaryView> records = permissionMapper.pagePermissions(
                normalized.keyword(), normalized.domain(), normalized.permType(), limit, offset);
        return ApiResult.ok(new PageResult<>(total, normalized.pageNum(), normalized.pageSize(), records));
    }

    public ApiResult<PermissionDictionaryView> detail(String permissionCode) {
        if (!StringUtils.hasText(permissionCode)) {
            return ApiResult.fail(422, "PERMISSION_CODE_REQUIRED");
        }
        PermissionDictionaryView view = permissionMapper.selectPermissionDetail(permissionCode.trim());
        if (view == null) {
            return ApiResult.fail(404, "PERMISSION_NOT_FOUND");
        }
        return ApiResult.ok(view);
    }

    private PermissionDictionaryQueryRequest normalize(PermissionDictionaryQueryRequest query) {
        if (query == null) {
            return new PermissionDictionaryQueryRequest(null, null, null, 1, DEFAULT_PAGE_SIZE);
        }
        String keyword = StringUtils.hasText(query.keyword()) ? query.keyword().trim() : null;
        String domain = StringUtils.hasText(query.domain()) ? query.domain().trim().toUpperCase() : null;
        String permType = StringUtils.hasText(query.permType()) ? query.permType().trim().toUpperCase() : null;
        int pageNum = query.pageNum() == null || query.pageNum() < 1 ? 1 : query.pageNum();
        int pageSize = query.pageSize() == null || query.pageSize() < 1 ? DEFAULT_PAGE_SIZE : query.pageSize();
        if (pageSize > MAX_PAGE_SIZE) {
            pageSize = MAX_PAGE_SIZE;
        }
        return new PermissionDictionaryQueryRequest(keyword, domain, permType, pageNum, pageSize);
    }
}
