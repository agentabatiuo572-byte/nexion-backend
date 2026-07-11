package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.CopyAbRepository;
import ffdd.opsconsole.content.domain.CopyAudienceTarget;
import ffdd.opsconsole.content.domain.CopyContentRow;
import ffdd.opsconsole.content.domain.CopyExperimentRow;
import ffdd.opsconsole.content.domain.CopyExperimentVariantView;
import ffdd.opsconsole.content.domain.CopyFrameworkParamView;
import ffdd.opsconsole.content.domain.CopyPositionView;
import ffdd.opsconsole.content.domain.CopyVersionRow;
import ffdd.opsconsole.content.dto.CopyCreateRequest;
import ffdd.opsconsole.content.dto.CopyDraftSaveRequest;
import ffdd.opsconsole.content.dto.CopyPositionCreateRequest;
import ffdd.opsconsole.content.dto.CopyPositionUpdateRequest;
import ffdd.opsconsole.content.dto.CopyVersionPublishRequest;
import ffdd.opsconsole.content.mapper.CopyContentMapper;
import ffdd.opsconsole.content.mapper.CopyPositionMapper;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final CopyPositionMapper positionMapper;
    private final ObjectMapper objectMapper;

    private static final List<CopySeed> COPY_SEEDS = List.of();
    private static final List<VersionSeed> HCB_VERSIONS = List.of();
    private static final List<FrameworkSeed> FRAMEWORK_SEEDS = List.of();
    private static final List<ExperimentSeed> EXPERIMENT_SEEDS = List.of();

    @Override
    public void ensureSeedData(LocalDateTime now) {
        // Business rows must come from MySQL writes, not read-time demo seeds.
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
    public Optional<CopyContentRow> findCopyForUpdate(String copyKey) {
        return Optional.ofNullable(findCopyEntityForUpdate(copyKey)).map(this::toCopyRow);
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
    public List<String> listAllVersionNumbers(String copyKey) {
        return versionMapper.selectList(new LambdaQueryWrapper<CopyVersionEntity>()
                        .eq(CopyVersionEntity::getCopyKey, copyKey)
                        .orderByAsc(CopyVersionEntity::getId))
                .stream()
                .map(CopyVersionEntity::getVersion)
                .toList();
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
    public boolean isVersionReferencedByExperiment(String copyKey, String version) {
        List<CopyExperimentEntity> experiments = experimentMapper.selectList(new LambdaQueryWrapper<CopyExperimentEntity>()
                .eq(CopyExperimentEntity::getCopyKey, copyKey)
                .eq(CopyExperimentEntity::getIsDeleted, 0));
        for (CopyExperimentEntity experiment : experiments) {
            List<CopyExperimentVariantEntity> variants = variantMapper.selectList(
                    new LambdaQueryWrapper<CopyExperimentVariantEntity>()
                            .eq(CopyExperimentVariantEntity::getExperimentId, experiment.getExperimentId())
                            .eq(CopyExperimentVariantEntity::getIsDeleted, 0));
            if (variants.isEmpty() || variants.stream().anyMatch(variant ->
                    !StringUtils.hasText(variant.getCopyVersion())
                            || version.equalsIgnoreCase(variant.getCopyVersion().trim()))) {
                return true;
            }
        }
        return false;
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
        copy.setDraftVi(trimToNull(request.vi()));
        copy.setDraftCopyPosition(trimToNull(request.copyPosition()));
        copy.setDraftSurface(request.surface().trim());
        copy.setDraftAudience(request.audience().trim());
        copy.setDraftAudienceJson(writeAudience(request.audienceTarget()));
        copy.setDraftTrafficSplit(request.trafficSplit().trim());
        copy.setDraftNote(request.versionNote().trim());
        copy.setStatus("DRAFT_SAVED");
        copy.setRevision(nextRevision(copy));
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
        fillVersion(version, request.surface(), request.audience(), request.audienceTarget(), request.trafficSplit(), request.versionNote(),
                request.zh(), request.en(), request.vi(), request.copyPosition(), "DRAFT", operator(request.operator()), now);
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
        fillVersion(version, request.surface(), request.audience(), request.audienceTarget(), request.trafficSplit(), request.versionNote(),
                request.zh(), request.en(), request.vi(), request.copyPosition(), "PUBLISHED", operator(request.operator()), now);
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
        copy.setDraftVi(null);
        copy.setCopyPosition(trimToNull(request.copyPosition()));
        copy.setDraftCopyPosition(null);
        copy.setDraftSurface(null);
        copy.setDraftAudience(null);
        copy.setDraftAudienceJson(null);
        copy.setDraftTrafficSplit(null);
        copy.setDraftNote(null);
        copy.setRevision(nextRevision(copy));
        copy.setLastOperator(operator(request.operator()));
        copy.setUpdatedAt(now);
        copyMapper.updateById(copy);
        return findCopy(copyKey).orElse(toCopyRow(copy));
    }

    @Override
    public CopyContentRow createCopy(CopyCreateRequest request, LocalDateTime now) {
        // 新建文案位 = insert nx_content_copy(首版生效) + 一个 PUBLISHED version 行。
        String copyKey = request.copyKey().trim();
        String op = operator(request.operator());
        CopyContentEntity copy = new CopyContentEntity();
        copy.setCopyKey(copyKey);
        copy.setDescription(request.description().trim());
        copy.setSurface(request.surface().trim());
        copy.setCurrentVersion(request.version().trim());
        copy.setStatus("PUBLISHED");
        copy.setI18nKey(request.i18nKey().trim());
        copy.setCopyPosition(trimToNull(request.copyPosition()));
        copy.setLastChange(DAY_LABEL.format(now));
        copy.setRevision(1L);
        copy.setLastOperator(op);
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        copy.setIsDeleted(0);
        copyMapper.insert(copy);

        CopyVersionEntity version = new CopyVersionEntity();
        version.setCopyKey(copyKey);
        version.setVersion(request.version().trim());
        version.setCreatedAt(now);
        version.setIsDeleted(0);
        fillVersion(version, request.surface(), request.audience(), request.audienceTarget(), request.trafficSplit(), request.versionNote(),
                request.zh(), request.en(), request.vi(), request.copyPosition(), "PUBLISHED", op, now);
        version.setTsLabel(TS_LABEL.format(now));
        versionMapper.insert(version);

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
        copy.setCopyPosition(target.getCopyPosition());
        copy.setStatus("PUBLISHED");
        copy.setLastChange(DAY_LABEL.format(now));
        copy.setRevision(nextRevision(copy));
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
        copy.setRevision(nextRevision(copy));
        copy.setLastOperator(operator(operator));
        copy.setUpdatedAt(now);
        copyMapper.updateById(copy);
        return findCopy(copyKey).orElse(toCopyRow(copy));
    }

    @Override
    public CopyContentRow deleteDraftVersion(String copyKey, String version, String operator, LocalDateTime now) {
        CopyContentEntity copy = findCopyEntity(copyKey);
        CopyVersionEntity draft = findVersionEntity(copyKey, version);
        if (copy == null || draft == null) {
            return null;
        }

        String normalizedOperator = operator(operator);
        draft.setIsDeleted(1);
        draft.setChain(normalizedOperator + " / deleted");
        draft.setLastOperator(normalizedOperator);
        draft.setUpdatedAt(now);
        versionMapper.updateById(draft);

        CopyVersionEntity current = findVersionEntity(copyKey, copy.getCurrentVersion());
        String restoredStatus = current != null && "PUBLISHED".equalsIgnoreCase(current.getStatus())
                ? "PUBLISHED"
                : "ARCHIVED";
        copy.setStatus(restoredStatus);
        copy.setDraftVersion(null);
        copy.setDraftZh(null);
        copy.setDraftEn(null);
        copy.setDraftVi(null);
        copy.setDraftCopyPosition(null);
        copy.setDraftSurface(null);
        copy.setDraftAudience(null);
        copy.setDraftAudienceJson(null);
        copy.setDraftTrafficSplit(null);
        copy.setDraftNote(null);
        copy.setRevision(nextRevision(copy));
        copy.setLastChange(DAY_LABEL.format(now));
        copy.setLastOperator(normalizedOperator);
        copy.setUpdatedAt(now);
        // MyBatis-Plus updateById skips null values by default. The draft columns must be
        // explicitly set to NULL or a deleted draft would remain attached to the copy row.
        copyMapper.update(null, new UpdateWrapper<CopyContentEntity>()
                .eq("id", copy.getId())
                .eq("is_deleted", 0)
                .set("status", restoredStatus)
                .set("draft_version", null)
                .set("draft_zh", null)
                .set("draft_en", null)
                .set("draft_vi", null)
                .set("draft_copy_position", null)
                .set("draft_surface", null)
                .set("draft_audience", null)
                .set("draft_audience_json", null)
                .set("draft_traffic_split", null)
                .set("draft_note", null)
                .set("revision", copy.getRevision())
                .set("last_change", copy.getLastChange())
                .set("last_operator", normalizedOperator)
                .set("updated_at", now));
        return toCopyRow(copy);
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

    @Override
    public List<CopyPositionView> listPositions() {
        return positionMapper.selectList(new LambdaQueryWrapper<CopyPositionEntity>()
                        .eq(CopyPositionEntity::getIsDeleted, 0)
                        .orderByAsc(CopyPositionEntity::getSortOrder)
                        .orderByAsc(CopyPositionEntity::getId))
                .stream()
                .map(this::toPositionView)
                .toList();
    }

    @Override
    public Optional<CopyPositionView> findPosition(String positionKey) {
        return Optional.ofNullable(findPositionEntity(positionKey)).map(this::toPositionView);
    }

    @Override
    public Optional<CopyPositionView> findPositionForUpdate(String positionKey) {
        return Optional.ofNullable(findPositionEntityForUpdate(positionKey)).map(this::toPositionView);
    }

    @Override
    public CopyPositionView createPosition(CopyPositionCreateRequest request, LocalDateTime now) {
        CopyPositionEntity entity = new CopyPositionEntity();
        entity.setPositionKey(request.positionKey().trim());
        entity.setName(request.name().trim());
        entity.setSurface(request.surface().trim());
        entity.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        entity.setStatus("ACTIVE");
        entity.setLastOperator(operator(request.operator()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        positionMapper.insert(entity);
        return toPositionView(entity);
    }

    @Override
    public CopyPositionView updatePosition(String positionKey, CopyPositionUpdateRequest request, LocalDateTime now) {
        CopyPositionEntity entity = findPositionEntityForUpdate(positionKey);
        if (entity == null) {
            return null;
        }
        entity.setName(request.name().trim());
        entity.setSurface(request.surface().trim());
        entity.setSortOrder(request.sortOrder());
        entity.setStatus(request.status().trim().toUpperCase(Locale.ROOT));
        entity.setLastOperator(operator(request.operator()));
        entity.setUpdatedAt(now);
        positionMapper.updateById(entity);
        return toPositionView(entity);
    }

    @Override
    public void deletePosition(String positionKey, LocalDateTime now) {
        CopyPositionEntity entity = positionMapper.selectOne(new LambdaQueryWrapper<CopyPositionEntity>()
                .eq(CopyPositionEntity::getPositionKey, positionKey)
                .eq(CopyPositionEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (entity == null) {
            return;
        }
        positionMapper.deleteById(entity.getId());
    }

    @Override
    public boolean isPositionReferenced(String positionKey) {
        Long copyRefs = copyMapper.selectCount(new LambdaQueryWrapper<CopyContentEntity>()
                .eq(CopyContentEntity::getIsDeleted, 0)
                .and(wrapper -> wrapper.eq(CopyContentEntity::getCopyPosition, positionKey)
                        .or().eq(CopyContentEntity::getDraftCopyPosition, positionKey)));
        Long versionRefs = versionMapper.selectCount(new LambdaQueryWrapper<CopyVersionEntity>()
                .eq(CopyVersionEntity::getIsDeleted, 0)
                .eq(CopyVersionEntity::getCopyPosition, positionKey));
        return copyRefs > 0 || versionRefs > 0;
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
        entity.setRevision(1L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        copyMapper.insert(entity);
    }

    private long nextRevision(CopyContentEntity copy) {
        return (copy.getRevision() == null ? 0L : copy.getRevision()) + 1L;
    }

    private void ensureCurrentVersion(CopySeed seed, LocalDateTime now) {
        ensureVersion(version(seed.key(), seed.version(), "PUBLISHED", "seed / content", seed.lastChange() + " 09:00",
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
        entity.setCopyVersion(extractSequentialVersion(seed.name()));
        entity.setSplitPct(seed.splitPct());
        entity.setCvrPct(new BigDecimal(seed.cvr()));
        entity.setSortOrder(seed.sortOrder());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        variantMapper.insert(entity);
    }

    private String extractSequentialVersion(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?i)(?:^|[^a-z0-9])(v[0-9]+)(?:$|[^a-z0-9])").matcher(value);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private CopyContentEntity findCopyEntity(String copyKey) {
        return copyMapper.selectOne(new LambdaQueryWrapper<CopyContentEntity>()
                .eq(CopyContentEntity::getCopyKey, copyKey)
                .eq(CopyContentEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private CopyContentEntity findCopyEntityForUpdate(String copyKey) {
        return copyMapper.selectOne(new LambdaQueryWrapper<CopyContentEntity>()
                .eq(CopyContentEntity::getCopyKey, copyKey)
                .eq(CopyContentEntity::getIsDeleted, 0)
                .last("LIMIT 1 FOR UPDATE"));
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
                entity.getDraftVi(),
                entity.getCopyPosition(),
                entity.getDraftCopyPosition(),
                entity.getDraftSurface(),
                entity.getDraftAudience(),
                readAudience(entity.getDraftAudienceJson(), entity.getDraftAudience()),
                entity.getDraftTrafficSplit(),
                entity.getDraftNote(),
                entity.getRevision());
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
                entity.getViText(),
                entity.getCopyPosition(),
                entity.getSurface(),
                entity.getAudience(),
                readAudience(entity.getAudienceJson(), entity.getAudience()),
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
            CopyAudienceTarget audienceTarget,
            String trafficSplit,
            String versionNote,
            String zh,
            String en,
            String vi,
            String copyPosition,
            String status,
            String operator,
            LocalDateTime now) {
        entity.setStatus(status);
        entity.setChain(operator + " / content");
        entity.setZhText(zh.trim());
        entity.setEnText(en.trim());
        entity.setViText(vi == null ? null : vi.trim());
        entity.setCopyPosition(copyPosition == null ? null : copyPosition.trim());
        entity.setSurface(surface.trim());
        entity.setAudience(audience.trim());
        entity.setAudienceJson(writeAudience(audienceTarget));
        entity.setTrafficSplit(trafficSplit.trim());
        entity.setVersionNote(versionNote.trim());
        entity.setLastOperator(operator);
        entity.setUpdatedAt(now);
    }

    private CopyPositionEntity findPositionEntity(String positionKey) {
        return positionMapper.selectOne(new LambdaQueryWrapper<CopyPositionEntity>()
                .eq(CopyPositionEntity::getPositionKey, positionKey)
                .eq(CopyPositionEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private CopyPositionEntity findPositionEntityForUpdate(String positionKey) {
        return positionMapper.selectOne(new LambdaQueryWrapper<CopyPositionEntity>()
                .eq(CopyPositionEntity::getPositionKey, positionKey)
                .eq(CopyPositionEntity::getIsDeleted, 0)
                .last("LIMIT 1 FOR UPDATE"));
    }

    private CopyPositionView toPositionView(CopyPositionEntity entity) {
        return new CopyPositionView(entity.getPositionKey(), entity.getName(), entity.getSurface(),
                entity.getSortOrder() == null ? 0 : entity.getSortOrder(), toViewStatus(entity.getStatus()));
    }

    private String writeAudience(CopyAudienceTarget audienceTarget) {
        if (audienceTarget == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(audienceTarget);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("COPY_AUDIENCE_INVALID", ex);
        }
    }

    private CopyAudienceTarget readAudience(String json, String legacyAudience) {
        if (StringUtils.hasText(json)) {
            try {
                return objectMapper.readValue(json, CopyAudienceTarget.class);
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("COPY_AUDIENCE_CORRUPTED", ex);
            }
        }
        return legacyAudienceTarget(legacyAudience);
    }

    private CopyAudienceTarget legacyAudienceTarget(String legacyAudience) {
        if (!StringUtils.hasText(legacyAudience)) {
            return null;
        }
        String value = legacyAudience.trim();
        if ("全量".equals(value)) {
            return new CopyAudienceTarget("structured", List.of(), List.of(), null, null);
        }
        if ("P3 · 全语言".equals(value)) {
            return new CopyAudienceTarget("structured", List.of(), List.of("P3"), null, null);
        }
        if ("zh · 注册>30天".equals(value)) {
            return new CopyAudienceTarget("structured", List.of("zh"), List.of(), 31, null);
        }
        if ("注册 ≤14 天".equals(value)) {
            return new CopyAudienceTarget("structured", List.of(), List.of(), null, 14);
        }
        if ("P2-P3".equals(value)) {
            return new CopyAudienceTarget("structured", List.of(), List.of("P2", "P3"), null, null);
        }
        return null;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
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
