package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BscDepositProofTest {
    private static final String TX = "0x" + "a".repeat(64);
    private static final String TO = "0x" + "b".repeat(40);
    private static final String HASH = "0x" + "c".repeat(64);
    private static final String TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private String head = "0x72";
    private String received = "1000000000000000000";
    private String balance = "0x0";
    private final AtomicReference<String> userAgent = new AtomicReference<>();
    private final AtomicInteger unavailableResponses = new AtomicInteger();

    @AfterEach void close() { if (server != null) server.stop(0); }

    @Test
    void creditsOnlyCanonicalReceiptWithExactTransferAndFifteenConfirmations() throws Exception {
        BscDepositProof proof = local();
        assertThat(proof.verify(TX, TO, new BigDecimal("1.000000"), 100))
                .contains(new BscDepositProof.Proof(2, 100, HASH, 15));
        assertThat(proof.scan(100, 100, java.util.List.of(TO))).singleElement()
                .extracting(BscDepositProof.Observation::address).isEqualTo(TO);
        assertThat(proof.scan(100, 100, java.util.List.of(TO))).singleElement()
                .extracting(BscDepositProof.Observation::amount).isEqualTo(BigDecimal.ONE);
        assertThat(proof.zeroUsdtBalance(TO)).isTrue();
        assertThat(userAgent).hasValue("NexGrid-Cregis-ReadOnly/1.0");
        balance = "0x1";
        assertThat(proof.zeroUsdtBalance(TO)).isFalse();
        balance = "0x0";

        received = "2000000000000000000";
        assertThatThrownBy(() -> proof.verify(TX, TO, BigDecimal.ONE, 100))
                .hasMessage("CREGIS_BSC_PROOF_INVALID");
        received = "1000000000000000000";
        head = "0x71";
        assertThat(proof.verify(TX, TO, BigDecimal.ONE, 100)).isEmpty();
    }

    @Test
    void transientRpcFailureRetriesOnceThenFailsClosed() throws Exception {
        BscDepositProof proof = local();
        unavailableResponses.set(1);
        assertThat(proof.head().number()).isEqualTo(114);
        unavailableResponses.set(2);
        assertThatThrownBy(proof::head).hasMessage("CREGIS_BSC_PROOF_UNAVAILABLE");
    }

    private BscDepositProof local() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (unavailableResponses.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            JsonNode request = json.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            String result = switch (method) {
                case "eth_chainId" -> "\"0x38\"";
                case "eth_blockNumber" -> "\"" + head + "\"";
                case "eth_getBlockByNumber" -> "{\"hash\":\"" + HASH + "\"}";
                case "eth_call" -> request.path("params").get(0).path("data").asText()
                        .startsWith("0x70a08231") ? "\"" + balance + "\"" : "\"0x12\"";
                case "eth_getTransactionReceipt" -> "{\"status\":\"0x1\",\"transactionHash\":\"" + TX
                        + "\",\"blockNumber\":\"0x64\",\"blockHash\":\"" + HASH + "\",\"logs\":[" + log() + "]}";
                case "eth_getLogs" -> "[" + log() + "]";
                default -> "null";
            };
            byte[] bytes = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        CregisProperties properties = new CregisProperties();
        properties.setBscRpcUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setDepositConfirmations(15);
        return new BscDepositProof(properties, json, HttpClient.newHttpClient(), true);
    }

    private String log() {
        String value = new BigInteger(received).toString(16);
        return "{\"address\":\"" + CregisConstants.USDT_BEP20_TOKEN_ID
                + "\",\"topics\":[\"" + TOPIC + "\",\"0x" + "0".repeat(64)
                + "\",\"0x" + "0".repeat(24) + TO.substring(2) + "\"],\"data\":\"0x"
                + "0".repeat(64 - value.length()) + value
                + "\",\"logIndex\":\"0x2\",\"blockNumber\":\"0x64\",\"blockHash\":\"" + HASH
                + "\",\"transactionHash\":\"" + TX + "\",\"removed\":false}";
    }
}
