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
import ffdd.system.domain.ContentPage;
import ffdd.system.dto.ContentPageCreateRequest;
import ffdd.system.dto.ContentPageResponse;
import ffdd.system.dto.ContentPageUpdateRequest;
import ffdd.system.mapper.ContentPageMapper;
import ffdd.system.service.impl.SystemContentServiceImpl;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SystemContentServiceTest {
    private final ContentPageMapper contentPageMapper = mock(ContentPageMapper.class);
    private final SystemContentServiceImpl service = new SystemContentServiceImpl(contentPageMapper);

    @Test
    void createsActivePageByDefault() {
        when(contentPageMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        doAnswer(invocation -> {
                    ContentPage page = invocation.getArgument(0);
                    page.setId(21L);
                    page.setCreatedAt(LocalDateTime.now());
                    page.setUpdatedAt(LocalDateTime.now());
                    return 1;
                })
                .when(contentPageMapper)
                .insert(any(ContentPage.class));

        ContentPageCreateRequest request = new ContentPageCreateRequest();
        request.setPageCode("terms.service");
        request.setTitle("Terms of Service");
        request.setContent("Terms body");

        ContentPageResponse response = service.create(request);

        ArgumentCaptor<ContentPage> captor = ArgumentCaptor.forClass(ContentPage.class);
        verify(contentPageMapper).insert(captor.capture());
        assertThat(captor.getValue().getPageCode()).isEqualTo("terms.service");
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
        assertThat(captor.getValue().getIsDeleted()).isZero();
        assertThat(response.getId()).isEqualTo(21L);
    }

    @Test
    void updateChangesOnlyProvidedFields() {
        ContentPage page = page("about.nexion", "About", "old", 1);
        page.setId(22L);
        when(contentPageMapper.selectById(22L)).thenReturn(page);

        ContentPageUpdateRequest request = new ContentPageUpdateRequest();
        request.setTitle("About Nexion");
        request.setStatus(0);

        ContentPageResponse response = service.update(22L, request);

        ArgumentCaptor<ContentPage> captor = ArgumentCaptor.forClass(ContentPage.class);
        verify(contentPageMapper).updateById(captor.capture());
        assertThat(captor.getValue().getPageCode()).isEqualTo("about.nexion");
        assertThat(captor.getValue().getTitle()).isEqualTo("About Nexion");
        assertThat(captor.getValue().getContent()).isEqualTo("old");
        assertThat(captor.getValue().getStatus()).isZero();
        assertThat(response.getStatus()).isZero();
    }

    @Test
    void getActiveByCodeRejectsMissingPage() {
        when(contentPageMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service.getActiveByCode("terms.service"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void listCapsLimitAndMapsRows() {
        when(contentPageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(page("privacy.policy", "Privacy", "body", 1)));

        List<ContentPageResponse> responses = service.list("privacy", 1, 500);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getPageCode()).isEqualTo("privacy.policy");
    }

    private ContentPage page(String code, String title, String content, int status) {
        ContentPage page = new ContentPage();
        page.setId(1L);
        page.setPageCode(code);
        page.setTitle(title);
        page.setContent(content);
        page.setStatus(status);
        page.setIsDeleted(0);
        page.setCreatedAt(LocalDateTime.now().minusDays(1));
        page.setUpdatedAt(LocalDateTime.now());
        return page;
    }
}
