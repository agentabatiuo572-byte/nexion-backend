package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.content.domain.SupportFaqView;
import ffdd.opsconsole.content.domain.SupportKnowledgeRepository;
import ffdd.opsconsole.device.domain.DeviceCatalogRepository;
import ffdd.opsconsole.device.domain.DeviceSkuView;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Local Nova adapter backed by the authoritative Qdrant + Gemma RAG service. */
@Component
public class RagNovaAiGateway implements NovaAiGateway {
    private static final Pattern DEVICE_EARNINGS_NAVIGATION = Pattern.compile(
            "^(?:请问)?(?:如何|怎么|怎样|在哪(?:里)?|从哪(?:里)?)?(?:查看|查询|看|查)(?:我(?:的)?)?(?:设备|算力|设备和算力)收益(?:记录|明细)?(?:呢|啊|呀)?$"
                    + "|^(?:请问)?(?:我(?:的)?)?(?:设备|算力|设备和算力)收益(?:记录|明细)?(?:在(?:哪里|哪)|怎么|如何)?(?:查看|查询|看|查)(?:呢|啊|呀)?$");
    private static final Pattern EN_DEVICE_EARNINGS_NAVIGATION = Pattern.compile(
            "^(?:how (?:can|do) i (?:check|view|see) my device earnings"
                    + "|where can i (?:check|view|see) my device(?: and computing)? earnings)$");
    private static final Pattern VI_DEVICE_EARNINGS_NAVIGATION = Pattern.compile(
            "^tôi xem thu nhập từ thiết bị(?: và năng lực điện toán)? ở đâu$");
    private static final Pattern GENERAL_EARNINGS_NAVIGATION = Pattern.compile(
            "^(?:请问)?(?:我(?:的)?)?(?:如何|怎么|怎样|在哪(?:里)?|从哪(?:里)?)?(?:查看|查询|看|查)(?:我(?:的)?)?收益(?:记录|明细)?(?:呢|啊|呀)?$"
                    + "|^(?:请问)?(?:我(?:的)?)?收益(?:记录|明细)?(?:在(?:哪里|哪)|从(?:哪里|哪)|怎么|如何)?(?:查看|查询|看|查)(?:呢|啊|呀)?$");
    private final NovaAiProperties properties;
    private final ObjectMapper objectMapper;
    private final SupportKnowledgeRepository knowledgeRepository;
    private final DeviceCatalogRepository catalogRepository;

