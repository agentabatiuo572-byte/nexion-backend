package ffdd.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.common.exception.BizException;
import ffdd.system.domain.ContentPage;
import ffdd.system.dto.ContentPageCreateRequest;
import ffdd.system.dto.ContentPageResponse;
import ffdd.system.dto.ContentPageUpdateRequest;
import ffdd.system.mapper.ContentPageMapper;
import ffdd.system.service.SystemContentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class SystemContentServiceImpl implements SystemContentService {
    private final ContentPageMapper contentPageMapper;

    @Override
    public List<ContentPageResponse> list(String query, Integer status, int limit) {
        String normalizedQuery = SystemFieldValidator.trimToNull(query);
        LambdaQueryWrapper<ContentPage> wrapper = new LambdaQueryWrapper<ContentPage>()
                .eq(ContentPage::getIsDeleted, 0)
                .eq(status != null, ContentPage::getStatus, status)
                .and(StringUtils.hasText(normalizedQuery), nested -> nested
                        .like(ContentPage::getPageCode, normalizedQuery)
                        .or()
                        .like(ContentPage::getTitle, normalizedQuery))
                .orderByDesc(ContentPage::getUpdatedAt)
                .last("LIMIT " + SystemFieldValidator.normalizeLimit(limit));
        return contentPageMapper.selectList(wrapper).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public ContentPageResponse getActiveByCode(String pageCode) {
        String normalizedCode = normalizePageCode(pageCode);
        ContentPage page = contentPageMapper.selectOne(new LambdaQueryWrapper<ContentPage>()
                .eq(ContentPage::getPageCode, normalizedCode)
                .eq(ContentPage::getStatus, SystemFieldValidator.ACTIVE)
                .eq(ContentPage::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (page == null) {
            throw new BizException(404, "Content page not found");
        }
        return toResponse(page);
    }

    @Override
    public ContentPageResponse create(ContentPageCreateRequest request) {
        if (request == null) {
            throw new BizException("Content page request is required");
        }
        String normalizedCode = normalizePageCode(request.getPageCode());
        ContentPage existing = contentPageMapper.selectOne(new LambdaQueryWrapper<ContentPage>()
                .eq(ContentPage::getPageCode, normalizedCode)
                .last("LIMIT 1"));
        if (existing != null) {
            throw new BizException(409, "Content page already exists");
        }

        ContentPage page = new ContentPage();
        page.setPageCode(normalizedCode);
        page.setTitle(SystemFieldValidator.requireText(request.getTitle(), "title", 128));
        page.setContent(SystemFieldValidator.normalizeContent(request.getContent()));
        page.setStatus(SystemFieldValidator.normalizeStatus(request.getStatus()));
        page.setIsDeleted(0);
        contentPageMapper.insert(page);
        return toResponse(page);
    }

    @Override
    public ContentPageResponse update(Long id, ContentPageUpdateRequest request) {
        if (id == null) {
            throw new BizException("Content page id is required");
        }
        if (request == null) {
            throw new BizException("Content page request is required");
        }
        ContentPage page = contentPageMapper.selectById(id);
        if (page == null || Integer.valueOf(1).equals(page.getIsDeleted())) {
            throw new BizException(404, "Content page not found");
        }
        if (request.getTitle() != null) {
            page.setTitle(SystemFieldValidator.requireText(request.getTitle(), "title", 128));
        }
        if (request.getContent() != null) {
            page.setContent(SystemFieldValidator.normalizeContent(request.getContent()));
        }
        if (request.getStatus() != null) {
            page.setStatus(SystemFieldValidator.normalizeStatus(request.getStatus()));
        }
        contentPageMapper.updateById(page);
        return toResponse(page);
    }

    private String normalizePageCode(String pageCode) {
        return SystemFieldValidator.normalizeCode(pageCode, "pageCode", 96);
    }

    private ContentPageResponse toResponse(ContentPage page) {
        return new ContentPageResponse(
                page.getId(),
                page.getPageCode(),
                page.getTitle(),
                page.getContent(),
                page.getStatus(),
                page.getCreatedAt(),
                page.getUpdatedAt());
    }
}
