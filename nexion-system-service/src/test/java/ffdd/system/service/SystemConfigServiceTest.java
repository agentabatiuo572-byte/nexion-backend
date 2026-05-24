package ffdd.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import ffdd.common.exception.BizException;
import ffdd.system.domain.ConfigItem;
import ffdd.system.dto.ConfigItemCreateRequest;
import ffdd.system.dto.ConfigItemResponse;
import ffdd.system.dto.ConfigItemUpdateRequest;
import ffdd.system.mapper.ConfigItemMapper;
import ffdd.system.service.impl.SystemConfigServiceImpl;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SystemConfigServiceTest {
    private final ConfigItemMapper configItemMapper = mock(ConfigItemMapper.class);
    private final SystemConfigServiceImpl service = new SystemConfigServiceImpl(configItemMapper);

    @Test
    void createsActiveStringConfigByDefault() {
        when(configItemMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        doAnswer(invocation -> {
                    ConfigItem item = invocation.getArgument(0);
                    item.setId(9L);
                    item.setCreatedAt(LocalDateTime.now());
                    item.setUpdatedAt(LocalDateTime.now());
                    return 1;
                })
                .when(configItemMapper)
                .insert(any(ConfigItem.class));

        ConfigItemCreateRequest request = new ConfigItemCreateRequest();
        request.setConfigKey("risk.withdrawal.review_amount");
        request.setConfigValue("1000");

        ConfigItemResponse response = service.create(request);

        ArgumentCaptor<ConfigItem> captor = ArgumentCaptor.forClass(ConfigItem.class);
        verify(configItemMapper).insert(captor.capture());
        assertThat(captor.getValue().getConfigKey()).isEqualTo("risk.withdrawal.review_amount");
        assertThat(captor.getValue().getValueType()).isEqualTo("STRING");
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
        assertThat(captor.getValue().getIsDeleted()).isZero();
        assertThat(response.getId()).isEqualTo(9L);
    }

    @Test
    void rejectsDuplicateConfigKey() {
        when(configItemMapper.selectOne(any(Wrapper.class))).thenReturn(config("product.phase", "MVP", "STRING", 1));
        ConfigItemCreateRequest request = new ConfigItemCreateRequest();
        request.setConfigKey("product.phase");
        request.setConfigValue("GA");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void updateChangesOnlyProvidedFields() {
        ConfigItem item = config("openapi.default_qps_limit", "20", "NUMBER", 1);
        item.setId(8L);
        when(configItemMapper.selectById(8L)).thenReturn(item);

        ConfigItemUpdateRequest request = new ConfigItemUpdateRequest();
        request.setConfigValue("50");
        request.setRemark("temporary burst");

        ConfigItemResponse response = service.update(8L, request);

        ArgumentCaptor<ConfigItem> captor = ArgumentCaptor.forClass(ConfigItem.class);
        verify(configItemMapper).updateById(captor.capture());
        assertThat(captor.getValue().getConfigValue()).isEqualTo("50");
        assertThat(captor.getValue().getValueType()).isEqualTo("NUMBER");
        assertThat(captor.getValue().getRemark()).isEqualTo("temporary burst");
        assertThat(response.getConfigValue()).isEqualTo("50");
    }

    @Test
    void getActiveByKeyRejectsDisabledConfig() {
        when(configItemMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service.getActiveByKey("feature.genesis.enabled"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void batchGetActiveReturnsRequestOrderAndSkipsMissing() {
        ConfigItem second = config("b.key", "2", "NUMBER", 1);
        ConfigItem first = config("a.key", "1", "NUMBER", 1);
        when(configItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(second, first));

        List<ConfigItemResponse> responses = service.batchGetActive(List.of("a.key", "missing.key", "b.key"));

        assertThat(responses).extracting(ConfigItemResponse::getConfigKey).containsExactly("a.key", "b.key");
    }

    @Test
    void listCapsLimitAndMapsRows() {
        when(configItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(config("product.phase", "MVP", "STRING", 1)));

        List<ConfigItemResponse> responses = service.list("product", 1, 500);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getConfigKey()).isEqualTo("product.phase");
    }

    private ConfigItem config(String key, String value, String valueType, int status) {
        ConfigItem item = new ConfigItem();
        item.setId(1L);
        item.setConfigKey(key);
        item.setConfigValue(value);
        item.setValueType(valueType);
        item.setStatus(status);
        item.setRemark("test");
        item.setIsDeleted(0);
        item.setCreatedAt(LocalDateTime.now().minusDays(1));
        item.setUpdatedAt(LocalDateTime.now());
        return item;
    }
}
