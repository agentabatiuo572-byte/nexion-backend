package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.I18nHardcodedFindingView;
import ffdd.opsconsole.content.domain.I18nIntegrityIssueView;
import ffdd.opsconsole.content.domain.I18nLearningRepository;
import ffdd.opsconsole.content.domain.I18nMessagePairView;
import ffdd.opsconsole.content.domain.I18nNamespaceView;
import ffdd.opsconsole.content.domain.LearningCourseView;
import ffdd.opsconsole.content.domain.LearningCourseVersionView;
import ffdd.opsconsole.content.domain.LearningQuizQuestionView;
import ffdd.opsconsole.content.dto.LearningCourseUpsertRequest;
import ffdd.opsconsole.content.dto.LearningQuizQuestionRequest;
import ffdd.opsconsole.content.mapper.HelpArticleMapper;
import ffdd.opsconsole.content.mapper.AppLearningMapper;
import ffdd.opsconsole.content.mapper.I18nHardcodedFindingMapper;
import ffdd.opsconsole.content.mapper.I18nIntegrityIssueMapper;
import ffdd.opsconsole.content.mapper.I18nMessageMapper;
import ffdd.opsconsole.content.mapper.I18nMessageVersionMapper;
import ffdd.opsconsole.content.mapper.I18nNamespaceMapper;
import ffdd.opsconsole.content.mapper.LearningCourseVersionMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final ObjectMapper JSON = new ObjectMapper();

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
    private final I18nMessageVersionMapper messageVersionMapper;
    private final I18nIntegrityIssueMapper integrityIssueMapper;
    private final I18nHardcodedFindingMapper hardcodedFindingMapper;
    private final HelpArticleMapper helpArticleMapper;
    private final LearningCourseVersionMapper courseVersionMapper;
    private final AppLearningMapper appLearningMapper;

    @Override
    public void ensureSeedData(LocalDateTime now) {
        // Business rows must come from MySQL writes, not read-time demo seeds.
    }

    @Override
    public List<I18nNamespaceView> listNamespaces() {
        Map<String, I18nNamespaceEntity> configured = new LinkedHashMap<>();
        namespaceMapper.selectList(new LambdaQueryWrapper<I18nNamespaceEntity>()
                        .eq(I18nNamespaceEntity::getIsDeleted, 0)
                        .eq(I18nNamespaceEntity::getStatus, 1))
                .forEach(row -> configured.put(row.getNamespaceCode(), row));
        Map<String, List<I18nMessagePairView>> grouped = new java.util.TreeMap<>();
        for (I18nMessagePairView message : listMessagePairs()) {
            grouped.computeIfAbsent(message.namespace(), ignored -> new ArrayList<>()).add(message);
        }
        List<I18nNamespaceView> rows = new ArrayList<>();
        grouped.forEach((namespace, messages) -> {
            long complete = messages.stream().filter(row -> StringUtils.hasText(row.zh())
                    && StringUtils.hasText(row.en()) && StringUtils.hasText(row.vi())).count();
            int coverage = messages.isEmpty() ? 0 : (int) Math.round(complete * 100.0D / messages.size());
            I18nNamespaceEntity metadata = configured.get(namespace);
            rows.add(new I18nNamespaceView(namespace, messages.size(), coverage,
                    metadata == null ? "-" : text(metadata.getVariants(), "-"),
                    metadata == null ? "-" : text(metadata.getLastChange(), "-")));
        });
        return rows;
    }

    @Override
    public Optional<I18nMessagePairView> findMessagePair(String messageKey) {
        I18nMessageVersionEntity latest = latestVersion(messageKey, null);
        if (latest != null) return Optional.of(toVersionPair(latest));
        List<I18nMessageEntity> rows = messageRows(messageKey);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toPair(messageKey, rows, "v1", "published"));
    }

    @Override
    public Optional<I18nMessagePairView> findPublishedMessagePair(String messageKey) {
        List<I18nMessageEntity> rows = messageRows(messageKey).stream().filter(row -> Integer.valueOf(1).equals(row.getStatus())).toList();
        if (rows.isEmpty()) return Optional.empty();
        I18nMessageVersionEntity published = latestVersion(messageKey, "PUBLISHED");
        return Optional.of(toPair(messageKey, rows, published == null ? "v1" : versionLabel(published.getVersionNo()), "published"));
    }

    @Override
    public Optional<I18nMessagePairView> findDraftMessagePair(String messageKey) {
        return Optional.ofNullable(latestVersion(messageKey, "DRAFT")).map(this::toVersionPair);
    }

    @Override
    public List<I18nMessagePairView> listMessagePairs() {
        Map<String, I18nMessagePairView> result = new LinkedHashMap<>();
        messageMapper.selectList(new LambdaQueryWrapper<I18nMessageEntity>()
                        .eq(I18nMessageEntity::getIsDeleted, 0).orderByAsc(I18nMessageEntity::getMessageKey)
                        .orderByAsc(I18nMessageEntity::getLocale)).stream().map(I18nMessageEntity::getMessageKey).distinct()
                .forEach(key -> findMessagePair(key).ifPresent(pair -> result.put(key, pair)));
        messageVersionMapper.selectList(new LambdaQueryWrapper<I18nMessageVersionEntity>()
                        .eq(I18nMessageVersionEntity::getIsDeleted, 0).orderByAsc(I18nMessageVersionEntity::getMessageKey)
                        .orderByDesc(I18nMessageVersionEntity::getVersionNo))
                .forEach(row -> result.putIfAbsent(row.getMessageKey(), toVersionPair(row)));
        return new ArrayList<>(result.values());
    }

    @Override
    public I18nMessagePairView saveMessagePair(String messageKey, String zh, String en, String vi, String status, LocalDateTime now) {
        String key = messageKey.trim();
        boolean publish = "published".equalsIgnoreCase(status);
        I18nMessageVersionEntity version = latestVersion(key, "DRAFT");
        if (version == null) {
            version = new I18nMessageVersionEntity();
            version.setMessageKey(key);
            I18nMessageVersionEntity latest = latestVersion(key, null);
            version.setVersionNo(latest == null ? 1 : value(latest.getVersionNo()) + 1);
            version.setCreatedAt(now);
            version.setIsDeleted(0);
        }
        version.setZhValue(zh.trim()); version.setEnValue(en.trim()); version.setViValue(vi.trim());
        version.setStatus(publish ? "PUBLISHED" : "DRAFT"); version.setUpdatedAt(now);
        if (publish) {
            messageVersionMapper.selectList(new LambdaQueryWrapper<I18nMessageVersionEntity>()
                            .eq(I18nMessageVersionEntity::getMessageKey, key).eq(I18nMessageVersionEntity::getStatus, "PUBLISHED")
                            .eq(I18nMessageVersionEntity::getIsDeleted, 0)).forEach(previous -> {
                previous.setStatus("ARCHIVED"); previous.setUpdatedAt(now); messageVersionMapper.updateById(previous);
            });
        }
        if (version.getId() == null) messageVersionMapper.insert(version); else messageVersionMapper.updateById(version);
        if (publish) {
            upsertMessage(key, "zh-CN", zh, 1, now); upsertMessage(key, "en-US", en, 1, now); upsertMessage(key, "vi-VN", vi, 1, now);
        }
        return toVersionPair(version);
    }

    @Override
    public I18nMessagePairView archiveMessage(String messageKey, LocalDateTime now) {
        String key = messageKey.trim();
        messageRows(key).forEach(row -> { row.setStatus(0); row.setUpdatedAt(now); messageMapper.updateById(row); });
        I18nMessageVersionEntity published = latestVersion(key, "PUBLISHED");
        if (published == null) throw new IllegalArgumentException(key);
        published.setStatus("ARCHIVED"); published.setUpdatedAt(now); messageVersionMapper.updateById(published);
        return toVersionPair(published);
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
        entity.setStatus(0);
        applyQuiz(entity, request);
        entity.setVersionNo(1);
        entity.setRevision(0L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        helpArticleMapper.insert(entity);
        saveMessagePair("learn." + courseId + ".title", request.titleZh().trim(), request.titleEn().trim(), request.titleVi().trim(), "draft", now);
        saveMessagePair("learn." + courseId + ".body", request.bodyZh().trim(), request.bodyEn().trim(), request.bodyVi().trim(), "draft", now);
        return findCourse(courseId).orElseGet(() -> toCourseView(entity));
    }

    @Override
    public List<I18nIntegrityIssueView> recomputeIntegrity(LocalDateTime now) {
        Map<String, List<String>> samples = new LinkedHashMap<>();
        for (String code : List.of("missing-zh", "missing-en", "missing-vi", "placeholder")) samples.put(code, new ArrayList<>());
        for (I18nMessagePairView message : listMessagePairs()) {
            if (!StringUtils.hasText(message.zh())) samples.get("missing-zh").add(message.messageKey());
            if (!StringUtils.hasText(message.en())) samples.get("missing-en").add(message.messageKey());
            if (!StringUtils.hasText(message.vi())) samples.get("missing-vi").add(message.messageKey());
            Set<String> zh = placeholders(message.zh()), en = placeholders(message.en()), vi = placeholders(message.vi());
            if ((!zh.equals(en) || !zh.equals(vi)) && StringUtils.hasText(message.zh()) && StringUtils.hasText(message.en()) && StringUtils.hasText(message.vi())) samples.get("placeholder").add(message.messageKey());
        }
        upsertIntegrity("missing-zh", "缺少中文镜像", samples.get("missing-zh"), 10, now);
        upsertIntegrity("missing-en", "缺少英文镜像", samples.get("missing-en"), 20, now);
        upsertIntegrity("missing-vi", "缺少越南语镜像", samples.get("missing-vi"), 30, now);
        upsertIntegrity("placeholder", "占位符不一致", samples.get("placeholder"), 40, now);
        return listIntegrityIssues();
    }

    @Override
    public LearningCourseView updateCourseDraft(String courseId, LearningCourseUpsertRequest request, LocalDateTime now) {
        HelpArticleEntity entity = findCourseEntity(courseId).orElseThrow();
        String category = request.category().trim().toLowerCase(Locale.ROOT);
        entity.setArticleCode("learn." + category + "." + courseId);
        entity.setTitle(request.titleZh().trim());
        entity.setContent(request.bodyZh().trim());
        entity.setCategory(category);
        entity.setLevel(request.difficulty().trim().toLowerCase(Locale.ROOT));
        entity.setFormat(toDbFormat(request.format()));
        entity.setDurationMin(parseDuration(request.duration()));
        entity.setRewardNex(request.rewardNex());
        entity.setEmoji(icon(category));
        entity.setTint(tint(category));
        applyQuiz(entity, request);
        entity.setRevision(longValue(entity.getRevision()) + 1L);
        entity.setUpdatedAt(now);
        long expectedRevision = request.expectedRevision() == null
                ? longValue(entity.getRevision()) - 1L
                : request.expectedRevision();
        int affected = helpArticleMapper.update(entity, Wrappers.<HelpArticleEntity>lambdaUpdate()
                .eq(HelpArticleEntity::getId, entity.getId())
                .eq(HelpArticleEntity::getRevision, expectedRevision)
                .eq(HelpArticleEntity::getIsDeleted, 0));
        if (affected != 1) {
            throw new IllegalStateException("LEARNING_COURSE_REVISION_CONFLICT");
        }
        saveMessagePair("learn." + courseId + ".title", request.titleZh().trim(), request.titleEn().trim(), request.titleVi().trim(), "draft", now);
        saveMessagePair("learn." + courseId + ".body", request.bodyZh().trim(), request.bodyEn().trim(), request.bodyVi().trim(), "draft", now);
        return findCourse(courseId).orElseGet(() -> toCourseView(entity));
    }

    @Override
    public void deleteCourseDraft(String courseId, LocalDateTime now) {
        HelpArticleEntity entity = findCourseEntity(courseId).orElseThrow();
        entity.setIsDeleted(1);
        entity.setRevision(longValue(entity.getRevision()) + 1L);
        entity.setUpdatedAt(now);
        helpArticleMapper.updateById(entity);
    }

    @Override
    public LearningCourseView updateCourseStatus(String courseId, String status, LocalDateTime now) {
        HelpArticleEntity entity = findCourseEntity(courseId).orElseThrow();
        entity.setStatus(toDbCourseStatus(status));
        entity.setRevision(longValue(entity.getRevision()) + 1L);
        entity.setUpdatedAt(now);
        helpArticleMapper.updateById(entity);
        return findCourse(courseId).orElseGet(() -> toCourseView(entity));
    }

    @Override
    public LearningCourseView updateCourseReward(String courseId, BigDecimal rewardNex, LocalDateTime now) {
        HelpArticleEntity entity = findCourseEntity(courseId).orElseThrow();
        entity.setRewardNex(rewardNex);
        entity.setRevision(longValue(entity.getRevision()) + 1L);
        entity.setUpdatedAt(now);
        helpArticleMapper.updateById(entity);
        return findCourse(courseId).orElseGet(() -> toCourseView(entity));
    }

    @Override
    public LearningCourseView updateFeaturedCourse(String courseId, LocalDateTime now) {
        HelpArticleEntity target = findCourseEntity(courseId).orElseThrow();
        helpArticleMapper.selectSingleFeaturedLearningCourse(target.getArticleCode(), now);
        return findCourse(courseId).orElseThrow();
    }

    @Override
    public List<LearningCourseVersionView> listCourseVersions(String courseId) {
        return courseVersionMapper.selectList(new LambdaQueryWrapper<LearningCourseVersionEntity>()
                        .eq(LearningCourseVersionEntity::getCourseId, courseId)
                        .eq(LearningCourseVersionEntity::getIsDeleted, 0)
                        .orderByDesc(LearningCourseVersionEntity::getCreatedAt))
                .stream().map(this::toCourseVersionView).toList();
    }

    @Override
    public Optional<LearningCourseVersionView> findCourseVersion(String courseId, String version) {
        return Optional.ofNullable(courseVersionMapper.selectOne(new LambdaQueryWrapper<LearningCourseVersionEntity>()
                        .eq(LearningCourseVersionEntity::getCourseId, courseId)
                        .eq(LearningCourseVersionEntity::getVersionLabel, version)
                        .eq(LearningCourseVersionEntity::getIsDeleted, 0)
                        .last("LIMIT 1")))
                .map(this::toCourseVersionView);
    }

    @Override
    public LearningCourseVersionView saveCourseVersion(String courseId, String version, String status,
            LearningCourseUpsertRequest request, Long expectedRevision, LocalDateTime now) {
        List<LearningCourseVersionEntity> lockedVersions = courseVersionMapper.lockCourseVersions(courseId);
        LearningCourseVersionEntity entity = courseVersionMapper.selectOne(new LambdaQueryWrapper<LearningCourseVersionEntity>()
                .eq(LearningCourseVersionEntity::getCourseId, courseId)
                .eq(LearningCourseVersionEntity::getVersionLabel, version)
                .eq(LearningCourseVersionEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
        String payload;
        try {
            payload = JSON.writeValueAsString(request);
        } catch (Exception ex) {
            throw new IllegalArgumentException("LEARNING_COURSE_VERSION_SERIALIZATION_FAILED", ex);
        }
        if (entity == null) {
            if (lockedVersions.stream().anyMatch(row -> "DRAFT".equals(row.getStatus()))) {
                throw new IllegalStateException("LEARNING_COURSE_DRAFT_VERSION_ALREADY_EXISTS");
            }
            entity = new LearningCourseVersionEntity();
            entity.setCourseId(courseId);
            entity.setVersionLabel(version);
            entity.setStatus(status);
            entity.setPayloadJson(payload);
            entity.setRevision(0L);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            entity.setIsDeleted(0);
            courseVersionMapper.insert(entity);
        } else {
            if (!"DRAFT".equals(entity.getStatus()) || expectedRevision == null || expectedRevision.longValue() != longValue(entity.getRevision())) {
                throw new IllegalStateException("LEARNING_COURSE_VERSION_REVISION_CONFLICT");
            }
            long revision = longValue(entity.getRevision());
            entity.setPayloadJson(payload);
            entity.setRevision(revision + 1L);
            entity.setUpdatedAt(now);
            int affected = courseVersionMapper.update(entity, Wrappers.<LearningCourseVersionEntity>lambdaUpdate()
                    .eq(LearningCourseVersionEntity::getId, entity.getId())
                    .eq(LearningCourseVersionEntity::getRevision, revision)
                    .eq(LearningCourseVersionEntity::getStatus, "DRAFT")
                    .eq(LearningCourseVersionEntity::getIsDeleted, 0));
            if (affected != 1) throw new IllegalStateException("LEARNING_COURSE_VERSION_REVISION_CONFLICT");
        }
        return toCourseVersionView(entity);
    }

    @Override
    public void deleteCourseVersion(String courseId, String version, LocalDateTime now) {
        LearningCourseVersionEntity entity = courseVersionMapper.selectOne(new LambdaQueryWrapper<LearningCourseVersionEntity>()
                .eq(LearningCourseVersionEntity::getCourseId, courseId)
                .eq(LearningCourseVersionEntity::getVersionLabel, version)
                .eq(LearningCourseVersionEntity::getStatus, "DRAFT")
                .eq(LearningCourseVersionEntity::getIsDeleted, 0).last("LIMIT 1"));
        if (entity == null) throw new IllegalStateException("LEARNING_COURSE_VERSION_NOT_DRAFT");
        entity.setIsDeleted(1);
        entity.setUpdatedAt(now);
        courseVersionMapper.updateById(entity);
    }

    @Override
    public LearningCourseView activateCourseVersion(String courseId, String version, String expectedStatus,
            String expectedCurrentVersion, long expectedCurrentRevision, LocalDateTime now) {
        // Keep the lock order aligned with draft updates: course row first, version rows second.
        HelpArticleEntity entity = helpArticleMapper.lockLearningCourse(courseId);
        if (entity == null) throw new IllegalStateException("LEARNING_COURSE_NOT_FOUND");
        if (!versionLabel(entity.getVersionNo()).equals(expectedCurrentVersion)) {
            throw new IllegalStateException("LEARNING_COURSE_CURRENT_VERSION_CONFLICT");
        }
        if (longValue(entity.getRevision()) != expectedCurrentRevision) {
            throw new IllegalStateException("LEARNING_COURSE_CURRENT_REVISION_CONFLICT");
        }
        List<LearningCourseVersionEntity> versions = courseVersionMapper.lockCourseVersions(courseId);
        LearningCourseVersionEntity selected = versions.stream()
                .filter(row -> version.equals(row.getVersionLabel()))
                .findFirst().orElse(null);
        if (selected == null) throw new IllegalStateException("LEARNING_COURSE_VERSION_NOT_FOUND");
        if (!expectedStatus.equals(selected.getStatus())) {
            throw new IllegalStateException("LEARNING_COURSE_VERSION_STATE_CONFLICT");
        }
        LearningCourseUpsertRequest request = readCourseVersionPayload(selected.getPayloadJson());
        String category = request.category().trim().toLowerCase(Locale.ROOT);
        entity.setArticleCode("learn." + category + "." + courseId);
        entity.setTitle(request.titleZh().trim());
        entity.setContent(request.bodyZh().trim());
        entity.setCategory(category);
        entity.setLevel(request.difficulty().trim().toLowerCase(Locale.ROOT));
        entity.setFormat(toDbFormat(request.format()));
        entity.setDurationMin(parseDuration(request.duration()));
        entity.setRewardNex(request.rewardNex());
        entity.setEmoji(icon(category));
        entity.setTint(tint(category));
        entity.setStatus(1);
        entity.setVersionNo(parseVersion(version));
        applyQuiz(entity, request);
        entity.setRevision(longValue(entity.getRevision()) + 1L);
        entity.setUpdatedAt(now);
        helpArticleMapper.updateById(entity);
        for (LearningCourseVersionEntity row : versions) {
            String nextStatus = row.getId().equals(selected.getId()) ? "PUBLISHED"
                    : "PUBLISHED".equals(row.getStatus()) ? "SUPERSEDED" : row.getStatus();
            if (!nextStatus.equals(row.getStatus())) {
                row.setStatus(nextStatus);
                row.setUpdatedAt(now);
                courseVersionMapper.updateById(row);
            }
        }
        saveMessagePair("learn." + courseId + ".title", request.titleZh().trim(), request.titleEn().trim(), request.titleVi().trim(), "published", now);
        saveMessagePair("learn." + courseId + ".body", request.bodyZh().trim(), request.bodyEn().trim(), request.bodyVi().trim(), "published", now);
        return findCourse(courseId).orElseGet(() -> toCourseView(entity));
    }

    private LearningCourseVersionView toCourseVersionView(LearningCourseVersionEntity entity) {
        return new LearningCourseVersionView(entity.getCourseId(), entity.getVersionLabel(), entity.getStatus(),
                readCourseVersionPayload(entity.getPayloadJson()), longValue(entity.getRevision()), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private LearningCourseUpsertRequest readCourseVersionPayload(String payload) {
        try {
            return JSON.readValue(payload, LearningCourseUpsertRequest.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("LEARNING_COURSE_VERSION_DESERIALIZATION_FAILED", ex);
        }
    }

    private int parseVersion(String version) {
        try { return Integer.parseInt(version.substring(1)); }
        catch (Exception ex) { throw new IllegalArgumentException("LEARNING_COURSE_VERSION_INVALID", ex); }
    }

    @Override
    public BigDecimal weeklyGrantedLearningReward() {
        BigDecimal value = appLearningMapper.sumGrantedRewardThisWeek();
        return value == null ? BigDecimal.ZERO : value;
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

    private I18nMessageVersionEntity latestVersion(String messageKey, String status) {
        return messageVersionMapper.selectOne(new LambdaQueryWrapper<I18nMessageVersionEntity>()
                .eq(I18nMessageVersionEntity::getMessageKey, messageKey.trim())
                .eq(StringUtils.hasText(status), I18nMessageVersionEntity::getStatus, status)
                .eq(I18nMessageVersionEntity::getIsDeleted, 0)
                .orderByDesc(I18nMessageVersionEntity::getVersionNo).last("LIMIT 1"));
    }

    private I18nMessagePairView toPair(String messageKey, List<I18nMessageEntity> rows, String version, String forcedStatus) {
        String en = "";
        String zh = "";
        String vi = "";
        int minStatus = 1;
        for (I18nMessageEntity row : rows) {
            if ("en-US".equalsIgnoreCase(row.getLocale())) {
                en = text(row.getMessageValue(), "");
            }
            if ("zh-CN".equalsIgnoreCase(row.getLocale())) {
                zh = text(row.getMessageValue(), "");
            }
            if ("vi-VN".equalsIgnoreCase(row.getLocale())) {
                vi = text(row.getMessageValue(), "");
            }
            if (row.getStatus() == null || row.getStatus() == 0) {
                minStatus = 0;
            }
        }
        String status = StringUtils.hasText(forcedStatus) ? forcedStatus : (minStatus == 1 ? "published" : "draft");
        Set<String> placeholders = new TreeSet<>();
        placeholders.addAll(placeholders(en));
        placeholders.addAll(placeholders(zh));
        placeholders.addAll(placeholders(vi));
        return new I18nMessagePairView(messageKey, namespaceOf(messageKey), en, zh, vi, status, version, List.copyOf(placeholders));
    }

    private I18nMessagePairView toVersionPair(I18nMessageVersionEntity entity) {
        String zh = text(entity.getZhValue(), ""), en = text(entity.getEnValue(), ""), vi = text(entity.getViValue(), "");
        Set<String> all = new TreeSet<>();
        all.addAll(placeholders(zh)); all.addAll(placeholders(en)); all.addAll(placeholders(vi));
        return new I18nMessagePairView(entity.getMessageKey(), namespaceOf(entity.getMessageKey()), en, zh, vi,
                normalizedStatus(entity.getStatus()), versionLabel(entity.getVersionNo()), List.copyOf(all));
    }

    private String versionLabel(Integer versionNo) {
        return "v" + Math.max(1, value(versionNo));
    }

    private void upsertIntegrity(String code, String kind, List<String> samples, int sortOrder, LocalDateTime now) {
        I18nIntegrityIssueEntity entity = integrityIssueMapper.selectOne(new LambdaQueryWrapper<I18nIntegrityIssueEntity>()
                .eq(I18nIntegrityIssueEntity::getIssueCode, code).eq(I18nIntegrityIssueEntity::getIsDeleted, 0).last("LIMIT 1"));
        if (entity == null) {
            entity = new I18nIntegrityIssueEntity(); entity.setIssueCode(code); entity.setCreatedAt(now); entity.setIsDeleted(0);
        }
        entity.setIssueKind(kind); entity.setIssueCount(samples.size()); entity.setSamplesText(String.join("\n", samples));
        entity.setStatus(samples.isEmpty() ? "fixed" : "open"); entity.setSortOrder(sortOrder); entity.setUpdatedAt(now);
        if (entity.getId() == null) integrityIssueMapper.insert(entity); else integrityIssueMapper.updateById(entity);
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
        String id = courseId(entity.getArticleCode());
        I18nMessagePairView title = findMessagePair("learn." + id + ".title").orElse(null);
        I18nMessagePairView body = findMessagePair("learn." + id + ".body").orElse(null);
        String titleZh = title == null ? text(entity.getTitle(), "") : text(title.zh(), text(entity.getTitle(), ""));
        String titleEn = title == null ? "" : text(title.en(), "");
        String titleVi = title == null ? "" : text(title.vi(), "");
        String bodyZh = body == null ? text(entity.getContent(), "") : text(body.zh(), text(entity.getContent(), ""));
        String bodyEn = body == null ? "" : text(body.en(), "");
        String bodyVi = body == null ? "" : text(body.vi(), "");
        return new LearningCourseView(
                id,
                titleZh,
                toViewCategory(entity.getCategory()),
                toViewFormat(entity.getFormat()),
                toViewLevel(entity.getLevel()),
                entity.getRewardNex() == null ? BigDecimal.ZERO : entity.getRewardNex().stripTrailingZeros(),
                entity.getFeatured() != null && entity.getFeatured() == 1,
                value(entity.getDurationMin()) + " min",
                "v" + Math.max(1, value(entity.getVersionNo())),
                toViewCourseStatus(entity.getStatus()),
                bodyZh,
                titleZh,
                titleEn,
                bodyZh,
                bodyEn,
                readQuiz(entity.getQuizJson()),
                entity.getQuizPassScore(),
                entity.getQuizRetryLimit(),
                text(entity.getCompletionCondition(), ""),
                text(entity.getRewardEvent(), ""),
                longValue(entity.getRevision()),
                titleVi,
                bodyVi);
    }

    private void applyQuiz(HelpArticleEntity entity, LearningCourseUpsertRequest request) {
        List<LearningQuizQuestionRequest> questions = request.quizQuestions() == null ? List.of() : request.quizQuestions();
        try {
            entity.setQuizJson(JSON.writeValueAsString(questions));
        } catch (Exception ex) {
            throw new IllegalArgumentException("LEARNING_COURSE_QUIZ_SERIALIZATION_FAILED", ex);
        }
        entity.setQuizPassScore(request.passScore());
        entity.setQuizRetryLimit(request.retryLimit());
        entity.setCompletionCondition(request.completionCondition());
        entity.setRewardEvent(request.rewardEvent());
    }

    private List<LearningQuizQuestionView> readQuiz(String quizJson) {
        if (!StringUtils.hasText(quizJson)) {
            return List.of();
        }
        try {
            List<LearningQuizQuestionRequest> rows = JSON.readValue(quizJson, new TypeReference<>() {});
            return rows.stream().map(row -> new LearningQuizQuestionView(
                    row.questionId(), row.questionZh(), row.questionEn(), row.optionsZh(), row.optionsEn(),
                    row.correctOptionIndex() == null ? 0 : row.correctOptionIndex(),
                    row.questionVi(), row.optionsVi())).toList();
        } catch (Exception ex) {
            return List.of();
        }
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

    private long longValue(Long value) {
        return value == null ? 0L : value;
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
