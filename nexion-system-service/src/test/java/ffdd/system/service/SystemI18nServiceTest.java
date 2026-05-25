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
import ffdd.system.domain.I18nMessage;
import ffdd.system.dto.I18nBatchQueryRequest;
import ffdd.system.dto.I18nMessageCreateRequest;
import ffdd.system.dto.I18nMessageResponse;
import ffdd.system.mapper.I18nMessageMapper;
import ffdd.system.service.impl.SystemI18nServiceImpl;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SystemI18nServiceTest {
    private final I18nMessageMapper i18nMessageMapper = mock(I18nMessageMapper.class);
    private final SystemI18nServiceImpl service = new SystemI18nServiceImpl(i18nMessageMapper);

    @Test
    void createsActiveMessageAndNormalizesLocale() {
        when(i18nMessageMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        doAnswer(invocation -> {
                    I18nMessage message = invocation.getArgument(0);
                    message.setId(10L);
                    message.setCreatedAt(LocalDateTime.now());
                    message.setUpdatedAt(LocalDateTime.now());
                    return 1;
                })
                .when(i18nMessageMapper)
                .insert(any(I18nMessage.class));

        I18nMessageCreateRequest request = new I18nMessageCreateRequest();
        request.setMessageKey("home.banner.title");
        request.setLocale("zh_CN");
        request.setMessageValue("Nexion 算力商城");

        I18nMessageResponse response = service.create(request);

        ArgumentCaptor<I18nMessage> captor = ArgumentCaptor.forClass(I18nMessage.class);
        verify(i18nMessageMapper).insert(captor.capture());
        assertThat(captor.getValue().getMessageKey()).isEqualTo("home.banner.title");
        assertThat(captor.getValue().getLocale()).isEqualTo("zh-CN");
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
        assertThat(captor.getValue().getIsDeleted()).isZero();
        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getLocale()).isEqualTo("zh-CN");
    }

    @Test
    void rejectsDuplicateMessageKeyAndLocale() {
        when(i18nMessageMapper.selectOne(any(Wrapper.class)))
                .thenReturn(message("home.banner.title", "en-US", "Nexion marketplace", 1));

        I18nMessageCreateRequest request = new I18nMessageCreateRequest();
        request.setMessageKey("home.banner.title");
        request.setLocale("en-US");
        request.setMessageValue("Nexion marketplace");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void batchGetActiveReturnsRequestOrderAndSkipsMissing() {
        I18nBatchQueryRequest request = new I18nBatchQueryRequest();
        request.setLocale("en_us");
        request.setMessageKeys(List.of("a.title", "missing.title", "b.title", "a.title"));

        I18nMessage second = message("b.title", "en-US", "B", 1);
        I18nMessage first = message("a.title", "en-US", "A", 1);
        when(i18nMessageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(second, first));

        List<I18nMessageResponse> responses = service.batchGetActive(request);

        assertThat(responses).extracting(I18nMessageResponse::getMessageKey).containsExactly("a.title", "b.title");
    }

    @Test
    void rejectsInvalidMessageKey() {
        assertThatThrownBy(() -> service.getActive("bad key", "en-US"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Invalid messageKey");
    }

    private I18nMessage message(String key, String locale, String value, int status) {
        I18nMessage message = new I18nMessage();
        message.setId(1L);
        message.setMessageKey(key);
        message.setLocale(locale);
        message.setMessageValue(value);
        message.setStatus(status);
        message.setIsDeleted(0);
        message.setCreatedAt(LocalDateTime.now().minusDays(1));
        message.setUpdatedAt(LocalDateTime.now());
        return message;
    }
}
