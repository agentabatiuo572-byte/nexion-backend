package ffdd.opsconsole.finance.cregis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Read-only BSC receipt proof. A callback or provider trade never credits by itself. */
@Component
public final class BscDepositProof {
    private static final String TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private final CregisProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Predicate<URI> loopbackAllowed;

    @Autowired
    public BscDepositProof(CregisProperties properties, ObjectMapper json) {
        this(properties, json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), false);
    }

    BscDepositProof(CregisProperties properties, ObjectMapper json, HttpClient http, boolean allowLoopbackForTests) {
        this.properties = properties;
        this.json = json;
        this.http = http;
        this.loopbackAllowed = allowLoopbackForTests
                ? uri -> "http".equalsIgnoreCase(uri.getScheme())
                        && ("localhost".equalsIgnoreCase(uri.getHost())
                            || "127.0.0.1".equals(uri.getHost()))
                : uri -> false;
    }

    public record Head(long number, String hash) { }
    public record Proof(int logIndex, long blockNumber, String blockHash, int confirmations) { }
    public record Observation(String txid, int logIndex, String address, BigDecimal amount, long blockNumber) { }

    /** Narrow finalized Transfer scan of already allocated addresses. */
    public List<Observation> scan(long from, long to, List<String> addresses) {
        if (from < 0 || to < from || to - from >= 500 || addresses == null
                || addresses.isEmpty() || addresses.size() > 60) throw invalid();
        StringBuilder recipients = new StringBuilder();
        for (String address : addresses) {
            if (address == null || !address.matches("(?i)0x[0-9a-f]{40}")) throw invalid();
            if (!recipients.isEmpty()) recipients.append(',');
            recipients.append('"').append("0x000000000000000000000000")
                    .append(address.substring(2).toLowerCase(Locale.ROOT)).append('"');
        }
        String params = "[{\"fromBlock\":\"" + hex(from) + "\",\"toBlock\":\"" + hex(to)
                + "\",\"address\":\"" + CregisConstants.USDT_BEP20_TOKEN_ID
                + "\",\"topics\":[\"" + TRANSFER_TOPIC + "\",null,[" + recipients + "]]}]";
        JsonNode logs = call("eth_getLogs", params);
        if (!logs.isArray()) throw invalid();
        List<Observation> found = new ArrayList<>();
        for (JsonNode log : logs) {
            if (log.path("removed").asBoolean(false)
                    || !CregisConstants.USDT_BEP20_TOKEN_ID.equalsIgnoreCase(log.path("address").asText()))
                throw invalid();
            JsonNode topics = log.path("topics");
            if (!topics.isArray() || topics.size() != 3
                    || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0).asText())) throw invalid();
            String recipient = topics.get(2).asText();
            String data = log.path("data").asText();
            if (!recipient.matches("(?i)0x[0-9a-f]{64}") || !data.matches("(?i)0x[0-9a-f]{64}"))
                throw invalid();
            long index = hexLong(log.path("logIndex").asText());
            long height = hexLong(log.path("blockNumber").asText());
            if (index > Integer.MAX_VALUE || height < from || height > to) throw invalid();
            found.add(new Observation(hash(log.path("transactionHash").asText()), (int) index,
                    "0x" + recipient.substring(26).toLowerCase(Locale.ROOT),
                    new BigDecimal(new BigInteger(data.substring(2), 16), 18).stripTrailingZeros(), height));
        }
        return List.copyOf(found);
    }

    public Head head() {
        if (hexLong(call("eth_chainId", "[]").asText()) != 56) throw invalid();
        long height = hexLong(call("eth_blockNumber", "[]").asText());
        JsonNode block = call("eth_getBlockByNumber", "[\"" + hex(height) + "\",false]");
        if (!block.isObject()) throw invalid();
        return new Head(height, hash(block.path("hash").asText()));
    }

    public String blockHash(long number) {
        if (number < 0) throw invalid();
        JsonNode block = call("eth_getBlockByNumber", "[\"" + hex(number) + "\",false]");
        if (!block.isObject() || hexLong(block.path("number").asText()) != number) throw invalid();
        return hash(block.path("hash").asText());
    }

    public boolean zeroUsdtBalance(String address) {
        if (address == null || !address.matches("(?i)0x[0-9a-f]{40}")) throw invalid();
        String data = "0x70a08231" + "0".repeat(24) + address.substring(2).toLowerCase(Locale.ROOT);
        JsonNode result = call("eth_call", "[{\"to\":\"" + CregisConstants.USDT_BEP20_TOKEN_ID
                + "\",\"data\":\"" + data + "\"},\"latest\"]");
        return hexBigInteger(result.asText()).signum() == 0;
    }

    public Optional<Proof> verify(String txid, String address, BigDecimal amount, long callbackHeight) {
        if (properties.getDepositConfirmations() < 15) throw invalid();
        if (!txid.matches("(?i)0x[0-9a-f]{64}") || !address.matches("(?i)0x[0-9a-f]{40}")
                || amount == null || amount.signum() <= 0 || amount.scale() > 6) throw invalid();
        JsonNode receipt = call("eth_getTransactionReceipt", "[\"" + txid + "\"]");
        if (receipt.isNull()) return Optional.empty();
        if (!receipt.isObject() || !"0x1".equalsIgnoreCase(receipt.path("status").asText())
                || !txid.equalsIgnoreCase(receipt.path("transactionHash").asText())) throw invalid();
        long blockNumber = hexLong(receipt.path("blockNumber").asText());
        String blockHash = hash(receipt.path("blockHash").asText());
        if (blockNumber != callbackHeight) throw invalid();
        Head head = head();
        if (head.number() < blockNumber || head.number() - blockNumber + 1 < properties.getDepositConfirmations())
            return Optional.empty();
        JsonNode block = call("eth_getBlockByNumber", "[\"" + hex(blockNumber) + "\",false]");
        if (!block.isObject() || !blockHash.equalsIgnoreCase(hash(block.path("hash").asText()))) throw invalid();
        JsonNode decimals = call("eth_call", "[{\"to\":\"" + CregisConstants.USDT_BEP20_TOKEN_ID
                + "\",\"data\":\"0x313ce567\"},\"latest\"]");
        if (hexLong(decimals.asText()) != 18) throw invalid();
        JsonNode logs = receipt.path("logs");
        if (!logs.isArray()) throw invalid();
        Integer found = null;
        for (JsonNode log : logs) {
            if (!CregisConstants.USDT_BEP20_TOKEN_ID.equalsIgnoreCase(log.path("address").asText())) continue;
            JsonNode topics = log.path("topics");
            if (!topics.isArray() || topics.size() != 3
                    || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0).asText())) continue;
            String recipient = topics.get(2).asText();
            if (!recipient.matches("(?i)0x[0-9a-f]{64}")
                    || !recipient.substring(26).equalsIgnoreCase(address.substring(2))) continue;
            String raw = log.path("data").asText();
            if (!raw.matches("(?i)0x[0-9a-f]{64}")) throw invalid();
            BigDecimal transferred = new BigDecimal(new BigInteger(raw.substring(2), 16), 18);
            if (transferred.compareTo(amount) != 0) continue;
            if (log.path("removed").asBoolean(false) || !blockHash.equalsIgnoreCase(log.path("blockHash").asText()))
                throw invalid();
            long index = hexLong(log.path("logIndex").asText());
            if (index > Integer.MAX_VALUE || found != null) throw invalid();
            found = (int) index;
        }
        if (found == null) throw invalid();
        return Optional.of(new Proof(found, blockNumber, blockHash,
                Math.toIntExact(Math.min(Integer.MAX_VALUE, head.number() - blockNumber + 1))));
    }

    private JsonNode call(String method, String params) {
        String configured = properties.getBscRpcUrl();
        if (configured == null || configured.isBlank()) throw invalid();
        URI uri;
        try { uri = URI.create(configured); }
        catch (IllegalArgumentException ex) { throw invalid(); }
        boolean testLoopback = loopbackAllowed.test(uri);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || testLoopback) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) throw invalid();
        try {
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method
                    + "\",\"params\":" + params + "}";
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "NexGrid-Cregis-ReadOnly/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] bytes = stream.readNBytes(1_048_577);
                if (response.statusCode() != 200 || bytes.length > 1_048_576) throw invalid();
                JsonNode root = json.readTree(new String(bytes, StandardCharsets.UTF_8));
                if (root == null || root.hasNonNull("error") || root.path("id").asInt(-1) != 1
                        || !root.has("result")) throw invalid();
                return root.get("result");
            }
        } catch (Exception ex) {
            throw new IllegalStateException("CREGIS_BSC_PROOF_UNAVAILABLE");
        }
    }

    private static long hexLong(String value) {
        if (value == null || !value.matches("(?i)0x[0-9a-f]{1,16}")) throw invalid();
        try { return Long.parseUnsignedLong(value.substring(2), 16); }
        catch (NumberFormatException ex) { throw invalid(); }
    }

    private static BigInteger hexBigInteger(String value) {
        if (value == null || !value.matches("(?i)0x[0-9a-f]{1,64}")) throw invalid();
        return new BigInteger(value.substring(2), 16);
    }

    private static String hex(long value) { return "0x" + Long.toHexString(value); }
    private static String hash(String value) {
        if (value == null || !value.matches("(?i)0x[0-9a-f]{64}")) throw invalid();
        return value.toLowerCase(Locale.ROOT);
    }
    private static IllegalStateException invalid() { return new IllegalStateException("CREGIS_BSC_PROOF_INVALID"); }
}
