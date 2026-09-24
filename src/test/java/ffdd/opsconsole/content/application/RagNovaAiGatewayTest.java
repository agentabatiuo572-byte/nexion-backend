package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.content.domain.SupportFaqView;
import ffdd.opsconsole.content.domain.SupportKnowledgeRepository;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RagNovaAiGatewayTest {
    private static final String MODEL = "gemma4-e4b-ctx32k:latest";
    private static final String COLLECTION = "customer_support_knowledge_prd_v2_20260814";
    private static final String RAG_SESSION_ID = "nova-v1-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void defaultOutputBudgetLeavesRoomForGemmaReasoningAndAnswer() {
        assertThat(new NovaAiProperties().getMaxOutputTokens()).isEqualTo(1_024);
    }

    @Test
    void answersDeviceEarningsNavigationFromTheCurrentPublishedHelpCenterFaq() {
        SupportKnowledgeRepository knowledge = mock(SupportKnowledgeRepository.class);
        String answer = "进入「我的 - 我的设备」查看设备、槽位和运行状态；进入收益相关页面查看算力收益与结算记录。设备状态和收益数据均以服务端返回为准。";
        when(knowledge.listFaqs()).thenReturn(List.of(deviceEarningsFaq("PUBLISHED", "Help Center", "zh-CN", answer)));

        String actual = new RagNovaAiGateway(properties(), objectMapper, knowledge).chat(new NovaAiGateway.ChatRequest(
                MODEL, "zh", RAG_SESSION_ID,
                List.of(new NovaAiGateway.Message("user", "如何查看我的设备收益？")), 1_024));

        assertThat(actual).isEqualTo(answer);

        assertThat(new RagNovaAiGateway(properties(), objectMapper, knowledge).chat(new NovaAiGateway.ChatRequest(
                MODEL, "zh", RAG_SESSION_ID,
                List.of(new NovaAiGateway.Message("user", "设备和算力收益在哪里查看？")), 1_024)))
                .isEqualTo(answer);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "如何查看收益？", "我在哪里查看收益？", "我的收益在哪看？", "怎么查询收益记录？"
    })
    void answersGeneralEarningsNavigationWithTheCurrentAppRoutes(String question) {
        String answer = new RagNovaAiGateway(properties(), objectMapper).chat(new NovaAiGateway.ChatRequest(
                MODEL, "zh", RAG_SESSION_ID,
                List.of(new NovaAiGateway.Message("user", question)), 1_024));

        assertThat(answer).contains("「赚取」", "算力收益", "首页「收益流水」", "「查看全部」", "账单", "服务端");
        assertThat(answer).doesNotContain("推理收据", "保证收益", "您的收益为", "已为您查到");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "如何查看收益提现手续费？", "我的收益为什么没到账？", "如何计算收益？", "查看收益是多少？", "查看团队收益税费"
    })
    void doesNotTurnOtherEarningsQuestionsIntoGenericNavigation(String question) throws Exception {
        assertUsesRagInsteadOfFaq(question,
                deviceEarningsFaq("PUBLISHED", "Help Center", "zh-CN", "FAQ answer"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "我的设备收益为什么没有到账？", "如何计算设备收益？", "查看我的设备收益是多少？",
            "如何查看设备收益提现手续费？", "查看设备收益的税费在哪里？", "我的设备收益如何查看但不显示？"
    })
    void doesNotTurnPersonalOrCalculationQuestionsIntoNavigationAnswers(String question) throws Exception {
        assertUsesRagInsteadOfFaq(question, deviceEarningsFaq("PUBLISHED", "Help Center", "zh-CN", "FAQ answer"));
    }

    @Test
    void ignoresDraftOrWrongSurfaceFaqs() throws Exception {
        assertUsesRagInsteadOfFaq("如何查看我的设备收益？",
                deviceEarningsFaq("DRAFT", "Help Center", "zh-CN", "Draft answer"));
        stop();
        assertUsesRagInsteadOfFaq("如何查看我的设备收益？",
                deviceEarningsFaq("PUBLISHED", "Nova", "zh-CN", "Wrong surface answer"));
    }

    @Test
    void ignoresPublishedFaqWithoutTheApprovedRouteAndRecordGuidance() throws Exception {
        assertUsesRagInsteadOfFaq("如何查看我的设备收益？",
                deviceEarningsFaq("PUBLISHED", "Help Center", "zh-CN", "请等待收益到账。"));
    }

    @Test
    void ignoresOtherLanguageFaq() throws Exception {
        assertUsesRagInsteadOfFaq("如何查看我的设备收益？",
                deviceEarningsFaq("PUBLISHED", "Help Center", "en-US", "See my devices and settlement records on server."));
    }

    private void assertUsesRagInsteadOfFaq(String question, SupportFaqView faq) throws Exception {
        SupportKnowledgeRepository knowledge = mock(SupportKnowledgeRepository.class);
        when(knowledge.listFaqs()).thenReturn(List.of(faq));
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            captured.set(objectMapper.readValue(exchange.getRequestBody(), new TypeReference<>() { }));
            respond(exchange, 200, """
                    {"answer":"RAG answer","sources":[{"source_name":"current"}],"need_human":false,
                     "model":"generated-answer"}
                    """);
        });
        server.start();
        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        assertThat(new RagNovaAiGateway(properties, objectMapper, knowledge).chat(new NovaAiGateway.ChatRequest(
                MODEL, "zh", RAG_SESSION_ID, List.of(new NovaAiGateway.Message("user", question)), 128)))
                .isEqualTo("RAG answer");
        assertThat(captured.get()).containsEntry("question", question);
    }

    private SupportFaqView deviceEarningsFaq(String status, String surface, String language, String answer) {
        return new SupportFaqView("FAQ-20260829015717730-084d2c0b", "hardware", "设备和算力收益在哪里查看？", answer,
                status, surface, language, 1, 1, null);
    }

    @Test
    void sendsTheCurrentQuestionLanguageAndServerSessionWithoutClientHistory() throws Exception {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            captured.set(objectMapper.readValue(exchange.getRequestBody(), new TypeReference<>() { }));
            respond(exchange, 200, """
                    {"answer":"Nexion 已更名为 NexGrid。","sources":[],"need_human":false,
                     "model":"generated-answer"}
                    """);
        });
        server.start();

        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        String answer = new RagNovaAiGateway(properties, objectMapper).chat(new NovaAiGateway.ChatRequest(
                MODEL,
                "zh",
                RAG_SESSION_ID,
                List.of(
                        new NovaAiGateway.Message("user", "旧问题"),
                        new NovaAiGateway.Message("assistant", "旧回答"),
                        new NovaAiGateway.Message("user", "NexGrid 和 Nexion 是什么关系？")),
                128));

        assertThat(answer).isEqualTo("Nexion 已更名为 NexGrid。");
        assertThat(captured.get())
                .containsEntry("question", "NexGrid 和 Nexion 是什么关系？")
                .containsEntry("response_language", "zh")
                .containsEntry("session_id", RAG_SESSION_ID);
        assertThat(captured.get()).doesNotContainKeys(
                "messages", "user_id", "collection", "top_k", "min_score",
                "use_llm", "show_citations", "max_output_tokens");
    }

    @Test
    void usesHttp11SoUvicornReceivesThePostBodyInsteadOfAnH2cUpgradeProbe() throws Exception {
        AtomicReference<String> upgradeHeader = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            if (upgradeHeader.get() != null) {
                respond(exchange, 422, """
                        {"detail":[{"type":"missing","loc":["body"],"msg":"Field required","input":null}]}
                        """);
                return;
            }
            respond(exchange, 200, """
                    {"answer":"Nexion was renamed to NexGrid.","sources":[],"need_human":false,
                     "model":"guardrail-current-fact"}
                    """);
        });
        server.start();

        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        String answer = new RagNovaAiGateway(properties, objectMapper).chat(new NovaAiGateway.ChatRequest(
                MODEL,
                "en",
                RAG_SESSION_ID,
                List.of(new NovaAiGateway.Message("user", "What is NexGrid?")),
                1_024));

        assertThat(answer).isEqualTo("Nexion was renamed to NexGrid.");
        assertThat(upgradeHeader.get()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"retrieval-only", "retrieval-fallback"})
    void retrievalRoutesWithoutSourcesFailClosedAtTheHttpGateway(String model) throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            method.set(exchange.getRequestMethod());
            respond(exchange, 200, """
                    {"answer":"This must not be returned without evidence.","sources":[],"need_human":false,
                     "model":"%s"}
                    """.formatted(model));
        });
        server.start();

        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        assertThatThrownBy(() -> new RagNovaAiGateway(properties, objectMapper).chat(new NovaAiGateway.ChatRequest(
                MODEL, "en", RAG_SESSION_ID, List.of(new NovaAiGateway.Message("user", "What is NexGrid?")), 128)))
                .isInstanceOf(BizException.class)
                .hasMessage("NOVA_AI_UNANSWERABLE");
        assertThat(method.get()).isEqualTo("POST");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"retrieval-only", "retrieval-fallback"})
    void retrievalRoutesWithEvidenceSourcesRemainAnswerableAtTheHttpGateway(String model) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> respond(exchange, 200, """
                {"answer":"NexGrid is the current product name.","sources":[{"id":"KB-23"}],"need_human":false,
                 "model":"%s"}
                """.formatted(model)));
        server.start();

        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        assertThat(new RagNovaAiGateway(properties, objectMapper).chat(new NovaAiGateway.ChatRequest(
                MODEL, "en", RAG_SESSION_ID, List.of(new NovaAiGateway.Message("user", "What is NexGrid?")), 128)))
                .isEqualTo("NexGrid is the current product name.");
    }

    @Test
    void availabilityUsesOnlyThePublicHealthStatus() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"ok\"}"));
        server.start();

        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        assertThat(new RagNovaAiGateway(properties, objectMapper).available()).isTrue();
        properties.setRagCollection("wrong_collection");
        assertThat(new RagNovaAiGateway(properties, objectMapper).available()).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"429,NOVA_AI_BUSY", "504,NOVA_AI_TIMEOUT", "409,NOVA_AI_TURN_CONFLICT"})
    void distinguishesRetryableResponsesAndForwardsStableTurnHeader(int status, String code) throws Exception {
        AtomicReference<String> turn = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            turn.set(exchange.getRequestHeaders().getFirst("X-Nova-Turn-Id"));
            respond(exchange, status, "{}");
        });
        server.start();
        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        String id = "8c12eaf3-744d-405e-b2fb-64b3d81267be";
        assertThatThrownBy(() -> new RagNovaAiGateway(properties, objectMapper).chat(
                new NovaAiGateway.ChatRequest(MODEL, "zh", RAG_SESSION_ID,
                        List.of(new NovaAiGateway.Message("user", "question")), 128, id)))
                .isInstanceOf(BizException.class).hasMessage(code);
        assertThat(turn.get()).isEqualTo(id);
    }

    @Test
    void acceptsTheBracketedIpv6LoopbackUsedByTheControlledRuntime() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("::1"), 0), 0);
        server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"ok\"}"));
        server.start();

        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("http://[::1]:" + server.getAddress().getPort());

        assertThat(new RagNovaAiGateway(properties, objectMapper).available()).isTrue();
    }

    @Test
    void controlledStartupScriptUsesTheSameIpv6LoopbackEndpoint() throws Exception {
        String script = Files.readString(Path.of("scripts", "start_ops_console_monolith.ps1"));

        assertThat(script)
                .contains("NEXION_NOVA_AI_RAG_BASE_URL=http://[::1]:8010")
                .doesNotContain("NEXION_NOVA_AI_RAG_BASE_URL=http://127.0.0.1:8010");
    }

    @Test
    void rejectsAChatResponseThatExposesAnUnknownModelIdentifier() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> respond(exchange, 200, """
                {"answer":"wrong corpus","sources":[],"need_human":false,
                 "model":"unexpected-internal-model"}
                """));
        server.start();
        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        RagNovaAiGateway gateway = new RagNovaAiGateway(properties, objectMapper);
        NovaAiGateway.ChatRequest request = new NovaAiGateway.ChatRequest(
                MODEL, "en", RAG_SESSION_ID, List.of(new NovaAiGateway.Message("user", "NexGrid?")), 64);

        assertThatThrownBy(() -> gateway.chat(request))
                .isInstanceOf(BizException.class)
                .hasMessage("NOVA_AI_RESPONSE_INVALID");
    }

    @Test
    void rejectsNonLoopbackRagTargetsBeforeSendingUserContent() {
        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("https://example.com/rag");
        RagNovaAiGateway gateway = new RagNovaAiGateway(properties, objectMapper);
        NovaAiGateway.ChatRequest request = new NovaAiGateway.ChatRequest(
                MODEL, "en", RAG_SESSION_ID, List.of(new NovaAiGateway.Message("user", "must not be sent")), 64);

        assertThatThrownBy(() -> gateway.chat(request))
                .isInstanceOf(BizException.class)
                .hasMessage("NOVA_AI_UNAVAILABLE");
    }

    private NovaAiProperties properties() {
        NovaAiProperties properties = new NovaAiProperties();
        properties.setMode(NovaAiProperties.Mode.OLLAMA_LOCAL);
        properties.setModel(MODEL);
        properties.setRagCollection(COLLECTION);
        properties.setConnectTimeoutMs(1_000);
        properties.setReadTimeoutMs(3_000);
        return properties;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