    @Autowired
    public RagNovaAiGateway(NovaAiProperties properties, ObjectMapper objectMapper,
                            SupportKnowledgeRepository knowledgeRepository,
                            DeviceCatalogRepository catalogRepository) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.knowledgeRepository = knowledgeRepository;
        this.catalogRepository = catalogRepository;
    }

    public RagNovaAiGateway(NovaAiProperties properties, ObjectMapper objectMapper,
                            SupportKnowledgeRepository knowledgeRepository) {
        this(properties, objectMapper, knowledgeRepository, null);
    }

    public RagNovaAiGateway(NovaAiProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, null, null);
    }

    @Override
    public String chat(ChatRequest request) {
        try {
            URI target = baseUri();
            int currentIndex = request.messages().size() - 1;
            if (currentIndex < 0 || !"user".equals(request.messages().get(currentIndex).role())) {
                throw invalidResponse();
            }
            Message current = request.messages().get(currentIndex);
            String brandAnswer = currentBrandAnswer(current.content(), request.language());
            if (brandAnswer != null) return brandAnswer;
            String cloudShareAnswer = currentCloudShareAnnualAnswer(current.content(), request.language());
            if (cloudShareAnswer != null) return cloudShareAnswer;
            String publishedFaqAnswer = publishedDeviceEarningsAnswer(current.content(), request.language());
            if (publishedFaqAnswer != null) return publishedFaqAnswer;
            String earningsNavigationAnswer = generalEarningsNavigationAnswer(current.content(), request.language());
            if (earningsNavigationAnswer != null) return earningsNavigationAnswer;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("question", novaQuestion(current.content(), request.language()));
            body.put("response_language", request.language());
            body.put("session_id", request.sessionId());

            HttpRequest.Builder builder = HttpRequest.newBuilder(target.resolve("/chat"))
                    .timeout(Duration.ofMillis(bounded(properties.getReadTimeoutMs(), 1_000, 300_000)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            if (request.turnId() != null) builder.header("X-Nova-Turn-Id", request.turnId());
            if (request.queueScope() != null) builder.header("X-Nova-Queue-Scope", request.queueScope());
            HttpRequest httpRequest = builder.build();
            HttpResponse<String> response = httpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) throw new BizException(429, "NOVA_AI_BUSY");
            if (response.statusCode() == 409) throw new BizException(409, "NOVA_AI_TURN_CONFLICT");
            if (response.statusCode() == 504 || response.statusCode() == 408) {
                throw new BizException(504, "NOVA_AI_TIMEOUT");
            }
            if (response.statusCode() != 200 || response.body() == null || response.body().length() > 128_000) {
                throw unavailable();
            }
            JsonNode root = objectMapper.readTree(response.body());
            String answer = root.path("answer").asText("").trim();
            String model = root.path("model").asText("");
            // No retrieved evidence on a retrieval-only/fallback route is a server outcome,
            // not authority inferred from generated answer text.
            if (("retrieval-only".equals(model) || "retrieval-fallback".equals(model))
                    && root.path("sources").isArray() && root.path("sources").isEmpty()) {
                throw new BizException(503, "NOVA_AI_UNANSWERABLE");
            }
            if (!isAcceptedPublicRoute(model) || answer.isBlank()
                    || answer.length() > bounded(properties.getMaxOutputChars(), 256, 16_000)) {
                throw invalidResponse();
            }
            return answer;
        } catch (BizException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (HttpTimeoutException ex) {
            throw new BizException(504, "NOVA_AI_TIMEOUT");
        } catch (IOException | RuntimeException ex) {
            throw unavailable();
        }
    }

    @Override
    public boolean available() {
        if (properties.getMode() != NovaAiProperties.Mode.OLLAMA_LOCAL) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri().resolve("/health"))
                    .timeout(Duration.ofMillis(bounded(properties.getConnectTimeoutMs(), 250, 10_000)))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().length() > 128_000) return false;
            JsonNode root = objectMapper.readTree(response.body());
            return "ok".equals(root.path("status").asText());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder()
                // Uvicorn serves this loopback API over HTTP/1.1. Java's clear-text
                // HTTP/2 preference sends an h2c upgrade probe whose POST body is
                // discarded by uvicorn, producing a misleading 422 "body missing".
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(bounded(properties.getConnectTimeoutMs(), 100, 10_000)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private URI baseUri() {
        return localBaseUri(properties.getRagBaseUrl());
    }

    private URI localBaseUri(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String rawHost = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String host = rawHost.startsWith("[") && rawHost.endsWith("]")
                    ? rawHost.substring(1, rawHost.length() - 1)
                    : rawHost;
            boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host)
                    || "0:0:0:0:0:0:0:1".equals(host);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || !loopback || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (!address.isLoopbackAddress()) throw new IllegalArgumentException();
            }
            String normalized = uri.toString().replaceAll("/+$", "") + "/";
            return URI.create(normalized);
        } catch (Exception ex) {
            throw new IllegalStateException("NOVA_AI_LOCAL_RAG_BASE_URL_INVALID");
        }
    }

    private BizException unavailable() {
        return new BizException(503, "NOVA_AI_UNAVAILABLE");
    }

    private BizException invalidResponse() {
        return new BizException(502, "NOVA_AI_RESPONSE_INVALID");
    }

    private String currentBrandAnswer(String question, String language) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (text.length() > 160) return null;
        String compact = compactQuestion(text);
        if (compact.contains("nexgridbox") || compact.contains("uvelbox")
                || text.matches(".*\\b(legal|company|corporate|entity)\\b.*")
                || List.of("法律主体", "法定名称", "pháp nhân", "pháp lý").stream().anyMatch(text::contains)) return null;
        boolean legacyIdentity = compact.matches("(whois|whatis)(nexgrid|nexion)")
                || compact.matches("(nexgrid|nexion)(是谁|是什么|làai|làgì)")
                || compact.matches("什么是(nexgrid|nexion)");
        boolean currentIdentity = List.of("whatisuvel", "whoisuvel", "uvel是什么", "什么是uvel",
                "uvellàgì", "uvellàai").contains(compact);
        boolean englishBrandIntent = compact.matches(
                "(?:is|whatis|whatisthe)?(?:uvel|nexgrid|nexion)(?:the)?(?:current|new)?(?:app)?brand(?:name)?"
                + "|whatbrandis(?:uvel|nexgrid|nexion)|whatis(?:uvel|nexgrid|nexion)callednow")
                || List.of("whatisthecurrentbrand", "whatiscurrentbrand", "whatisthecurrentappbrand",
                        "whatistheappcallednow", "whatistheappname").contains(compact);
        boolean chineseBrandIntent = compact.matches(
                "(?:uvel|nexgrid|nexion)(?:现在|当前|目前)?(?:叫什么|叫什么名字|是什么名字|是什么品牌|是什么平台|是什么app)"
                + "|(?:uvel|nexgrid|nexion)(?:还是|是)(?:当前|现在|目前)(?:app)?品牌吗?");
        boolean vietnameseBrandIntent = compact.matches(
                "(?:uvel|nexgrid|nexion)(?:bâygiờ|giờ|hiệntại)?(?:gọilàgì|têngì|tênhiệntạilàgì)"
                + "|(?:uvel|nexgrid|nexion)cóphảitênmớicủa(?:uvel|nexgrid|nexion)không"
                + "|(?:uvel|nexgrid|nexion)cóphảithươnghiệu(?:app)?(?:hiệntại)?không");
        String legacyName = legacyIdentity ? (compact.contains("nexgrid") ? "NexGrid" : "Nexion") : null;
        boolean namesBrand = text.contains("uvel") || text.contains("nexgrid") || text.contains("nexion");
        boolean asksBrand = legacyIdentity || currentIdentity || englishBrandIntent
                || chineseBrandIntent || vietnameseBrandIntent
                || List.of("uvel", "nexgrid", "nexion").contains(compact)
                || text.contains("什么关系") || text.contains("是同一个")
                || text.contains("显示nexgrid") || text.contains("看到nexgrid")
                || text.contains("same app") || text.contains("relationship")
                || text.contains("see nexgrid") || text.contains("shows nexgrid")
                || text.contains("quan hệ") || text.contains("thấy nexgrid");
        if (!asksBrand || (!namesBrand && !text.contains("app"))) return null;
        if (legacyName != null) return switch (language == null ? "" : language.toLowerCase(Locale.ROOT)) {
            case "zh" -> "当前 App 品牌是 UVEL。" + legacyName + " 是旧资料中的历史检索名称，不是当前品牌。如果您在 App 中看到 "
                    + legacyName + "，请发送该页面已遮住个人信息的截图，我们会核查具体位置。";
            case "en" -> "The current App brand is UVEL. " + legacyName
                    + " is a historical search name in older materials, not the current App brand. If you see "
                    + legacyName + " in the App, please send a screenshot of that page with personal information hidden so we can check where it appears.";
            case "vi" -> "Thương hiệu App hiện tại là UVEL. " + legacyName
                    + " là tên tra cứu lịch sử trong tài liệu cũ, không phải thương hiệu App hiện tại. Nếu bạn thấy "
                    + legacyName + " trong App, vui lòng gửi ảnh chụp màn hình trang đó đã che thông tin cá nhân để chúng tôi kiểm tra vị trí hiển thị.";
            default -> null;
        };
        return switch (language == null ? "" : language.toLowerCase(Locale.ROOT)) {
            case "zh" -> "当前 App 品牌是 UVEL。如果您在 App 中看到 NexGrid，请提供该页面的截图，我们会核查具体位置。发送前请遮住个人信息。";
            case "en" -> "The current App brand is UVEL. If you see NexGrid in the App, please send a screenshot of that page so we can check where it appears. Please hide personal information before sending it.";
            case "vi" -> "Thương hiệu App hiện tại là UVEL. Nếu bạn thấy NexGrid trong App, vui lòng gửi ảnh chụp màn hình trang đó để chúng tôi kiểm tra vị trí hiển thị. Hãy che thông tin cá nhân trước khi gửi.";
            default -> null;
        };
    }

    private String currentCloudShareAnnualAnswer(String question, String language) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (text.length() > 240 || !text.matches("(?s).*\\bcloud[\\s-]*share\\b.*")
                && !text.contains("云分享") && !text.contains("云算力份额")) return null;
        if (text.matches("(?s).*\\b(invite|invitation|referral|refer|friends?)\\b.*")
                || text.contains("邀请") || text.contains("推荐") || text.contains("朋友") || text.contains("好友")
                || text.contains("mời") || text.contains("giới thiệu") || text.contains("bạn bè")) return null;
        if (text.matches("(?s).*\\b(fee|fees|price|pricing|subscription|renew|renewal|cost|charge)\\b.*")
                || text.contains("手续费") || text.contains("管理费") || text.contains("订阅费")
                || text.contains("续费") || text.contains("价格") || text.contains("phí")
                || text.contains("giá") || text.contains("gia hạn")) return null;
        if (!(text.matches("(?s).*\\bannual(?:ized)?\\s+(return|yield|rate|profit|income|percentage)\\b.*")
                || text.contains("apy") || text.contains("年化")
                || text.contains("年收益率") || text.contains("lợi nhuận năm")
                || text.contains("lợi suất năm") || text.contains("lãi suất năm"))) return null;

        String range = null;
        if (catalogRepository != null) {
            try {
                DeviceSkuView sku = catalogRepository.findSku("cloud-share").orElse(null);
                if (sku != null && "SHARE".equalsIgnoreCase(sku.productType())
                        && "on".equalsIgnoreCase(sku.status()) && !Boolean.TRUE.equals(sku.publishBlocked())
                        && validAnnualRange(sku.shareYieldMin(), sku.shareYieldMax())) {
                    range = sku.shareYieldMin().stripTrailingZeros().toPlainString() + "%–"
                            + sku.shareYieldMax().stripTrailingZeros().toPlainString() + "%";
                }
            } catch (RuntimeException ignored) {
                // Missing or unavailable live catalog data must not become a stale numerical claim.
            }
        }
        String current = range;
        return switch (language == null ? "" : language.toLowerCase(Locale.ROOT)) {
            case "zh" -> current == null
                    ? "我暂时无法核实 Cloud Share 当前参考年化区间，请以 App“商城 → Cloud Share”商品页为准；参考区间不是固定或保证收益。"
                    : "Cloud Share 当前商品配置的参考年化区间为 " + current
                    + "。请以 App“商城 → Cloud Share”商品页为准；这不是固定或保证收益。";
            case "en" -> current == null
                    ? "I cannot confirm Cloud Share's current reference annual range right now. Please check Store → Cloud Share in the App; the reference range is not a fixed or guaranteed return."
                    : "The current Cloud Share product configuration shows a reference annual range of " + current
                    + ". Please check Store → Cloud Share in the App; this is not a fixed or guaranteed return.";
            case "vi" -> current == null
                    ? "Tôi chưa thể xác nhận khoảng lợi nhuận năm tham khảo hiện tại của Cloud Share. Hãy kiểm tra Cửa hàng → Cloud Share trong App; khoảng tham khảo không phải lợi nhuận cố định hoặc được bảo đảm."
                    : "Cấu hình sản phẩm Cloud Share hiện cho thấy khoảng lợi nhuận năm tham khảo " + current
                    + ". Hãy kiểm tra Cửa hàng → Cloud Share trong App; đây không phải lợi nhuận cố định hoặc được bảo đảm.";
            default -> null;
        };
    }

    private boolean validAnnualRange(BigDecimal min, BigDecimal max) {
        return min != null && max != null && min.signum() > 0 && max.compareTo(min) >= 0;
    }

    private String publishedDeviceEarningsAnswer(String question, String language) {
        String faqLanguage = switch (language == null ? "" : language.toLowerCase(Locale.ROOT)) {
            case "zh" -> "zh-CN";
            case "en" -> "en-US";
            case "vi" -> "vi-VN";
            default -> null;
        };
        if (knowledgeRepository == null || faqLanguage == null
                || !isDeviceEarningsNavigationQuestion(question, language)) return null;
        java.util.List<SupportFaqView> matches = knowledgeRepository.listFaqs().stream()
                .filter(faq -> "PUBLISHED".equalsIgnoreCase(faq.status()))
                .filter(faq -> "Help Center".equalsIgnoreCase(faq.surface()))
                .filter(faq -> faqLanguage.equalsIgnoreCase(faq.language()))
                .filter(faq -> "hardware".equalsIgnoreCase(faq.category()))
                .filter(faq -> "zh-CN".equals(faqLanguage)
                        ? "FAQ-20260829015717730-084d2c0b".equals(faq.id())
                            && "设备和算力收益在哪里查看".equals(compactQuestion(faq.question()))
                        : isDeviceEarningsNavigationQuestion(faq.question(), language))
                .limit(2).toList();
        if (matches.size() != 1) return null;
        String answer = matches.get(0).answer();
        int maxChars = bounded(properties.getMaxOutputChars(), 256, 16_000);
        return answer == null || answer.isBlank() || answer.length() > maxChars
                || !hasDeviceEarningsRoutes(answer, language)
                ? null : answer.trim();
    }

    private boolean hasDeviceEarningsRoutes(String answer, String language) {
        return switch (language.toLowerCase(Locale.ROOT)) {
            case "zh" -> answer.contains("我的→我的设备") && answer.contains("赚取")
                    && answer.contains("首页→收益→查看全部") && answer.contains("账单流水") && answer.contains("服务端");
            case "en" -> answer.contains("Me → My Devices") && answer.contains("Earn tab")
                    && answer.contains("Home → Earnings → See all") && answer.contains("credited");
            case "vi" -> answer.contains("Tôi → Thiết bị của tôi") && answer.contains("thẻ Sinh lời")
                    && answer.contains("Trang chủ → Thu nhập → Xem tất cả") && answer.contains("Sao kê");
            default -> false;
        };
    }

    private String generalEarningsNavigationAnswer(String question, String language) {
        if (!"zh".equalsIgnoreCase(language)) return null;
        String text = compactQuestion(question);
        if (text.length() > 24 || !GENERAL_EARNINGS_NAVIGATION.matcher(text).matches()) return null;
        return "您可以在 App 底部「赚取」页查看算力收益；要核对已入账记录，可在首页「收益流水」点「查看全部」进入账单。"
                + "金额和记录以服务端返回为准。"
                + "如果您问的是任务、团队或其他类型的收益，请告诉我具体类型。";
    }

    private boolean isDeviceEarningsNavigationQuestion(String question, String language) {
        if ("zh".equalsIgnoreCase(language)) {
            String text = compactQuestion(question);
            return text.length() <= 32 && DEVICE_EARNINGS_NAVIGATION.matcher(text).matches();
        }
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT)
                .replaceAll("[?!.。！？]", "").trim().replaceAll("\\s+", " ");
        if (text.length() > 100) return false;
        return switch (language == null ? "" : language.toLowerCase(Locale.ROOT)) {
            case "en" -> EN_DEVICE_EARNINGS_NAVIGATION.matcher(text).matches();
            case "vi" -> VI_DEVICE_EARNINGS_NAVIGATION.matcher(text).matches();
            default -> false;
        };
    }

    private String compactQuestion(String question) {
        return question == null ? "" : question.replaceAll("[\\s?？。！!，,]", "");
    }

    private String novaQuestion(String question, String language) {
        if (knowledgeRepository == null) return question;
        String normalizedLanguage = language == null ? "" : language.trim();
        String context = knowledgeRepository.listFaqs().stream()
                .filter(faq -> "PUBLISHED".equalsIgnoreCase(faq.status()))
                .filter(faq -> "Nova".equalsIgnoreCase(faq.surface()))
                .filter(faq -> normalizedLanguage.isBlank() || normalizedLanguage.equalsIgnoreCase(faq.language()))
                .sorted(java.util.Comparator.comparing(SupportFaqView::sortOrder).thenComparing(SupportFaqView::id))
                .limit(20)
                .map(faq -> "Q: " + boundedText(faq.question(), 500) + "\nA: " + boundedText(faq.answer(), 2_000))
                .collect(java.util.stream.Collectors.joining("\n\n"));
        if (context.isBlank()) return question;
        return "Published Nova knowledge (use only when relevant):\n" + context + "\n\nUser question:\n" + question;
    }

    private String boundedText(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private boolean isAcceptedPublicRoute(String value) {
        return "generated-answer".equals(value)
                || "retrieval-only".equals(value)
                || "retrieval-fallback".equals(value)
                || (value != null && value.startsWith("guardrail"));
    }

    private int bounded(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
