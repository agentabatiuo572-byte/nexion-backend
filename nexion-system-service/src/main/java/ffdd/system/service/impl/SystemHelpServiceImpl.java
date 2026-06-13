package ffdd.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.system.domain.HelpArticle;
import ffdd.system.dto.HelpArticleCreateRequest;
import ffdd.system.dto.HelpArticleResponse;
import ffdd.system.dto.HelpArticleUpdateRequest;
import ffdd.system.mapper.HelpArticleMapper;
import ffdd.system.service.SystemHelpService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class SystemHelpServiceImpl implements SystemHelpService {
    private static final Set<String> LEARN_CATEGORIES = Set.of("basics", "earn", "team", "wealth", "security", "help");
    private static final Set<String> LEARN_LEVELS = Set.of("beginner", "intermediate", "advanced");
    private static final Set<String> LEARN_FORMATS = Set.of("article", "video", "quiz", "guide", "interactive");
    private static final BigDecimal ZERO_REWARD = BigDecimal.ZERO.setScale(6);

    private final HelpArticleMapper helpArticleMapper;

    @Override
    public List<HelpArticleResponse> list(String query, Integer status, int limit) {
        LambdaQueryWrapper<HelpArticle> wrapper = baseQuery(query, status)
                .orderByAsc(HelpArticle::getSortOrder)
                .orderByDesc(HelpArticle::getUpdatedAt)
                .last("LIMIT " + SystemFieldValidator.normalizeLimit(limit));
        return helpArticleMapper.selectList(wrapper).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public PageResult<HelpArticleResponse> page(String query, Integer status, long pageNum, long pageSize) {
        long normalizedPageNum = Math.max(1, pageNum);
        long normalizedPageSize = Math.max(1, Math.min(pageSize, 100));
        Page<HelpArticle> page = helpArticleMapper.selectPage(
                new Page<>(normalizedPageNum, normalizedPageSize),
                baseQuery(query, status)
                        .orderByAsc(HelpArticle::getSortOrder)
                        .orderByDesc(HelpArticle::getUpdatedAt));
        return new PageResult<>(
                page.getTotal(),
                page.getCurrent(),
                page.getSize(),
                page.getRecords().stream().map(this::toResponse).toList());
    }

    @Override
    public HelpArticleResponse getById(Long id) {
        if (id == null) {
            throw new BizException("Help article id is required");
        }
        HelpArticle article = helpArticleMapper.selectById(id);
        if (article == null || Integer.valueOf(1).equals(article.getIsDeleted())) {
            throw new BizException(404, "Help article not found");
        }
        return toResponse(article);
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
        article.setCategory(normalizeToken(request.getCategory(), "category", LEARN_CATEGORIES, "help"));
        article.setLevel(normalizeToken(request.getLevel(), "level", LEARN_LEVELS, "beginner"));
        article.setFormat(normalizeToken(request.getFormat(), "format", LEARN_FORMATS, "article"));
        article.setDurationMin(normalizeNonNegative(request.getDurationMin(), 5, 1_000_000, "durationMin"));
        article.setRewardNex(normalizeReward(request.getRewardNex()));
        article.setProgressPct(normalizeNonNegative(request.getProgressPct(), 0, 100, "progressPct"));
        article.setFeatured(normalizeFlag(request.getFeatured()));
        article.setEmoji(normalizeShortText(request.getEmoji(), "📘", 16));
        article.setTint(normalizeShortText(request.getTint(), "#c6ff3a", 32));
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
        if (request.getCategory() != null) {
            article.setCategory(normalizeToken(request.getCategory(), "category", LEARN_CATEGORIES, "help"));
        }
        if (request.getLevel() != null) {
            article.setLevel(normalizeToken(request.getLevel(), "level", LEARN_LEVELS, "beginner"));
        }
        if (request.getFormat() != null) {
            article.setFormat(normalizeToken(request.getFormat(), "format", LEARN_FORMATS, "article"));
        }
        if (request.getDurationMin() != null) {
            article.setDurationMin(normalizeNonNegative(request.getDurationMin(), 5, 1_000_000, "durationMin"));
        }
        if (request.getRewardNex() != null) {
            article.setRewardNex(normalizeReward(request.getRewardNex()));
        }
        if (request.getProgressPct() != null) {
            article.setProgressPct(normalizeNonNegative(request.getProgressPct(), 0, 100, "progressPct"));
        }
        if (request.getFeatured() != null) {
            article.setFeatured(normalizeFlag(request.getFeatured()));
        }
        if (request.getEmoji() != null) {
            article.setEmoji(normalizeShortText(request.getEmoji(), "📘", 16));
        }
        if (request.getTint() != null) {
            article.setTint(normalizeShortText(request.getTint(), "#c6ff3a", 32));
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

    @Override
    public HelpArticleResponse archive(Long id) {
        if (id == null) {
            throw new BizException("Help article id is required");
        }
        HelpArticle article = helpArticleMapper.selectById(id);
        if (article == null || Integer.valueOf(1).equals(article.getIsDeleted())) {
            throw new BizException(404, "Help article not found");
        }
        article.setStatus(0);
        article.setIsDeleted(1);
        helpArticleMapper.updateById(article);
        return toResponse(article);
    }

    private LambdaQueryWrapper<HelpArticle> baseQuery(String query, Integer status) {
        String normalizedQuery = SystemFieldValidator.trimToNull(query);
        return new LambdaQueryWrapper<HelpArticle>()
                .eq(HelpArticle::getIsDeleted, 0)
                .eq(status != null, HelpArticle::getStatus, status)
                .and(StringUtils.hasText(normalizedQuery), nested -> nested
                        .like(HelpArticle::getArticleCode, normalizedQuery)
                        .or()
                        .like(HelpArticle::getTitle, normalizedQuery));
    }

    private String normalizeArticleCode(String articleCode) {
        return SystemFieldValidator.normalizeCode(articleCode, "articleCode", 96);
    }

    private String normalizeToken(String value, String fieldName, Set<String> allowed, String fallback) {
        String normalized = SystemFieldValidator.trimToNull(value);
        if (!StringUtils.hasText(normalized)) {
            return fallback;
        }
        normalized = normalized.toLowerCase();
        if (!allowed.contains(normalized)) {
            throw new BizException("Invalid " + fieldName);
        }
        return normalized;
    }

    private Integer normalizeNonNegative(Integer value, int fallback, int max, String fieldName) {
        if (value == null) {
            return fallback;
        }
        if (value < 0 || value > max) {
            throw new BizException("Invalid " + fieldName);
        }
        return value;
    }

    private Integer normalizeFlag(Integer value) {
        if (value == null) {
            return 0;
        }
        if (value != 0 && value != 1) {
            throw new BizException("Invalid featured");
        }
        return value;
    }

    private BigDecimal normalizeReward(BigDecimal value) {
        if (value == null) {
            return ZERO_REWARD;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("Invalid rewardNex");
        }
        return value.setScale(6, java.math.RoundingMode.HALF_UP);
    }

    private String normalizeShortText(String value, String fallback, int maxLength) {
        String normalized = SystemFieldValidator.trimToNull(value);
        if (!StringUtils.hasText(normalized)) {
            return fallback;
        }
        if (normalized.length() > maxLength) {
            throw new BizException("Invalid text length");
        }
        return normalized;
    }

    private HelpArticleResponse toResponse(HelpArticle article) {
        return new HelpArticleResponse(
                article.getId(),
                article.getArticleCode(),
                article.getTitle(),
                article.getContent(),
                article.getCategory(),
                article.getLevel(),
                article.getFormat(),
                article.getDurationMin(),
                article.getRewardNex(),
                article.getProgressPct(),
                article.getFeatured(),
                article.getEmoji(),
                article.getTint(),
                article.getSortOrder(),
                article.getStatus(),
                article.getCreatedAt(),
                article.getUpdatedAt());
    }
}
