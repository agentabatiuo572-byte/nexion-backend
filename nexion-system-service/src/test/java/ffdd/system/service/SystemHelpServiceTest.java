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
import ffdd.system.domain.HelpArticle;
import ffdd.system.dto.HelpArticleCreateRequest;
import ffdd.system.dto.HelpArticleResponse;
import ffdd.system.dto.HelpArticleUpdateRequest;
import ffdd.system.mapper.HelpArticleMapper;
import ffdd.system.service.impl.SystemHelpServiceImpl;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SystemHelpServiceTest {
    private final HelpArticleMapper helpArticleMapper = mock(HelpArticleMapper.class);
    private final SystemHelpServiceImpl service = new SystemHelpServiceImpl(helpArticleMapper);

    @Test
    void createsArticleWithDefaultSortAndStatus() {
        when(helpArticleMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        doAnswer(invocation -> {
                    HelpArticle article = invocation.getArgument(0);
                    article.setId(31L);
                    article.setCreatedAt(LocalDateTime.now());
                    article.setUpdatedAt(LocalDateTime.now());
                    return 1;
                })
                .when(helpArticleMapper)
                .insert(any(HelpArticle.class));

        HelpArticleCreateRequest request = new HelpArticleCreateRequest();
        request.setArticleCode("wallet.withdrawal");
        request.setTitle("Withdrawal help");
        request.setContent("Help body");

        HelpArticleResponse response = service.create(request);

        ArgumentCaptor<HelpArticle> captor = ArgumentCaptor.forClass(HelpArticle.class);
        verify(helpArticleMapper).insert(captor.capture());
        assertThat(captor.getValue().getArticleCode()).isEqualTo("wallet.withdrawal");
        assertThat(captor.getValue().getSortOrder()).isZero();
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
        assertThat(captor.getValue().getIsDeleted()).isZero();
        assertThat(response.getId()).isEqualTo(31L);
    }

    @Test
    void updateChangesOnlyProvidedFields() {
        HelpArticle article = article("compute.activation", "Activation", "old", 10, 1);
        article.setId(32L);
        when(helpArticleMapper.selectById(32L)).thenReturn(article);

        HelpArticleUpdateRequest request = new HelpArticleUpdateRequest();
        request.setSortOrder(5);
        request.setContent("new");

        HelpArticleResponse response = service.update(32L, request);

        ArgumentCaptor<HelpArticle> captor = ArgumentCaptor.forClass(HelpArticle.class);
        verify(helpArticleMapper).updateById(captor.capture());
        assertThat(captor.getValue().getArticleCode()).isEqualTo("compute.activation");
        assertThat(captor.getValue().getTitle()).isEqualTo("Activation");
        assertThat(captor.getValue().getContent()).isEqualTo("new");
        assertThat(captor.getValue().getSortOrder()).isEqualTo(5);
        assertThat(response.getSortOrder()).isEqualTo(5);
    }

    @Test
    void listMapsRows() {
        when(helpArticleMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(article("wallet.withdrawal", "Withdrawal", "body", 10, 1)));

        List<HelpArticleResponse> responses = service.list("wallet", 1, 20);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getArticleCode()).isEqualTo("wallet.withdrawal");
    }

    @Test
    void rejectsInvalidArticleCode() {
        assertThatThrownBy(() -> service.getActiveByCode("bad code"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Invalid articleCode");
    }

    private HelpArticle article(String code, String title, String content, int sortOrder, int status) {
        HelpArticle article = new HelpArticle();
        article.setId(1L);
        article.setArticleCode(code);
        article.setTitle(title);
        article.setContent(content);
        article.setSortOrder(sortOrder);
        article.setStatus(status);
        article.setIsDeleted(0);
        article.setCreatedAt(LocalDateTime.now().minusDays(1));
        article.setUpdatedAt(LocalDateTime.now());
        return article;
    }
}
