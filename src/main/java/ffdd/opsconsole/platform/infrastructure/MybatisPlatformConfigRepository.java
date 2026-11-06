package ffdd.opsconsole.platform.infrastructure;


import lombok.RequiredArgsConstructor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.platform.dto.PlatformConfigChangedEvent;
import ffdd.opsconsole.platform.mapper.PlatformConfigItemMapper;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisPlatformConfigRepository implements PlatformConfigRepository, ApplicationEventPublisherAware {
    private final PlatformConfigItemMapper mapper;
    private ApplicationEventPublisher events;

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public Optional<PlatformConfigItem> findActiveByKey(String configKey) {
        PlatformConfigItemEntity entity = mapper.selectOne(new LambdaQueryWrapper<PlatformConfigItemEntity>()
                .eq(PlatformConfigItemEntity::getConfigKey, configKey)
                .eq(PlatformConfigItemEntity::getStatus, 1)
                .eq(PlatformConfigItemEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public Optional<PlatformConfigItem> findAnyByKey(String configKey) {
        PlatformConfigItemEntity entity = mapper.selectOne(new LambdaQueryWrapper<PlatformConfigItemEntity>()
                .eq(PlatformConfigItemEntity::getConfigKey, configKey)
                .last("LIMIT 1"));
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public Optional<PlatformConfigItem> findStoredByKey(String configKey) {
        PlatformConfigItemEntity entity = mapper.selectOne(new LambdaQueryWrapper<PlatformConfigItemEntity>()
                .eq(PlatformConfigItemEntity::getConfigKey, configKey)
                .eq(PlatformConfigItemEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public Optional<PlatformConfigItem> findActiveByKeyForUpdate(String configKey) {
        return Optional.ofNullable(mapper.selectActiveByKeyForUpdate(configKey)).map(this::toDomain);
    }

    @Override
    public List<PlatformConfigItem> findActiveByGroups(Collection<String> configGroups) {
        if (configGroups == null || configGroups.isEmpty()) {
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<PlatformConfigItemEntity>()
                        .in(PlatformConfigItemEntity::getConfigGroup, configGroups)
                        .eq(PlatformConfigItemEntity::getStatus, 1)
                        .eq(PlatformConfigItemEntity::getIsDeleted, 0)
                        .orderByAsc(PlatformConfigItemEntity::getConfigKey))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public PlatformConfigItem save(PlatformConfigItem item) {
        PlatformConfigItemEntity entity = toEntity(item);
        if (entity.getId() == null) {
            entity.setIsDeleted(0);
            if (mapper.insert(entity) == 1) publishPublicChange(item.configKey());
        } else {
            if (mapper.updateById(entity) == 1) publishPublicChange(item.configKey());
        }
        return toDomain(entity);
    }

    @Override
    public boolean insertIfAbsent(PlatformConfigItem item) {
        boolean inserted = mapper.insertIfConfigKeyAbsent(
                item.configKey(),
                item.configValue(),
                item.valueType(),
                item.configGroup(),
                item.visibility(),
                item.remark(),
                item.status()) == 1;
        if (inserted) publishPublicChange(item.configKey());
        return inserted;
    }

    @Override
    public boolean reactivateIfInactive(PlatformConfigItem item) {
        boolean reactivated = mapper.reactivateConfigIfInactive(item) == 1;
        if (reactivated) publishPublicChange(item.configKey());
        return reactivated;
    }

    private void publishPublicChange(String key) {
        if (key.startsWith("platform.experience.") || key.startsWith("E.compute.")
                || key.startsWith("K.rewards.") || key.startsWith("growth.phase.month.")
                || key.startsWith("growth.public_stats.") || key.startsWith("h9.sb.")
                || key.equals("G.staking.app_visible") || key.equals("dailyUsdtPerBaseline")
                || key.equals("platform.phase.config")) {
            PlatformConfigChangedEvent.publish(events);
        }
    }

    private PlatformConfigItem toDomain(PlatformConfigItemEntity entity) {
        return new PlatformConfigItem(
                entity.getId(),
                entity.getConfigKey(),
                entity.getConfigValue(),
                entity.getValueType(),
                entity.getConfigGroup(),
                entity.getVisibility(),
                entity.getRemark(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private PlatformConfigItemEntity toEntity(PlatformConfigItem item) {
        PlatformConfigItemEntity entity = new PlatformConfigItemEntity();
        entity.setId(item.id());
        entity.setConfigKey(item.configKey());
        entity.setConfigValue(item.configValue());
        entity.setValueType(item.valueType());
        entity.setConfigGroup(item.configGroup());
        entity.setVisibility(item.visibility());
        entity.setRemark(item.remark());
        entity.setStatus(item.status());
        entity.setCreatedAt(item.createdAt());
        entity.setUpdatedAt(item.updatedAt());
        entity.setIsDeleted(0);
        return entity;
    }
}
