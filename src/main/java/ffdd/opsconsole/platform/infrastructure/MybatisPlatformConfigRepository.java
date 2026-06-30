package ffdd.opsconsole.platform.infrastructure;


import lombok.RequiredArgsConstructor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.platform.mapper.PlatformConfigItemMapper;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisPlatformConfigRepository implements PlatformConfigRepository {
    private final PlatformConfigItemMapper mapper;

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
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        return toDomain(entity);
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
