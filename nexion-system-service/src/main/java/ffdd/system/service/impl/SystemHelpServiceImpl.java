package ffdd.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.common.exception.BizException;
import ffdd.system.domain.HelpArticle;
import ffdd.system.dto.HelpArticleCreateRequest;
import ffdd.system.dto.HelpArticleResponse;
import ffdd.system.dto.HelpArticleUpdateRequest;
import ffdd.system.mapper.HelpArticleMapper;
import ffdd.system.service.SystemHelpService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class SystemHelpServiceImpl implements SystemHelpService {
    private final HelpArticleMapper helpArticleMapper;

    @Override
    public List<HelpArticleResponse> list(String query, Integer status, int limit) {
        String normalizedQuery = SystemFieldValidator.trimToNull(query);
        LambdaQueryWrapper<HelpArticle> wrapper = new LambdaQueryWrapper<HelpArticle>()
                .eq(HelpArticle::getIsDeleted, 0)
                .eq(status != null, HelpArticle::getStatus, status)
                .and(StringUtils.hasText(normalizedQuery), nested -> nested
                        .like(HelpArticle::getArticleCode, normalizedQuery)
                        .or()
                        .like(HelpArticle::getTitle, normalizedQuery))
                .orderByAsc(HelpArticle::getSortOrder)
                .orderByDesc(HelpArticle::getUpdatedAt)
                .last("LIMIT " + SystemFieldValidator.normalizeLimit(limit));
        return helpArticleMapper.selectList(wrapper).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public HelpArticleResponse getActiveByCode(String articleCode) {
        String normalizedCode = normalizeArticleCode(articleCode);
        HelpArticle article = helpArticleMapper.selectOne(new LambdaQueryWrapper<HelpArticle>()
                .eq(HelpArticle::getArticleCode, normalizedCode)
                .eq(HelpArticle::getStatus, SystemFieldValidator.ACTIVE)
                .eq(HelpArticle::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (article == null) {
            throw new BizException(404, "Help article not found");
        }
        return toResponse(article);
    }

    @Override
    public HelpArticleResponse create(HelpArticleCreateRequest request) {
        if (request == null) {
            throw new BizException("Help article request is required");
        }
        String normalizedCode = normalizeArticleCode(request.getArticleCode());
        HelpArticle existing = helpArticleMapper.selectOne(new LambdaQueryWrapper<HelpArticle>()
                .eq(HelpArticle::getArticleCode, normalizedCode)
                .last("LIMIT 1"));
        if (existing != null) {
            throw new BizException(409, "Help article already exists");
        }

        HelpArticle article = new HelpArticle();
        article.setArticleCode(normalizedCode);
        article.setTitle(SystemFieldValidator.requireText(request.getTitle(), "title", 128));
        article.setContent(SystemFieldValidator.normalizeContent(request.getContent()));
        article.setSortOrder(SystemFieldValidator.normalizeSortOrder(request.getSortOrder()));
        article.setStatus(SystemFieldValidator.normalizeStatus(request.getStatus()));
        article.setIsDeleted(0);
        helpArticleMapper.insert(article);
        return toResponse(article);
    }

    @Override
    public HelpArticleResponse update(Long id, HelpArticleUpdateRequest request) {
        if (id == null) {
            throw new BizException("Help article id is required");
        }
        if (request == null) {
            throw new BizException("Help article request is required");
        }
        HelpArticle article = helpArticleMapper.selectById(id);
        if (article == null || Integer.valueOf(1).equals(article.getIsDeleted())) {
            throw new BizException(404, "Help article not found");
        }
        if (request.getTitle() != null) {
            article.setTitle(SystemFieldValidator.requireText(request.getTitle(), "title", 128));
        }
        if (request.getContent() != null) {
            article.setContent(SystemFieldValidator.normalizeContent(request.getContent()));
        }
        if (request.getSortOrder() != null) {
            article.setSortOrder(SystemFieldValidator.normalizeSortOrder(request.getSortOrder()));
        }
        if (request.getStatus() != null) {
            article.setStatus(SystemFieldValidator.normalizeStatus(request.getStatus()));
        }
        helpArticleMapper.updateById(article);
        return toResponse(article);
    }

    private String normalizeArticleCode(String articleCode) {
        return SystemFieldValidator.normalizeCode(articleCode, "articleCode", 96);
    }

    private HelpArticleResponse toResponse(HelpArticle article) {
        return new HelpArticleResponse(
                article.getId(),
                article.getArticleCode(),
                article.getTitle(),
                article.getContent(),
                article.getSortOrder(),
                article.getStatus(),
                article.getCreatedAt(),
                article.getUpdatedAt());
    }
}
