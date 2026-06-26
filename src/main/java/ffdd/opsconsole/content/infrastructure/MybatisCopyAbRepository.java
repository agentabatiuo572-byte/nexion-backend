package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.content.domain.CopyAbRepository;
import ffdd.opsconsole.content.domain.CopyContentRow;
import ffdd.opsconsole.content.domain.CopyExperimentRow;
import ffdd.opsconsole.content.domain.CopyExperimentVariantView;
import ffdd.opsconsole.content.domain.CopyFrameworkParamView;
import ffdd.opsconsole.content.domain.CopyVersionRow;
import ffdd.opsconsole.content.dto.CopyDraftSaveRequest;
import ffdd.opsconsole.content.dto.CopyVersionPublishRequest;
import ffdd.opsconsole.content.mapper.CopyContentMapper;
import ffdd.opsconsole.content.mapper.CopyExperimentMapper;
import ffdd.opsconsole.content.mapper.CopyExperimentVariantMapper;
import ffdd.opsconsole.content.mapper.CopyFrameworkParamMapper;
import ffdd.opsconsole.content.mapper.CopyVersionMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MybatisCopyAbRepository implements CopyAbRepository {
    private static final DateTimeFormatter TS_LABEL = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");

    private final CopyContentMapper copyMapper;
    private final CopyVersionMapper versionMapper;
    private final CopyExperimentMapper experimentMapper;
    private final CopyExperimentVariantMapper variantMapper;
    private final CopyFrameworkParamMapper frameworkMapper;

    private static final List<CopySeed> COPY_SEEDS = List.of(
            copy("home.conversionBanner", "主转化横幅 · 激活设备每日收益话术", "Home", "v7", "PUBLISHED", "marketing.home.convBanner", "EXP-2611", "05-28"),
            copy("home.missedIncome", "错过收益条 · 与基准机型的日产差额", "Home", "v4", "PUBLISHED", "marketing.home.missedIncome", "EXP-2612", "05-30"),
            copy("home.heroCta", "首屏主按钮文案", "Home", "v9", "PUBLISHED", "marketing.home.heroCta", "", "05-21"),
            copy("home.trialNudge", "试用引导条(试用资格用户可见)", "Home", "v3", "PUBLISHED", "marketing.home.trialNudge", "", "04-30"),
            copy("home.paybackChip", "回本周期角标", "Home", "v2", "PUBLISHED", "marketing.home.paybackChip", "", "04-02"),
            copy("me.upgradeCard", "「该升级了」卡片 · 按机队推荐", "Me", "v5", "PUBLISHED", "marketing.me.upgradeCard", "EXP-2613", "06-02"),
            copy("me.referralNudge", "邀请返佣提示条", "Me", "v6", "PUBLISHED", "marketing.me.referralNudge", "", "05-11"),
            copy("me.vrankProgress", "V 等级进度话术", "Me", "v2", "PUBLISHED", "marketing.me.vrankProgress", "", "03-19"),
            copy("me.walletEmpty", "钱包空态引导", "Me", "v3", "PUBLISHED", "marketing.me.walletEmpty", "", "02-27"),
            copy("store.paybackHint", "商品卡回本提示", "商城", "v4", "PUBLISHED", "marketing.store.paybackHint", "EXP-2598", "05-06"),
            copy("store.bundleBanner", "捆绑购横幅", "商城", "v3", "PUBLISHED", "marketing.store.bundleBanner", "", "04-22"),
            copy("store.tradeinHook", "以旧换新钩子文案", "商城", "v2", "PUBLISHED", "marketing.store.tradeinHook", "", "03-30"));

    private static final List<VersionSeed> HCB_VERSIONS = List.of(
            version("home.conversionBanner", "v8", "DRAFT", "li.wen / -", "06-10 18:22",
                    "完成 {amount} USDT 复投并获得 {nex} NEX 奖励",
                    "Reinvest {amount} USDT and earn {nex} NEX", "Home", "全量", "50", "复投文案草稿"),
            version("home.conversionBanner", "v7", "PUBLISHED", "li.wen / chen.r(lead)", "05-28 11:04",
                    "激活 {targetName},每天赚 ${targetDaily},约 {paybackDays} 天回本,收益是 {lowestName} 的 {multiplier} 倍",
                    "Activate {targetName} · earn ${targetDaily}/day · payback ~{paybackDays} days · {multiplier}x {lowestName}",
                    "Home", "P3 · 全语言", "50", "当前线上版本"),
            version("home.conversionBanner", "v6", "ARCHIVED", "zhao.m / chen.r(lead)", "04-12 09:40",
                    "激活 {targetName},开启每日收益", "Activate {targetName} and start daily earnings",
                    "Home", "P2-P3", "50", "上一轮版本"),
            version("home.conversionBanner", "v5", "ARCHIVED", "zhao.m / 超管", "03-02 15:17",
                    "购买 {targetName},获得更多收益", "Buy {targetName} for higher earnings",
                    "Home", "全量", "50", "历史版本"));

    private static final List<FrameworkSeed> FRAMEWORK_SEEDS = List.of(
            framework("split", "分流比例默认", "变体等分", "默认按变体等分,上线前可调", 10),
            framework("aud", "受众定向默认", "全量", "默认覆盖全部可见用户", 20),
            framework("sample", "最小样本量", "5,000 / 变体", "未达样本量不允许采纳", 30),
            framework("maxrun", "最长运行期", "90 天", "超过最长运行期需要复核", 40));

    private static final List<ExperimentSeed> EXPERIMENT_SEEDS = List.of(
            experiment("EXP-2611", "home.conversionBanner", "P3 · 全语言", "412K", "18.3K", "RUNNING", "05-29 起 · 已 13 天",
                    List.of(variant("A(v7现版)", 50, "4.1", 10), variant("B(v8候选)", 50, "4.8", 20))),
            experiment("EXP-2612", "home.missedIncome", "全量", "388K", "13.6K", "RUNNING", "错过收益条多版本实验",
                    List.of(variant("A", 34, "3.2", 10), variant("B", 33, "3.9", 20), variant("C", 33, "3.4", 30))),
            experiment("EXP-2613", "me.upgradeCard", "zh · 注册>30天", "96K", "2.2K", "RUNNING", "升级卡片主文案实验",
                    List.of(variant("A", 50, "2.1", 10), variant("B", 50, "2.6", 20))),
            experiment("EXP-2607", "home.conversionBanner", "P2-P3", "1.02M", "37.8K", "ADOPTED", "v7 已采纳",
                    List.of(variant("A(v6)", 50, "3.4", 10), variant("B(v7)", 50, "4.0", 20))),
            experiment("EXP-2598", "store.paybackHint", "全量", "640K", "17.6K", "DISCARDED", "效果未达预期",
                    List.of(variant("A", 50, "2.8", 10), variant("B", 50, "2.7", 20))));

    @Override
    public void ensureSeedData(LocalDateTime now) {
        for (CopySeed seed : COPY_SEEDS) {
            ensureCopy(seed, now);
            if (!"home.conversionBanner".equals(seed.key())) {
                ensureCurrentVersion(seed, now);
            }
        }
        for (VersionSeed seed : HCB_VERSIONS) {
            ensureVersion(seed, now);
        }
        for (FrameworkSeed seed : FRAMEWORK_SEEDS) {
            ensureFramework(seed, now);
        }
        for (ExperimentSeed seed : EXPERIMENT_SEEDS) {
            ensureExperiment(seed, now);
        }
    }

    @Override
    public List<CopyContentRow> listCopies() {
        return copyMapper.selectList(new LambdaQueryWrapper<CopyContentEntity>()
                        .eq(CopyContentEntity::getIsDeleted, 0)
                        .orderByAsc(CopyContentEntity::getId))
                .stream()
                .map(this::toCopyRow)
                .toList();
    }

    @Override
    public Optional<CopyContentRow> findCopy(String copyKey) {
        return Optional.ofNullable(findCopyEntity(copyKey)).map(this::toCopyRow);
    }

    @Override
    public List<CopyVersionRow> listVersions(String copyKey) {
        return versionMapper.selectList(new LambdaQueryWrapper<CopyVersionEntity>()
                        .eq(CopyVersionEntity::getIsDeleted, 0)
                        .eq(StringUtils.hasText(copyKey), CopyVersionEntity::getCopyKey, copyKey)
                        .orderByDesc(CopyVersionEntity::getId))
                .stream()
                .map(this::toVersionRow)
                .toList();
    }

    @Override
    public Optional<CopyVersionRow> findVersion(String copyKey, String version) {
        return Optional.ofNullable(findVersionEntity(copyKey, version)).map(this::toVersionRow);
    }

    @Override
    public List<CopyExperimentRow> listExperiments() {
        return experimentMapper.selectList(new LambdaQueryWrapper<CopyExperimentEntity>()
                        .eq(CopyExperimentEntity::getIsDeleted, 0)
                        .orderByDesc(CopyExperimentEntity::getId))
                .stream()
                .map(this::toExperimentRow)
                .toList();
    }

    @Override
    public Optional<CopyExperimentRow> findExperiment(String experimentId) {
        return Optional.ofNullable(findExperimentEntity(experimentId)).map(this::toExperimentRow);
    }

    @Override
    public List<CopyFrameworkParamView> listFrameworkParams() {
        return frameworkMapper.selectList(new LambdaQueryWrapper<CopyFrameworkParamEntity>()
                        .eq(CopyFrameworkParamEntity::getIsDeleted, 0)
                        .orderByAsc(CopyFrameworkParamEntity::getSortOrder)
                        .orderByAsc(CopyFrameworkParamEntity::getId))
                .stream()
                .map(entity -> new CopyFrameworkParamView(
                        entity.getParamKey(),
                        entity.getParamName(),
                        entity.getCurrentValue(),
                        entity.getDescription()))
                .toList();
    }

    @Override
    public void saveDraft(String copyKey, CopyDraftSaveRequest request, LocalDateTime now) {
        CopyContentEntity copy = findCopyEntity(copyKey);
        if (copy == null) {
            return;
        }
        copy.setDraftVersion(request.version().trim());
        copy.setDraftZh(request.zh().trim());
        copy.setDraftEn(request.en().trim());
        copy.setDraftSurface(request.surface().trim());
        copy.setDraftAudience(request.audience().trim());
        copy.setDraftTrafficSplit(request.trafficSplit().trim());
        copy.setDraftNote(request.versionNote().trim());
        copy.setStatus("DRAFT_SAVED");
        copy.setLastOperator(operator(request.operator()));
        copy.setUpdatedAt(now);
        copyMapper.updateById(copy);

        CopyVersionEntity version = findVersionEntity(copyKey, request.version());
        if (version == null) {
            version = new CopyVersionEntity();
            version.setCopyKey(copyKey);
            version.setVersion(request.version().trim());
            version.setCreatedAt(now);
            version.setIsDeleted(0);
        }
        fillVersion(version, request.surface(), request.audience(), request.trafficSplit(), request.versionNote(),
                request.zh(), request.en(), "DRAFT", operator(request.operator()), now);
        version.setTsLabel(TS_LABEL.format(now));
        if (version.getId() == null) {
            versionMapper.insert(version);
        } else {
            versionMapper.updateById(version);
        }
    }

    @Override
    public CopyContentRow publishVersion(String copyKey, CopyVersionPublishRequest request, LocalDateTime now) {
        CopyContentEntity copy = findCopyEntity(copyKey);
        if (copy == null) {
            return null;
        }
        archivePublishedVersion(copyKey, now);

        CopyVersionEntity version = findVersionEntity(copyKey, request.version());
        if (version == null) {
            version = new CopyVersionEntity();
            version.setCopyKey(copyKey);
            version.setVersion(request.version().trim());
            version.setCreatedAt(now);
            version.setIsDeleted(0);
        }
        fillVersion(version, request.surface(), request.audience(), request.trafficSplit(), request.versionNote(),
                request.zh(), request.en(), "PUBLISHED", operator(request.operator()), now);
        version.setTsLabel(TS_LABEL.format(now));
        if (version.getId() == null) {
            versionMapper.insert(version);
        } else {
            versionMapper.updateById(version);
        }

        copy.setSurface(request.surface().trim());
        copy.setCurrentVersion(request.version().trim());
        copy.setStatus("PUBLISHED");
        copy.setLastChange(DAY_LABEL.format(now));
        copy.setDraftVersion(null);
        copy.setDraftZh(null);
        copy.setDraftEn(null);
        copy.setDraftSurface(null);
        copy.setDraftAudience(null);
        copy.setDraftTrafficSplit(null);
        copy.setDraftNote(null);
        copy.setLastOperator(operator(request.operator()));
        copy.setUpdatedAt(now);
        copyMapper.updateById(copy);
        return findCopy(copyKey).orElse(toCopyRow(copy));
    }

    @Override
    public CopyContentRow rollbackVersion(String copyKey, String version, String operator, LocalDateTime now) {
        CopyContentEntity copy = findCopyEntity(copyKey);
        CopyVersionEntity target = findVersionEntity(copyKey, version);
        if (copy == null || target == null) {
            return null;
        }
        archivePublishedVersion(copyKey, now);
        target.setStatus("PUBLISHED");
        target.setChain(operator(operator) + " / rollback");
        target.setTsLabel(TS_LABEL.format(now));
        target.setLastOperator(operator(operator));
        target.setUpdatedAt(now);
        versionMapper.updateById(target);

        copy.setCurrentVersion(target.getVersion());
        copy.setSurface(target.getSurface());
        copy.setStatus("PUBLISHED");
        copy.setLastChange(DAY_LABEL.format(now));
        copy.setLastOperator(operator(operator));
        copy.setUpdatedAt(now);
        copyMapper.updateById(copy);
        return findCopy(copyKey).orElse(toCopyRow(copy));
    }

    @Override
    public CopyContentRow archiveCurrent(String copyKey, String operator, LocalDateTime now) {
        CopyContentEntity copy = findCopyEntity(copyKey);
        if (copy == null) {
            return null;
        }
        CopyVersionEntity current = findVersionEntity(copyKey, copy.getCurrentVersion());
        if (current != null) {
            current.setStatus("ARCHIVED");
            current.setChain(operator(operator) + " / archived");
            current.setLastOperator(operator(operator));
            current.setUpdatedAt(now);
            versionMapper.updateById(current);
        }
        copy.setStatus("ARCHIVED");
        copy.setLastChange(DAY_LABEL.format(now));
        copy.setLastOperator(operator(operator));
        copy.setUpdatedAt(now);
        copyMapper.updateById(copy);
        return findCopy(copyKey).orElse(toCopyRow(copy));
    }

    @Override
    public void updateFrameworkParam(String paramKey, String value, String operator, LocalDateTime now) {
        CopyFrameworkParamEntity entity = findFrameworkEntity(paramKey);
        if (entity == null) {
            return;
        }
        entity.setCurrentValue(value.trim());
        entity.setLastOperator(operator(operator));
        entity.setUpdatedAt(now);
        frameworkMapper.updateById(entity);
    }

    @Override
    public void updateExperimentState(String experimentId, String state, String operator, LocalDateTime now) {
        CopyExperimentEntity entity = findExperimentEntity(experimentId);
        if (entity == null) {
            return;
        }
        entity.setState(state.trim().toUpperCase(Locale.ROOT));
        entity.setLastOperator(operator(operator));
        entity.setUpdatedAt(now);
        experimentMapper.updateById(entity);
    }

    private void archivePublishedVersion(String copyKey, LocalDateTime now) {
        versionMapper.selectList(new LambdaQueryWrapper<CopyVersionEntity>()
                        .eq(CopyVersionEntity::getCopyKey, copyKey)
                        .eq(CopyVersionEntity::getStatus, "PUBLISHED")
                        .eq(CopyVersionEntity::getIsDeleted, 0))
                .forEach(entity -> {
                    entity.setStatus("ARCHIVED");
                    entity.setUpdatedAt(now);
                    versionMapper.updateById(entity);
                });
    }

    private void ensureCopy(CopySeed seed, LocalDateTime now) {
        if (findCopyEntity(seed.key()) != null) {
            return;
        }
        CopyContentEntity entity = new CopyContentEntity();
        entity.setCopyKey(seed.key());
        entity.setDescription(seed.description());
        entity.setSurface(seed.surface());
        entity.setCurrentVersion(seed.version());
        entity.setStatus(seed.status());
        entity.setI18nKey(seed.i18nKey());
        entity.setExperimentId(seed.experimentId());
        entity.setLastChange(seed.lastChange());
        if ("home.conversionBanner".equals(seed.key())) {
            entity.setDraftVersion("v8");
            entity.setDraftZh("完成 {amount} USDT 复投并获得 {nex} NEX 奖励");
            entity.setDraftEn("Reinvest {amount} USDT and earn {nex} NEX");
            entity.setDraftSurface("Home");
            entity.setDraftAudience("全量");
            entity.setDraftTrafficSplit("50");
            entity.setDraftNote("复投文案草稿");
        }
        entity.setLastOperator("seed");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        copyMapper.insert(entity);
    }

    private void ensureCurrentVersion(CopySeed seed, LocalDateTime now) {
        ensureVersion(version(seed.key(), seed.version(), "PUBLISHED", "seed / content lead", seed.lastChange() + " 09:00",
                seed.description() + " 中文文案", seed.key() + " English copy", seed.surface(), "全量", "50", "seed current version"), now);
    }

    private void ensureVersion(VersionSeed seed, LocalDateTime now) {
        if (findVersionEntity(seed.copyKey(), seed.version()) != null) {
            return;
        }
        CopyVersionEntity entity = new CopyVersionEntity();
        entity.setCopyKey(seed.copyKey());
        entity.setVersion(seed.version());
        entity.setStatus(seed.status());
        entity.setChain(seed.chain());
        entity.setTsLabel(seed.tsLabel());
        entity.setZhText(seed.zh());
        entity.setEnText(seed.en());
        entity.setSurface(seed.surface());
        entity.setAudience(seed.audience());
        entity.setTrafficSplit(seed.trafficSplit());
        entity.setVersionNote(seed.note());
        entity.setLastOperator("seed");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        versionMapper.insert(entity);
    }

    private void ensureFramework(FrameworkSeed seed, LocalDateTime now) {
        if (findFrameworkEntity(seed.key()) != null) {
            return;
        }
        CopyFrameworkParamEntity entity = new CopyFrameworkParamEntity();
        entity.setParamKey(seed.key());
        entity.setParamName(seed.name());
        entity.setCurrentValue(seed.current());
        entity.setDescription(seed.description());
        entity.setSortOrder(seed.sortOrder());
        entity.setLastOperator("seed");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        frameworkMapper.insert(entity);
    }

    private void ensureExperiment(ExperimentSeed seed, LocalDateTime now) {
        if (findExperimentEntity(seed.id()) == null) {
            CopyExperimentEntity entity = new CopyExperimentEntity();
            entity.setExperimentId(seed.id());
            entity.setCopyKey(seed.copyKey());
            entity.setAudience(seed.audience());
            entity.setImpressionsLabel(seed.impressions());
            entity.setConversionsLabel(seed.conversions());
            entity.setState(seed.state());
            entity.setNote(seed.note());
            entity.setLastOperator("seed");
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            entity.setIsDeleted(0);
            experimentMapper.insert(entity);
        }
        for (VariantSeed variant : seed.variants()) {
            ensureVariant(seed.id(), variant, now);
        }
    }

    private void ensureVariant(String experimentId, VariantSeed seed, LocalDateTime now) {
        CopyExperimentVariantEntity existing = variantMapper.selectOne(new LambdaQueryWrapper<CopyExperimentVariantEntity>()
                .eq(CopyExperimentVariantEntity::getExperimentId, experimentId)
                .eq(CopyExperimentVariantEntity::getVariantName, seed.name())
                .eq(CopyExperimentVariantEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        CopyExperimentVariantEntity entity = new CopyExperimentVariantEntity();
        entity.setExperimentId(experimentId);
        entity.setVariantName(seed.name());
        entity.setSplitPct(seed.splitPct());
        entity.setCvrPct(new BigDecimal(seed.cvr()));
        entity.setSortOrder(seed.sortOrder());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        variantMapper.insert(entity);
    }

    private CopyContentEntity findCopyEntity(String copyKey) {
        return copyMapper.selectOne(new LambdaQueryWrapper<CopyContentEntity>()
                .eq(CopyContentEntity::getCopyKey, copyKey)
                .eq(CopyContentEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private CopyVersionEntity findVersionEntity(String copyKey, String version) {
        return versionMapper.selectOne(new LambdaQueryWrapper<CopyVersionEntity>()
                .eq(CopyVersionEntity::getCopyKey, copyKey)
                .eq(CopyVersionEntity::getVersion, version)
                .eq(CopyVersionEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private CopyExperimentEntity findExperimentEntity(String experimentId) {
        return experimentMapper.selectOne(new LambdaQueryWrapper<CopyExperimentEntity>()
                .eq(CopyExperimentEntity::getExperimentId, experimentId)
                .eq(CopyExperimentEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private CopyFrameworkParamEntity findFrameworkEntity(String paramKey) {
        return frameworkMapper.selectOne(new LambdaQueryWrapper<CopyFrameworkParamEntity>()
                .eq(CopyFrameworkParamEntity::getParamKey, paramKey)
                .eq(CopyFrameworkParamEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private CopyContentRow toCopyRow(CopyContentEntity entity) {
        return new CopyContentRow(
                entity.getCopyKey(),
                entity.getDescription(),
                entity.getSurface(),
                entity.getCurrentVersion(),
                toViewStatus(entity.getStatus()),
                entity.getI18nKey(),
                StringUtils.hasText(entity.getExperimentId()) ? entity.getExperimentId() : "-",
                entity.getLastChange(),
                entity.getDraftVersion(),
                entity.getDraftZh(),
                entity.getDraftEn(),
                entity.getDraftSurface(),
                entity.getDraftAudience(),
                entity.getDraftTrafficSplit(),
                entity.getDraftNote());
    }

    private CopyVersionRow toVersionRow(CopyVersionEntity entity) {
        return new CopyVersionRow(
                entity.getCopyKey(),
                entity.getVersion(),
                toViewStatus(entity.getStatus()),
                entity.getChain(),
                entity.getTsLabel(),
                entity.getZhText(),
                entity.getEnText(),
                entity.getSurface(),
                entity.getAudience(),
                entity.getTrafficSplit(),
                entity.getVersionNote());
    }

    private CopyExperimentRow toExperimentRow(CopyExperimentEntity entity) {
        List<CopyExperimentVariantView> variants = variantMapper.selectList(new LambdaQueryWrapper<CopyExperimentVariantEntity>()
                        .eq(CopyExperimentVariantEntity::getExperimentId, entity.getExperimentId())
                        .eq(CopyExperimentVariantEntity::getIsDeleted, 0)
                        .orderByAsc(CopyExperimentVariantEntity::getSortOrder)
                        .orderByAsc(CopyExperimentVariantEntity::getId))
                .stream()
                .map(variant -> new CopyExperimentVariantView(
                        variant.getVariantName(),
                        variant.getSplitPct() == null ? 0 : variant.getSplitPct(),
                        variant.getCvrPct()))
                .toList();
        return new CopyExperimentRow(
                entity.getExperimentId(),
                entity.getCopyKey(),
                variants,
                entity.getAudience(),
                entity.getImpressionsLabel(),
                entity.getConversionsLabel(),
                toViewStatus(entity.getState()),
                entity.getNote());
    }

    private void fillVersion(
            CopyVersionEntity entity,
            String surface,
            String audience,
            String trafficSplit,
            String versionNote,
            String zh,
            String en,
            String status,
            String operator,
            LocalDateTime now) {
        entity.setStatus(status);
        entity.setChain(operator + " / content lead");
        entity.setZhText(zh.trim());
        entity.setEnText(en.trim());
        entity.setSurface(surface.trim());
        entity.setAudience(audience.trim());
        entity.setTrafficSplit(trafficSplit.trim());
        entity.setVersionNote(versionNote.trim());
        entity.setLastOperator(operator);
        entity.setUpdatedAt(now);
    }

    private String toViewStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT).replace('_', '-') : "";
    }

    private String operator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private static CopySeed copy(String key, String description, String surface, String version, String status, String i18nKey, String experimentId, String lastChange) {
        return new CopySeed(key, description, surface, version, status, i18nKey, experimentId, lastChange);
    }

    private static VersionSeed version(String copyKey, String version, String status, String chain, String tsLabel, String zh, String en, String surface, String audience, String trafficSplit, String note) {
        return new VersionSeed(copyKey, version, status, chain, tsLabel, zh, en, surface, audience, trafficSplit, note);
    }

    private static FrameworkSeed framework(String key, String name, String current, String description, int sortOrder) {
        return new FrameworkSeed(key, name, current, description, sortOrder);
    }

    private static ExperimentSeed experiment(String id, String copyKey, String audience, String impressions, String conversions, String state, String note, List<VariantSeed> variants) {
        return new ExperimentSeed(id, copyKey, audience, impressions, conversions, state, note, variants);
    }

    private static VariantSeed variant(String name, int splitPct, String cvr, int sortOrder) {
        return new VariantSeed(name, splitPct, cvr, sortOrder);
    }

    private record CopySeed(String key, String description, String surface, String version, String status, String i18nKey, String experimentId, String lastChange) {
    }

    private record VersionSeed(String copyKey, String version, String status, String chain, String tsLabel, String zh, String en, String surface, String audience, String trafficSplit, String note) {
    }

    private record FrameworkSeed(String key, String name, String current, String description, int sortOrder) {
    }

    private record ExperimentSeed(String id, String copyKey, String audience, String impressions, String conversions, String state, String note, List<VariantSeed> variants) {
    }

    private record VariantSeed(String name, int splitPct, String cvr, int sortOrder) {
    }
}
