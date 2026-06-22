package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.content.domain.I18nHardcodedFindingView;
import ffdd.opsconsole.content.domain.I18nIntegrityIssueView;
import ffdd.opsconsole.content.domain.I18nLearningRepository;
import ffdd.opsconsole.content.domain.I18nMessagePairView;
import ffdd.opsconsole.content.domain.I18nNamespaceView;
import ffdd.opsconsole.content.domain.LearningCourseView;
import ffdd.opsconsole.content.dto.LearningCourseUpsertRequest;
import ffdd.opsconsole.content.mapper.HelpArticleMapper;
import ffdd.opsconsole.content.mapper.I18nHardcodedFindingMapper;
import ffdd.opsconsole.content.mapper.I18nIntegrityIssueMapper;
import ffdd.opsconsole.content.mapper.I18nMessageMapper;
import ffdd.opsconsole.content.mapper.I18nNamespaceMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MybatisI18nLearningRepository implements I18nLearningRepository {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{[a-zA-Z0-9_.-]+}");

    private final I18nNamespaceMapper namespaceMapper;
    private final I18nMessageMapper messageMapper;
    private final I18nIntegrityIssueMapper integrityIssueMapper;
    private final I18nHardcodedFindingMapper hardcodedFindingMapper;
    private final HelpArticleMapper helpArticleMapper;

    @Override
    public List<I18nNamespaceView> listNamespaces() {
        return namespaceMapper.selectList(new LambdaQueryWrapper<I18nNamespaceEntity>()
                        .eq(I18nNamespaceEntity::getIsDeleted, 0)
                        .eq(I18nNamespaceEntity::getStatus, 1)
                        .orderByAsc(I18nNamespaceEntity::getSortOrder)
                        .orderByAsc(I18nNamespaceEntity::getNamespaceCode))
                .stream()
                .map(row -> new I18nNamespaceView(
                        row.getNamespaceCode(),
                        value(row.getKeyCount()),
                        value(row.getCoveragePct()),
                        text(row.getVariants(), "-"),
                        text(row.getLastChange(), "-")))
                .toList();
    }

    @Override
    public Optional<I18nMessagePairView> findMessagePair(String messageKey) {
        List<I18nMessageEntity> rows = messageRows(messageKey);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toPair(messageKey, rows));
    }

    @Override
    public I18nMessagePairView saveMessagePair(String messageKey, String zh, String en, String status, LocalDateTime now) {
        int dbStatus = "published".equalsIgnoreCase(status) ? 1 : 0;
        upsertMessage(messageKey, "zh-CN", zh, dbStatus, now);
        upsertMessage(messageKey, "en-US", en, dbStatus, now);
        return toPair(messageKey, messageRows(messageKey));
    }

    @Override
    public I18nMessagePairView markMarketingExperiment(String messageKey, LocalDateTime now) {
        String namespace = namespaceOf(messageKey);
        I18nNamespaceEntity entity = namespaceMapper.selectOne(new LambdaQueryWrapper<I18nNamespaceEntity>()
                .eq(I18nNamespaceEntity::getNamespaceCode, namespace)
                .eq(I18nNamespaceEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (entity != null) {
            entity.setVariants("多版 x1");
            entity.setUpdatedAt(now);
            namespaceMapper.updateById(entity);
        }
        return findMessagePair(messageKey).orElseGet(() -> saveMessagePair(messageKey, "", "", "draft", now));
    }

    @Override
    public List<I18nIntegrityIssueView> listIntegrityIssues() {
        return integrityIssueMapper.selectList(new LambdaQueryWrapper<I18nIntegrityIssueEntity>()
                        .eq(I18nIntegrityIssueEntity::getIsDeleted, 0)
                        .orderByAsc(I18nIntegrityIssueEntity::getSortOrder)
                        .orderByAsc(I18nIntegrityIssueEntity::getIssueCode))
                .stream()
                .map(this::toIssue)
                .toList();
    }

    @Override
    public I18nIntegrityIssueView markIssueFixed(String issueCode, LocalDateTime now) {
        I18nIntegrityIssueEntity entity = integrityIssueMapper.selectOne(new LambdaQueryWrapper<I18nIntegrityIssueEntity>()
                .eq(I18nIntegrityIssueEntity::getIssueCode, issueCode)
                .eq(I18nIntegrityIssueEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (entity == null) {
            throw new IllegalArgumentException(issueCode);
        }
        entity.setStatus("fixed");
        entity.setUpdatedAt(now);
        integrityIssueMapper.updateById(entity);
        return toIssue(entity);
    }

    @Override
    public List<I18nHardcodedFindingView> listHardcodedFindings() {
        return hardcodedFindingMapper.selectList(new LambdaQueryWrapper<I18nHardcodedFindingEntity>()
                        .eq(I18nHardcodedFindingEntity::getIsDeleted, 0)
                        .orderByAsc(I18nHardcodedFindingEntity::getSortOrder)
                        .orderByAsc(I18nHardcodedFindingEntity::getLocation))
                .stream()
                .map(row -> new I18nHardcodedFindingView(
                        row.getLocation(),
                        row.getRawCopy(),
                        row.getSuggestedKey(),
                        normalizedStatus(row.getStatus())))
                .toList();
    }

    @Override
    public List<LearningCourseView> listCourses() {
        return learningCourseEntities().stream()
                .map(this::toCourseView)
                .toList();
    }

    @Override
    public Optional<LearningCourseView> findCourse(String courseId) {
        return findCourseEntity(courseId).map(this::toCourseView);
    }

    @Override
    public LearningCourseView createCourse(String courseId, LearningCourseUpsertRequest request, LocalDateTime now) {
        HelpArticleEntity entity = new HelpArticleEntity();
        String category = request.category().trim().toLowerCase(Locale.ROOT);
        entity.setArticleCode("learn." + category + "." + courseId);
        entity.setTitle(request.titleZh().trim());
        entity.setContent(request.bodyZh().trim());
        entity.setCategory(category);
        entity.setLevel(request.difficulty().trim().toLowerCase(Locale.ROOT));
        entity.setFormat(toDbFormat(request.format()));
        entity.setSurface("/learn/" + courseId);
        entity.setDurationMin(parseDuration(request.duration()));
        entity.setRewardNex(request.rewardNex());
        entity.setProgressPct(0);
        entity.setFeatured(0);
        entity.setEmoji(icon(category));
        entity.setTint(tint(category));
        entity.setSortOrder(nextLearningSortOrder());
        entity.setStatus(toDbCourseStatus(request.publishState()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        helpArticleMapper.insert(entity);
        saveMessagePair("learn." + courseId + ".title", request.titleZh().trim(), request.titleEn().trim(), "draft", now);
        saveMessagePair("learn." + courseId + ".body", request.bodyZh().trim(), request.bodyEn().trim(), "draft", now);
        return findCourse(courseId).orElseGet(() -> toCourseView(entity));
    }

    @Override
    public LearningCourseView updateCourseStatus(String courseId, String status, LocalDateTime now) {
        HelpArticleEntity entity = findCourseEntity(courseId).orElseThrow();
        entity.setStatus(toDbCourseStatus(status));
        entity.setUpdatedAt(now);
        helpArticleMapper.updateById(entity);
        return findCourse(courseId).orElseGet(() -> toCourseView(entity));
    }

    @Override
    public LearningCourseView updateCourseReward(String courseId, BigDecimal rewardNex, LocalDateTime now) {
        HelpArticleEntity entity = findCourseEntity(courseId).orElseThrow();
        entity.setRewardNex(rewardNex);
        entity.setUpdatedAt(now);
        helpArticleMapper.updateById(entity);
        return findCourse(courseId).orElseGet(() -> toCourseView(entity));
    }

    @Override
    public LearningCourseView updateFeaturedCourse(String courseId, LocalDateTime now) {
        for (HelpArticleEntity entity : learningCourseEntities()) {
            entity.setFeatured(courseId.equals(courseId(entity.getArticleCode())) ? 1 : 0);
            entity.setUpdatedAt(now);
            helpArticleMapper.updateById(entity);
        }
        return findCourse(courseId).orElseThrow();
    }

    private List<I18nMessageEntity> messageRows(String messageKey) {
        return messageMapper.selectList(new LambdaQueryWrapper<I18nMessageEntity>()
                .eq(I18nMessageEntity::getMessageKey, messageKey)
                .eq(I18nMessageEntity::getIsDeleted, 0));
    }

    private void upsertMessage(String messageKey, String locale, String value, int status, LocalDateTime now) {
        I18nMessageEntity entity = messageMapper.selectOne(new LambdaQueryWrapper<I18nMessageEntity>()
                .eq(I18nMessageEntity::getMessageKey, messageKey)
                .eq(I18nMessageEntity::getLocale, locale)
                .eq(I18nMessageEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (entity == null) {
            entity = new I18nMessageEntity();
            entity.setMessageKey(messageKey);
            entity.setLocale(locale);
            entity.setCreatedAt(now);
            entity.setIsDeleted(0);
        }
        entity.setMessageValue(value);
        entity.setStatus(status);
        entity.setUpdatedAt(now);
        if (entity.getId() == null) {
            messageMapper.insert(entity);
        } else {
            messageMapper.updateById(entity);
        }
    }

    private I18nMessagePairView toPair(String messageKey, List<I18nMessageEntity> rows) {
        String en = "";
        String zh = "";
        int minStatus = 1;
        for (I18nMessageEntity row : rows) {
            if ("en-US".equalsIgnoreCase(row.getLocale())) {
                en = text(row.getMessageValue(), "");
            }
            if ("zh-CN".equalsIgnoreCase(row.getLocale())) {
                zh = text(row.getMessageValue(), "");
            }
            if (row.getStatus() == null || row.getStatus() == 0) {
                minStatus = 0;
            }
        }
        String status = minStatus == 1 ? "published" : "draft";
        Set<String> placeholders = new TreeSet<>();
        placeholders.addAll(placeholders(en));
        placeholders.addAll(placeholders(zh));
        return new I18nMessagePairView(messageKey, en, zh, status, minStatus == 1 ? "published" : "draft", List.copyOf(placeholders));
    }

    private I18nIntegrityIssueView toIssue(I18nIntegrityIssueEntity row) {
        return new I18nIntegrityIssueView(
                row.getIssueCode(),
                row.getIssueKind(),
                value(row.getIssueCount()),
                splitSamples(row.getSamplesText()),
                normalizedStatus(row.getStatus()));
    }

    private List<String> splitSamples(String samplesText) {
        if (!StringUtils.hasText(samplesText)) {
            return List.of();
        }
        return samplesText.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private List<HelpArticleEntity> learningCourseEntities() {
        return helpArticleMapper.selectList(new LambdaQueryWrapper<HelpArticleEntity>()
                        .likeRight(HelpArticleEntity::getArticleCode, "learn.")
                        .eq(HelpArticleEntity::getIsDeleted, 0))
                .stream()
                .sorted(Comparator.comparing(HelpArticleEntity::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(HelpArticleEntity::getArticleCode))
                .toList();
    }

    private Optional<HelpArticleEntity> findCourseEntity(String courseId) {
        return learningCourseEntities().stream()
                .filter(row -> courseId.equals(courseId(row.getArticleCode())))
                .findFirst();
    }

    private LearningCourseView toCourseView(HelpArticleEntity entity) {
        return new LearningCourseView(
                courseId(entity.getArticleCode()),
                entity.getTitle(),
                toViewCategory(entity.getCategory()),
                toViewFormat(entity.getFormat()),
                toViewLevel(entity.getLevel()),
                entity.getRewardNex() == null ? BigDecimal.ZERO : entity.getRewardNex().stripTrailingZeros(),
                entity.getFeatured() != null && entity.getFeatured() == 1,
                value(entity.getDurationMin()) + " min",
                "v" + Math.max(1, value(entity.getSortOrder()) / 10),
                toViewCourseStatus(entity.getStatus()),
                text(entity.getContent(), ""));
    }

    private int nextLearningSortOrder() {
        return learningCourseEntities().stream()
                .map(HelpArticleEntity::getSortOrder)
                .filter(value -> value != null)
                .max(Integer::compareTo)
                .orElse(0) + 10;
    }

    private Set<String> placeholders(String text) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text == null ? "" : text);
        Set<String> values = new TreeSet<>();
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    private String namespaceOf(String messageKey) {
        int dot = messageKey.indexOf('.');
        return dot > 0 ? messageKey.substring(0, dot) : messageKey;
    }

    private String courseId(String articleCode) {
        int lastDot = articleCode == null ? -1 : articleCode.lastIndexOf('.');
        return lastDot >= 0 ? articleCode.substring(lastDot + 1) : text(articleCode, "");
    }

    private int parseDuration(String duration) {
        if (!StringUtils.hasText(duration)) {
            return 5;
        }
        String digits = duration.replaceAll("[^0-9]", "");
        if (!StringUtils.hasText(digits)) {
            return 5;
        }
        return Integer.parseInt(digits);
    }

    private String toDbFormat(String format) {
        return switch (format == null ? "" : format.trim().toLowerCase(Locale.ROOT)) {
            case "video" -> "video";
            case "hands-on", "interactive" -> "interactive";
            default -> "article";
        };
    }

    private int toDbCourseStatus(String status) {
        return switch (status == null ? "" : status.trim().toLowerCase(Locale.ROOT)) {
            case "published" -> 1;
            case "archived" -> 2;
            default -> 0;
        };
    }

    private String toViewCourseStatus(Integer status) {
        if (status != null && status == 1) {
            return "published";
        }
        if (status != null && status == 2) {
            return "archived";
        }
        return "draft";
    }

    private String toViewFormat(String format) {
        return switch (format == null ? "" : format.trim().toLowerCase(Locale.ROOT)) {
            case "video" -> "Video";
            case "interactive", "guide" -> "Hands-on";
            default -> "Article";
        };
    }

    private String toViewLevel(String level) {
        return switch (level == null ? "" : level.trim().toLowerCase(Locale.ROOT)) {
            case "intermediate" -> "Intermediate";
            case "advanced" -> "Advanced";
            default -> "Beginner";
        };
    }

    private String toViewCategory(String category) {
        return switch (category == null ? "" : category.trim().toLowerCase(Locale.ROOT)) {
            case "earn" -> "Earn";
            case "team" -> "Team";
            case "wealth" -> "Wealth";
            case "security" -> "Security";
            default -> "Basics";
        };
    }

    private String icon(String category) {
        return switch (category) {
            case "earn" -> "⚡";
            case "team" -> "🧬";
            case "wealth" -> "💎";
            case "security" -> "🛡";
            default -> "🚀";
        };
    }

    private String tint(String category) {
        return switch (category) {
            case "earn" -> "#8be9ff";
            case "team" -> "#f4a7ff";
            case "wealth" -> "#ffd166";
            case "security" -> "#ff9f9f";
            default -> "#c6ff3a";
        };
    }

    private String normalizedStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : "open";
    }

    private String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }
}
