package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.SupportFaqView;
import ffdd.opsconsole.content.dto.NovaAiChatRequest;
import ffdd.opsconsole.content.infrastructure.MybatisSupportKnowledgeRepository;
import ffdd.opsconsole.content.mapper.AppNovaConversationMapper;
import ffdd.opsconsole.content.mapper.HelpArticleMapper;
import ffdd.opsconsole.content.mapper.SupportSlaRuleMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppNovaAiPublishedFaqPathTest {
    private static final String CONVERSATION_ID = "6f0b5c55-0ec5-4a31-85eb-1d4531c1e8df";
    private static final String AUTH_SESSION_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String EN_ANSWER = "Open Me → My Devices to check your devices, slots, and operating status. "
            + "Open the Earn tab for computing earnings and device task history. "
            + "To review entries that have been credited, go to Home → Earnings → See all to open Bill history. "
            + "Device status and earnings are based on server data.";
    private static final String VI_ANSWER = "Mở Tôi → Thiết bị của tôi để xem thiết bị, khe và trạng thái hoạt động. "
            + "Mở thẻ Sinh lời để xem thu nhập điện toán và lịch sử tác vụ của thiết bị. "
            + "Để xem các khoản đã được ghi có, vào Trang chủ → Thu nhập → Xem tất cả để mở Sao kê. "
            + "Trạng thái thiết bị và thu nhập căn cứ vào dữ liệu máy chủ trả về.";

    @Test
    void appChatReturnsThePublishedEnglishAndVietnameseFaqsWithoutCallingRag() {
        HelpArticleMapper articles = mock(HelpArticleMapper.class);
        when(articles.listFaqs()).thenReturn(List.of(
                faq("FAQ-20260924230035730-107a4716", "en-US",
                        "Where can I check my device and computing earnings?", EN_ANSWER),
                faq("FAQ-20260924230217505-4f34a494", "vi-VN",
                        "Tôi xem thu nhập từ thiết bị và năng lực điện toán ở đâu?", VI_ANSWER)));
        MybatisSupportKnowledgeRepository knowledge = new MybatisSupportKnowledgeRepository(
                articles, mock(SupportSlaRuleMapper.class));
        NovaAiProperties properties = new NovaAiProperties();
        properties.setMode(NovaAiProperties.Mode.OLLAMA_LOCAL);
        properties.setRagBaseUrl("http://127.0.0.1:1");
        AppNovaConversationMapper conversations = mock(AppNovaConversationMapper.class);
        when(conversations.insertTurn(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        AppNovaAiService service = new AppNovaAiService(
                new RagNovaAiGateway(properties, new ObjectMapper(), knowledge), properties,
                conversations, mock(PlatformConfigFacade.class));

        assertThat(chat(service, "en", "How can I check my device earnings?",
                "8c12eaf3-744d-405e-b2fb-64b3d81267be")).isEqualTo(EN_ANSWER);
        assertThat(chat(service, "en", "Where can I check my device and computing earnings?",
                "b150350c-fdbf-4c7c-a663-477dd9afe098")).isEqualTo(EN_ANSWER);
        assertThat(chat(service, "vi", "Tôi xem thu nhập từ thiết bị ở đâu?",
                "6402edfb-8b57-41e8-a897-d8809312515c")).isEqualTo(VI_ANSWER);
        verify(articles, org.mockito.Mockito.times(3)).listFaqs();
    }

    private String chat(AppNovaAiService service, String language, String question, String turnId) {
        return service.chat(42L, AUTH_SESSION_ID,
                new NovaAiChatRequest(question, language, CONVERSATION_ID, turnId, List.of())).reply();
    }

    private SupportFaqView faq(String id, String language, String question, String answer) {
        return new SupportFaqView(id, "hardware", question, answer,
                "PUBLISHED", "Help Center", language, 50, 2, null);
    }
}
