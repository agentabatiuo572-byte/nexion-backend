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

    private static final List<NamespaceSeed> NAMESPACE_SEEDS = List.of(
            namespace("home", 128, 100, "-", "06-09", 10),
            namespace("binaryHowItWorks", 30, 100, "-", "05-30", 20),
            namespace("exchangeHowItWorks", 35, 100, "-", "06-02", 30),
            namespace("marketing", 64, 95, "多版 x3", "06-05", 40),
            namespace("milestones", 22, 100, "多版 x1", "06-09", 50),
            namespace("team", 41, 100, "-", "05-30", 60),
            namespace("wallet", 52, 98, "-", "06-05", 70),
            namespace("trust", 38, 95, "-", "05-12", 80),
            namespace("genesis", 29, 99, "-", "05-26", 90),
            namespace("riskDisclosure", 44, 100, "-", "06-08", 100),
            namespace("learn", 36, 100, "-", "06-01", 110));

    private static final List<IssueSeed> ISSUE_SEEDS = List.of(
            issue("missing-zh", "缺镜像 (zh)", 3, "marketing.referral.tagline\nwallet.lowBalance\ntrust.heroSub", 10),
            issue("missing-en", "缺镜像 (en)", 1, "genesis.dividendNote", 20),
            issue("placeholder", "占位符不匹配", 2, "milestones.earnCross({n} 词序异常)\nmarketing.bundle.cta({amount} 缺失)", 30),
            issue("hardcoded", "疑似硬编码", 4, "store/bundle\nwallet空态\nteam邀请卡\nearn任务角标", 40));

    private static final List<FindingSeed> FINDING_SEEDS = List.of(
            finding("app/store/bundle-card", "Bundle bonus today", "marketing.bundle.bonus", 10),
            finding("app/wallet/empty-state", "No balance yet", "wallet.emptyState.title", 20),
            finding("app/team/invite-card", "Invite friends to earn", "team.invite.cardTitle", 30),
            finding("app/earn/task-chip", "Daily task", "earn.task.dailyChip", 40));

    private static final List<CourseSeed> COURSE_SEEDS = List.of(
            course("what-is-nexion", "什么是 Nexion", "What is Nexion", "Basics", "Article", "Beginner", "6 min", "10", true, 10),
            course("how-devices-earn", "设备如何产生收益", "How devices earn", "Basics", "Video", "Beginner", "8 min", "12", false, 20),
            course("wallet-basics", "钱包基础", "Wallet basics", "Basics", "Article", "Beginner", "5 min", "10", false, 30),
            course("quests-101", "任务入门", "Quests 101", "Earn", "Hands-on", "Beginner", "7 min", "15", false, 40),
            course("staking-explained", "锁仓说明", "Staking explained", "Earn", "Article", "Intermediate", "9 min", "20", false, 50),
            course("royalty-network", "版税网络", "Royalty network", "Team", "Video", "Intermediate", "10 min", "25", false, 60),
            course("build-your-team", "搭建团队", "Build your team", "Team", "Hands-on", "Beginner", "8 min", "18", false, 70),
            course("v-rank-path", "V 等级路径", "V-Rank path", "Team", "Article", "Intermediate", "9 min", "22", false, 80),
            course("ambassador-track", "大使成长路径", "Ambassador track", "Team", "Video", "Advanced", "12 min", "30", false, 90),
            course("genesis-nodes", "Genesis 节点", "Genesis nodes", "Wealth", "Article", "Advanced", "11 min", "40", false, 100),
            course("nex-tokenomics", "NEX 经济模型", "NEX tokenomics", "Wealth", "Video", "Intermediate", "10 min", "28", false, 110),
            course("reinvest-strategy", "复投策略", "Reinvest strategy", "Wealth", "Hands-on", "Intermediate", "9 min", "35", false, 120),
            course("kyc-express", "KYC Express", "KYC Express", "Security", "Article", "Beginner", "5 min", "12", false, 130),
            course("2fa-setup", "设置 2FA", "Set up 2FA", "Security", "Hands-on", "Beginner", "4 min", "10", false, 140),
            course("proof-of-compute", "算力证明", "Proof of compute", "Security", "Video", "Advanced", "13 min", "32", false, 150));

    private final I18nNamespaceMapper namespaceMapper;
    private final I18nMessageMapper messageMapper;
    private final I18nIntegrityIssueMapper integrityIssueMapper;
    private final I18nHardcodedFindingMapper hardcodedFindingMapper;
    private final HelpArticleMapper helpArticleMapper;

    @Override
    public void ensureSeedData(LocalDateTime now) {
        // Business rows must come from MySQL writes, not read-time demo seeds.
    }

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
        entity.setSurface("learn");
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

    private void ensureNamespace(NamespaceSeed seed, LocalDateTime now) {
        I18nNamespaceEntity existing = namespaceMapper.selectOne(new LambdaQueryWrapper<I18nNamespaceEntity>()
                .eq(I18nNamespaceEntity::getNamespaceCode, seed.code())
                .eq(I18nNamespaceEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        I18nNamespaceEntity entity = new I18nNamespaceEntity();
        entity.setNamespaceCode(seed.code());
        entity.setKeyCount(seed.keys());
        entity.setCoveragePct(seed.coverage());
        entity.setVariants(seed.variants());
        entity.setLastChange(seed.lastChange());
        entity.setStatus(1);
        entity.setSortOrder(seed.sortOrder());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        namespaceMapper.insert(entity);
    }

    private void ensureIssue(IssueSeed seed, LocalDateTime now) {
        I18nIntegrityIssueEntity existing = integrityIssueMapper.selectOne(new LambdaQueryWrapper<I18nIntegrityIssueEntity>()
                .eq(I18nIntegrityIssueEntity::getIssueCode, seed.code())
                .eq(I18nIntegrityIssueEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        I18nIntegrityIssueEntity entity = new I18nIntegrityIssueEntity();
        entity.setIssueCode(seed.code());
        entity.setIssueKind(seed.kind());
        entity.setIssueCount(seed.count());
        entity.setSamplesText(seed.samples());
        entity.setStatus("open");
        entity.setSortOrder(seed.sortOrder());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        integrityIssueMapper.insert(entity);
    }

    private void ensureFinding(FindingSeed seed, LocalDateTime now) {
        I18nHardcodedFindingEntity existing = hardcodedFindingMapper.selectOne(new LambdaQueryWrapper<I18nHardcodedFindingEntity>()
                .eq(I18nHardcodedFindingEntity::getLocation, seed.location())
                .eq(I18nHardcodedFindingEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        I18nHardcodedFindingEntity entity = new I18nHardcodedFindingEntity();
        entity.setLocation(seed.location());
        entity.setRawCopy(seed.rawCopy());
        entity.setSuggestedKey(seed.suggestedKey());
        entity.setStatus("open");
        entity.setSortOrder(seed.sortOrder());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        hardcodedFindingMapper.insert(entity);
    }

    private void ensureCourse(CourseSeed seed, LocalDateTime now) {
        if (findCourseEntity(seed.id()).isPresent()) {
            return;
        }
        HelpArticleEntity entity = new HelpArticleEntity();
        String category = seed.category().toLowerCase(Locale.ROOT);
        entity.setArticleCode("learn." + category + "." + seed.id());
        entity.setTitle(seed.titleZh());
        entity.setContent(seed.titleZh() + " 课程正文");
        entity.setCategory(category);
        entity.setLevel(seed.level().toLowerCase(Locale.ROOT));
        entity.setFormat(toDbFormat(seed.format()));
        entity.setSurface("learn");
        entity.setDurationMin(parseDuration(seed.duration()));
        entity.setRewardNex(new BigDecimal(seed.rewardNex()));
        entity.setProgressPct(0);
        entity.setFeatured(seed.featured() ? 1 : 0);
        entity.setEmoji(icon(category));
        entity.setTint(tint(category));
        entity.setSortOrder(seed.sortOrder());
        entity.setStatus(1);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        helpArticleMapper.insert(entity);
        saveMessagePair("learn." + seed.id() + ".title", seed.titleZh(), seed.titleEn(), "published", now);
        saveMessagePair("learn." + seed.id() + ".body", seed.titleZh() + " 课程正文", seed.titleEn() + " course body.", "published", now);
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

    private static NamespaceSeed namespace(String code, int keys, int coverage, String variants, String lastChange, int sortOrder) {
        return new NamespaceSeed(code, keys, coverage, variants, lastChange, sortOrder);
    }

    private static IssueSeed issue(String code, String kind, int count, String samples, int sortOrder) {
        return new IssueSeed(code, kind, count, samples, sortOrder);
    }

    private static FindingSeed finding(String location, String rawCopy, String suggestedKey, int sortOrder) {
        return new FindingSeed(location, rawCopy, suggestedKey, sortOrder);
    }

    private static CourseSeed course(String id, String titleZh, String titleEn, String category, String format, String level, String duration, String rewardNex, boolean featured, int sortOrder) {
        return new CourseSeed(id, titleZh, titleEn, category, format, level, duration, rewardNex, featured, sortOrder);
    }

    private record NamespaceSeed(String code, int keys, int coverage, String variants, String lastChange, int sortOrder) {
    }

    private record IssueSeed(String code, String kind, int count, String samples, int sortOrder) {
    }

    private record FindingSeed(String location, String rawCopy, String suggestedKey, int sortOrder) {
    }

    private record CourseSeed(String id, String titleZh, String titleEn, String category, String format, String level, String duration, String rewardNex, boolean featured, int sortOrder) {
    }
}
