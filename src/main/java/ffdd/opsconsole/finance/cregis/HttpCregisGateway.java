package ffdd.opsconsole.finance.cregis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

public final class HttpCregisGateway implements CregisGateway {
    private static final String SUCCESS_CODE = "00000";
    private static final String NONCE_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final byte[] OVERSIZED_RESPONSE = new byte[MAX_RESPONSE_BYTES + 1];
    private static final ScheduledExecutorService RESPONSE_DEADLINES = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "cregis-response-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private static final Pattern EVM_ADDRESS = Pattern.compile("(?i)^0x[0-9a-f]{40}$");
    private static final Pattern BSC_TXID = Pattern.compile("(?i)^(?:0x)?[0-9a-f]{64}$");

    private final CregisProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Clock clock;
    private final Supplier<String> nonceSupplier;
    private final boolean allowInsecureLoopbackForTests;
    private final CregisSigner signer = new CregisSigner();

    public HttpCregisGateway(CregisProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(Math.max(1, properties.getConnectTimeoutMs())))
                        .build(),
                Clock.systemUTC(),
                HttpCregisGateway::secureNonce,
                false);
    }

    HttpCregisGateway(
            CregisProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            Clock clock,
            Supplier<String> nonceSupplier,
            boolean allowInsecureLoopbackForTests) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.clock = clock;
        this.nonceSupplier = nonceSupplier;
        this.allowInsecureLoopbackForTests = allowInsecureLoopbackForTests;
    }

    @Override
    public List<Coin> projectCoins() {
        JsonNode data = postRead("/api/v1/coins", Map.of());
        JsonNode payout = data.get("payout_coins");
        JsonNode address = data.get("address_coins");
        if (payout == null || !payout.isArray() || address == null || !address.isArray()) throw invalidResponse();
        Map<String, MutableCoin> merged = new LinkedHashMap<>();
        mergeCoins(merged, payout, true);
        mergeCoins(merged, address, false);
        List<Coin> result = new ArrayList<>();
        for (MutableCoin coin : merged.values()) {
            result.add(new Coin(coin.currency(), coin.coinName(), coin.chainId(), coin.tokenId(),
                    coin.payoutEnabled, coin.addressEnabled));
        }
        return List.copyOf(result);
    }

    @Override
    public Address createAddress(String chainId, String alias, String callbackUrl, String requestId) {
        requireBsc(chainId);
        requireText(requestId, 128);
        requireOptionalText(alias, 40);
        requireProviderCallback(callbackUrl, "/deposit");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("chain_id", chainId);
        if (StringUtils.hasText(alias)) fields.put("alias", alias.trim());
        fields.put("callback_url", callbackUrl.trim());
        JsonNode data = postWrite("/api/v1/address/create", fields);
        JsonNode addressNode = data.get("address");
        if (addressNode == null || !addressNode.isTextual() || addressNode.textValue().isBlank()
                || !EVM_ADDRESS.matcher(addressNode.textValue()).matches()) {
            throw submissionUnknown();
        }
        String address = addressNode.textValue();
        return new Address(chainId, address.toLowerCase(Locale.ROOT), requestId.trim());
    }

    @Override
    public boolean addressBelongs(String chainId, String address) {
        return addressCheck("/api/v1/address/inner", chainId, address);
    }

    @Override
    public boolean addressLegal(String chainId, String address) {
        return addressCheck("/api/v1/address/legal", chainId, address);
    }

    @Override
    public PayoutSubmission createPayout(PayoutRequest request) {
        PayoutRequest normalized = normalizePayout(request);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("currency", normalized.currency());
        fields.put("address", normalized.address());
        fields.put("amount", normalized.amount().toPlainString());
        fields.put("third_party_id", normalized.thirdPartyId());
        fields.put("callback_url", normalized.callbackUrl());
        if (StringUtils.hasText(normalized.remark())) fields.put("remark", normalized.remark());
        JsonNode data = postWrite("/api/v1/payout", fields);
        JsonNode cidNode = data.get("cid");
        if (cidNode == null || !cidNode.isIntegralNumber() || !cidNode.canConvertToLong()
                || cidNode.longValue() <= 0) {
            throw submissionUnknown();
        }
        return new PayoutSubmission(cidNode.longValue(), normalized.thirdPartyId());
    }

    @Override
    public PayoutOrder queryPayout(PayoutQuery expected) {
        BigDecimal expectedAmount = expected == null ? null : CregisAmount.normalizePositive(expected.amount());
        if (expected == null || expected.cid() <= 0
                || !StringUtils.hasText(expected.thirdPartyId()) || expected.thirdPartyId().trim().length() > 128
                || !StringUtils.hasText(expected.address())
                || !EVM_ADDRESS.matcher(expected.address().trim()).matches()
                || expectedAmount == null) {
            throw requestInvalid();
        }
        long cid = expected.cid();
        JsonNode data = postRead("/api/v1/payout/query", Map.of("cid", cid));
        JsonNode pidNode = data.get("pid");
        String chainId = requiredText(data, "chain_id");
        String tokenId = requiredText(data, "token_id");
        String providerCurrency = requiredText(data, "currency");
        String address = requiredText(data, "address");
        String thirdPartyId = requiredText(data, "third_party_id");
        BigDecimal amount = positiveAmount(data.get("amount"));
        JsonNode statusNode = data.get("status");
        if (!CregisConstants.BSC_CHAIN_ID.equals(chainId)
                || !CregisConstants.USDT_BEP20_TOKEN_ID.equalsIgnoreCase(tokenId)
                || !("USDT-BEP20".equalsIgnoreCase(providerCurrency)
                    || CregisConstants.USDT_BEP20_CURRENCY.equalsIgnoreCase(providerCurrency))
                || !EVM_ADDRESS.matcher(address).matches()
                || pidNode == null || !pidNode.isIntegralNumber() || !pidNode.canConvertToLong()
                || pidNode.longValue() != properties.getProjectId()
                || statusNode == null || !statusNode.isIntegralNumber() || !statusNode.canConvertToInt()) {
            throw invalidResponse();
        }
        if (!thirdPartyId.equals(expected.thirdPartyId().trim())
                || !address.equalsIgnoreCase(expected.address().trim())
                || amount.compareTo(expectedAmount) != 0) {
            throw new CregisGatewayException(
                    CregisGatewayException.Kind.INVALID_RESPONSE, "CREGIS_PAYOUT_SNAPSHOT_MISMATCH");
        }
        String txid = nullableText(data.get("txid"));
        PayoutStatus status = PayoutStatus.fromProviderCode(statusNode.intValue());
        if ((txid != null && !BSC_TXID.matcher(txid).matches())
                || (status == PayoutStatus.SUCCEEDED && txid == null)) {
            throw invalidResponse();
        }
        return new PayoutOrder(cid, CregisConstants.USDT_BEP20_CURRENCY, chainId, tokenId,
                address.toLowerCase(Locale.ROOT), amount, thirdPartyId,
                status, txid);
    }

    private boolean addressCheck(String path, String chainId, String address) {
        requireBsc(chainId);
        if (!StringUtils.hasText(address) || !EVM_ADDRESS.matcher(address.trim()).matches()) throw requestInvalid();
        JsonNode data = postRead(path, Map.of("chain_id", chainId, "address", address.trim()));
        JsonNode result = data.get("result");
        if (result == null || !result.isBoolean()) throw invalidResponse();
        return result.booleanValue();
    }

    private JsonNode postRead(String path, Map<String, ?> functional) {
        return post(path, functional, false);
    }

    private JsonNode postWrite(String path, Map<String, ?> functional) {
        return post(path, functional, true);
    }

    private JsonNode post(String path, Map<String, ?> functional, boolean ambiguousSubmission) {
        URI endpoint = endpoint(path);
        Map<String, Object> requestFields = new LinkedHashMap<>();
        requestFields.put("pid", properties.getProjectId());
        requestFields.putAll(functional);
        String nonce = nonceSupplier.get();
        if (nonce == null || !nonce.matches("[A-Za-z0-9]{6}")) throw configurationInvalid();
        requestFields.put("nonce", nonce);
        requestFields.put("timestamp", clock.millis());
        requestFields.put("sign", signer.sign(properties.getApiKey(), requestFields));
        try {
            String body = objectMapper.writeValueAsString(requestFields);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request,
                    ignored -> new BoundedBodySubscriber(
                            MAX_RESPONSE_BYTES, Math.max(1, properties.getReadTimeoutMs())));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (ambiguousSubmission && (response.statusCode() >= 500
                        || response.statusCode() == 408 || response.statusCode() == 409
                        || response.statusCode() == 429)) {
                    throw submissionUnknown();
                }
                throw unavailableOrRejected(ambiguousSubmission);
            }
            byte[] responseBytes = response.body();
            if (responseBytes == null || responseBytes.length > MAX_RESPONSE_BYTES) {
                throw ambiguousSubmission ? submissionUnknown() : invalidResponse();
            }
            JsonNode root = objectMapper.readTree(new String(responseBytes, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) throw ambiguousSubmission ? submissionUnknown() : invalidResponse();
            JsonNode code = root.get("code");
            if (code == null || !code.isTextual()) throw ambiguousSubmission ? submissionUnknown() : invalidResponse();
            if (!SUCCESS_CODE.equals(code.textValue())) {
                if (ambiguousSubmission && ("E0009".equals(code.textValue()) || "E0018".equals(code.textValue()))) {
                    throw new CregisGatewayException(
                            CregisGatewayException.Kind.SUBMISSION_UNKNOWN,
                            "CREGIS_DUPLICATE_BUSINESS_ID_UNKNOWN");
                }
                throw new CregisGatewayException(CregisGatewayException.Kind.REJECTED, "CREGIS_PROVIDER_REJECTED");
            }
            JsonNode data = root.get("data");
            if (data == null || !data.isObject()) throw ambiguousSubmission ? submissionUnknown() : invalidResponse();
            return data;
        } catch (CregisGatewayException known) {
            throw known;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ambiguousSubmission ? submissionUnknown() : unavailable();
        } catch (IOException | RuntimeException failure) {
            throw ambiguousSubmission ? submissionUnknown() : unavailable();
        }
    }

    private URI endpoint(String path) {
        validateConfiguration();
        try {
            URI base = URI.create(properties.getBaseUrl());
            return URI.create(base.toString().replaceAll("/+$", "") + path);
        } catch (IllegalArgumentException invalid) {
            throw configurationInvalid();
        }
    }

    private void validateConfiguration() {
        if (properties.getMode() != CregisProperties.Mode.PROVIDER
                || properties.getProjectId() <= 0
                || !StringUtils.hasText(properties.getBaseUrl())
                || !StringUtils.hasText(properties.getApiKey())
                || !StringUtils.hasText(properties.getCallbackBaseUrl())
                || !properties.getApiKey().equals(properties.getApiKey().trim())
                || properties.getApiKey().length() < 16
                || properties.getConnectTimeoutMs() <= 0
                || properties.getReadTimeoutMs() <= 0) {
            throw configurationInvalid();
        }
        try {
            URI base = URI.create(properties.getBaseUrl());
            URI callbackBase = URI.create(properties.getCallbackBaseUrl());
            String callbackPath = callbackBase.getPath() == null
                    ? "" : callbackBase.getPath().replaceAll("/+$", "");
            boolean secure = "https".equalsIgnoreCase(base.getScheme());
            boolean testLoopback = allowInsecureLoopbackForTests
                    && "http".equalsIgnoreCase(base.getScheme())
                    && ("127.0.0.1".equals(base.getHost()) || "localhost".equalsIgnoreCase(base.getHost()));
            if ((!secure && !testLoopback) || !StringUtils.hasText(base.getHost())
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
                throw configurationInvalid();
            }
            if (!"https".equalsIgnoreCase(callbackBase.getScheme())
                    || !StringUtils.hasText(callbackBase.getHost())
                    || callbackBase.getUserInfo() != null || callbackBase.getQuery() != null
                    || callbackBase.getFragment() != null
                    || callbackPath.isBlank() || "/".equals(callbackPath)
                    || !callbackBase.getRawPath().equals(callbackBase.getPath())
                    || callbackPath.contains("\\")) {
                throw configurationInvalid();
            }
        } catch (IllegalArgumentException invalid) {
            throw configurationInvalid();
        }
    }

    private void mergeCoins(Map<String, MutableCoin> merged, JsonNode array, boolean payout) {
        for (JsonNode item : array) {
            if (!item.isObject()) throw invalidResponse();
            String coinName = requiredText(item, "coin_name");
            String chainId = requiredText(item, "chain_id");
            String tokenId = requiredText(item, "token_id");
            String currency = chainId + "@" + tokenId;
            MutableCoin existing = merged.get(currency.toLowerCase(Locale.ROOT));
            if (existing == null) {
                existing = new MutableCoin(currency, coinName, chainId, tokenId);
                merged.put(currency.toLowerCase(Locale.ROOT), existing);
            } else if (!existing.coinName().equals(coinName)) {
                throw invalidResponse();
            }
            if (payout) existing.payoutEnabled = true;
            else existing.addressEnabled = true;
        }
    }

    private PayoutRequest normalizePayout(PayoutRequest request) {
        if (request == null || !CregisConstants.USDT_BEP20_CURRENCY.equalsIgnoreCase(request.currency())
                || !StringUtils.hasText(request.address()) || !EVM_ADDRESS.matcher(request.address().trim()).matches()) {
            throw requestInvalid();
        }
        BigDecimal amount = CregisAmount.normalizePositive(request.amount());
        if (amount == null) throw requestInvalid();
        requireText(request.thirdPartyId(), 128);
        requireProviderCallback(request.callbackUrl(), "/payout");
        requireOptionalText(request.remark(), 255);
        return new PayoutRequest(CregisConstants.USDT_BEP20_CURRENCY,
                request.address().trim().toLowerCase(Locale.ROOT), amount.stripTrailingZeros(),
                request.thirdPartyId().trim(), request.callbackUrl().trim(),
                request.remark() == null ? "" : request.remark().trim());
    }

    private BigDecimal positiveAmount(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalidResponse();
        BigDecimal amount = CregisAmount.parsePositive(node.textValue());
        if (amount == null) throw invalidResponse();
        return amount;
    }

    private String requiredText(JsonNode parent, String field) {
        String value = optionalText(parent == null ? null : parent.get(field));
        if (value == null) throw invalidResponse();
        return value;
    }

    private String optionalText(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual() || node.textValue().isBlank()) throw invalidResponse();
        return node.textValue();
    }

    private String nullableText(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw invalidResponse();
        return node.textValue().isBlank() ? null : node.textValue();
    }

    private void requireBsc(String chainId) {
        if (!CregisConstants.BSC_CHAIN_ID.equals(chainId)) throw requestInvalid();
    }

    private void requireHttps(String value) {
        requireText(value, 2048);
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) throw requestInvalid();
        } catch (IllegalArgumentException invalid) {
            throw requestInvalid();
        }
    }

    private void requireProviderCallback(String value, String expectedSuffix) {
        requireHttps(value);
        validateConfiguration();
        try {
            URI allowed = URI.create(properties.getCallbackBaseUrl()).normalize();
            URI candidate = URI.create(value.trim()).normalize();
            String allowedPath = allowed.getPath() == null ? "" : allowed.getPath().replaceAll("/+$", "");
            String candidatePath = candidate.getPath() == null ? "" : candidate.getPath();
            boolean encodedPath = !allowed.getRawPath().equals(allowed.getPath())
                    || !candidate.getRawPath().equals(candidate.getPath())
                    || allowedPath.contains("\\") || candidatePath.contains("\\");
            boolean sameOrigin = "https".equalsIgnoreCase(allowed.getScheme())
                    && allowed.getScheme().equalsIgnoreCase(candidate.getScheme())
                    && allowed.getHost().equalsIgnoreCase(candidate.getHost())
                    && effectivePort(allowed) == effectivePort(candidate);
            boolean pathBound = !allowedPath.isBlank() && !"/".equals(allowedPath)
                    && candidatePath.equals(allowedPath + expectedSuffix)
                    && candidate.getUserInfo() == null && candidate.getQuery() == null
                    && candidate.getFragment() == null;
            if (encodedPath || !sameOrigin || !pathBound) throw requestInvalid();
        } catch (IllegalArgumentException invalid) {
            throw requestInvalid();
        }
    }

    private int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    }

    private void requireText(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.trim().length() > maxLength) throw requestInvalid();
    }

    private void requireOptionalText(String value, int maxLength) {
        if (value != null && value.trim().length() > maxLength) throw requestInvalid();
    }

    private CregisGatewayException unavailableOrRejected(boolean submission) {
        if (submission) {
            return new CregisGatewayException(CregisGatewayException.Kind.REJECTED, "CREGIS_PROVIDER_REJECTED");
        }
        return unavailable();
    }

    private CregisGatewayException configurationInvalid() {
        return new CregisGatewayException(CregisGatewayException.Kind.CONFIGURATION, "CREGIS_CONFIGURATION_INVALID");
    }

    private CregisGatewayException requestInvalid() {
        return new CregisGatewayException(CregisGatewayException.Kind.REJECTED, "CREGIS_REQUEST_INVALID");
    }

    private CregisGatewayException unavailable() {
        return new CregisGatewayException(CregisGatewayException.Kind.UNAVAILABLE, "CREGIS_PROVIDER_UNAVAILABLE");
    }

    private CregisGatewayException invalidResponse() {
        return new CregisGatewayException(CregisGatewayException.Kind.INVALID_RESPONSE, "CREGIS_RESPONSE_INVALID");
    }

    private CregisGatewayException submissionUnknown() {
        return new CregisGatewayException(CregisGatewayException.Kind.SUBMISSION_UNKNOWN, "CREGIS_SUBMISSION_UNKNOWN");
    }

    private static String secureNonce() {
        SecureRandom random = SecureRandomHolder.INSTANCE;
        StringBuilder value = new StringBuilder(6);
        for (int index = 0; index < 6; index++) {
            value.append(NONCE_ALPHABET.charAt(random.nextInt(NONCE_ALPHABET.length())));
        }
        return value.toString();
    }

    private static final class SecureRandomHolder {
        private static final SecureRandom INSTANCE = new SecureRandom();
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final ByteArrayOutputStream output;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ScheduledFuture<?> deadline;
        private volatile Flow.Subscription subscription;
        private int received;

        private BoundedBodySubscriber(int maxBytes, int timeoutMs) {
            this.maxBytes = maxBytes;
            this.output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            this.deadline = RESPONSE_DEADLINES.schedule(this::timeOut, timeoutMs, TimeUnit.MILLISECONDS);
            body.whenComplete((ignored, failure) -> deadline.cancel(false));
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null || body.isDone()) {
                incoming.cancel();
                return;
            }
            subscription = incoming;
            incoming.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                int length = buffer.remaining();
                if (length > maxBytes - received) {
                    Flow.Subscription active = subscription;
                    if (active != null) active.cancel();
                    body.complete(OVERSIZED_RESPONSE);
                    return;
                }
                byte[] chunk = new byte[length];
                buffer.get(chunk);
                output.writeBytes(chunk);
                received += length;
            }
            Flow.Subscription active = subscription;
            if (active != null) active.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            body.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            body.complete(output.toByteArray());
        }

        private void timeOut() {
            Flow.Subscription active = subscription;
            if (active != null) active.cancel();
            body.completeExceptionally(new IOException("CREGIS_RESPONSE_READ_TIMEOUT"));
        }
    }

    private static final class MutableCoin {
        private final String currency;
        private final String coinName;
        private final String chainId;
        private final String tokenId;
        private boolean payoutEnabled;
        private boolean addressEnabled;

        private MutableCoin(String currency, String coinName, String chainId, String tokenId) {
            this.currency = currency;
            this.coinName = coinName;
            this.chainId = chainId;
            this.tokenId = tokenId;
        }

        private String currency() { return currency; }
        private String coinName() { return coinName; }
        private String chainId() { return chainId; }
        private String tokenId() { return tokenId; }
    }
}
