package ffdd.opsconsole.platform.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformParamRegistrySource;
import ffdd.opsconsole.platform.mapper.PlatformConfigItemMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisPlatformParamRegistrySource implements PlatformParamRegistrySource {
    private final PlatformConfigItemMapper mapper;

    @Override
    public List<PlatformConfigItem> findAllActive() {
        return mapper.selectList(new LambdaQueryWrapper<PlatformConfigItemEntity>()
                        .eq(PlatformConfigItemEntity::getStatus, 1)
                        .eq(PlatformConfigItemEntity::getIsDeleted, 0)
                        .orderByAsc(PlatformConfigItemEntity::getConfigKey))
                .stream()
                .map(this::toDomain)
                .toList();
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
}
