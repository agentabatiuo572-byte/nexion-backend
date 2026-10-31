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
    private CregisProperties properties;
    private boolean logsRpcError;
    private final AtomicReference<String> scanTo = new AtomicReference<>();
    private CregisNetwork network=CregisNetwork.BEP20;
    private String rpcChainId="0x38";
    private String tokenOverride;
    private String head = "0x72";
    private String received = "1000000000000000000";
    private String balance = "0x0";
    private String decimals = "0x" + "0".repeat(62) + "12";
    private String canonicalHash = HASH;
    private String blockTimestamp="0x6553f100";
    private String logTx = TX;
    private final AtomicReference<String> userAgent = new AtomicReference<>();
    private final AtomicInteger unavailableResponses = new AtomicInteger();
    private final java.util.List<String> methods = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Test void paymentTimeComesFromTheSameCanonicalBlockAndRejectsMissingOrReorgedEvidence() throws Exception {
        var proof=local();
        assertThat(proof.blockTime(CregisNetwork.BEP20,100,HASH)).isEqualTo(java.time.Instant.ofEpochSecond(1700000000));
        canonicalHash="0x"+"d".repeat(64);
        assertThatThrownBy(()->proof.blockTime(CregisNetwork.BEP20,100,HASH)).hasMessage("CREGIS_BSC_PROOF_INVALID");
        canonicalHash=HASH;blockTimestamp="";
        assertThatThrownBy(()->proof.blockTime(CregisNetwork.BEP20,100,HASH)).hasMessage("CREGIS_BSC_PROOF_INVALID");
    }
    @Test void preparationReadsExactBlockBalancesWithoutAnyTransactionMethod() throws Exception {
        var proof = local();
        balance = "0xde0b6b3a7640000";
        var facts = proof.preparationBalance(TO);
        assertThat(facts.usdt()).isEqualTo("1.000000000000000000");
        assertThat(facts.bnb()).isEqualTo("0.000000000000000001");
        assertThat(facts.blockNumber()).isEqualTo(114);
        assertThat(methods).containsOnly("eth_chainId","eth_blockNumber","eth_getBlockByNumber","eth_call","eth_getBalance");
    }

    @AfterEach void close() { if (server != null) server.stop(0); }

    @Test void rejectsDifferentOrMalformedAbiDecimalsBeforeReadingBalancesOrVerifyingTransfers() throws Exception {
        var proof = local();
        for (String invalidDecimals : java.util.List.of("0x" + "0".repeat(62) + "06", "0x" + "1".repeat(65), "0xZZ")) {
            decimals = invalidDecimals;
            methods.clear();
            assertThatThrownBy(() -> proof.preparationBalance(TO)).hasMessage("CREGIS_BSC_PROOF_INVALID");
            assertThat(methods).doesNotContain("eth_getBalance");
            assertThatThrownBy(() -> proof.verify(TX, TO, BigDecimal.ONE, 100)).hasMessage("CREGIS_BSC_PROOF_INVALID");
        }
    }

    @Test
    void creditsOnlyCanonicalReceiptWithExactTransferAndFifteenConfirmations() throws Exception {
        BscDepositProof proof = local();
        assertThat(proof.verify(TX, TO, new BigDecimal("1.000000"), 100))
                .contains(new BscDepositProof.Proof(2, 100, HASH, 15,"0x"+"0".repeat(40)));
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
    void payoutDerivesHeightFromReceiptAndObservesPendingWithoutCrediting() throws Exception {
        BscDepositProof proof = local();
        head = "0x71";
        assertThat(proof.verifyPayout(TX.substring(2),TO,BigDecimal.ONE))
                .contains(new BscDepositProof.Proof(2,100,HASH,14,"0x"+"0".repeat(40)));
        assertThat(proof.verify(TX,TO,BigDecimal.ONE,100)).isEmpty();
        canonicalHash = "0x" + "d".repeat(64);
        assertThatThrownBy(() -> proof.verifyPayout(TX,TO,BigDecimal.ONE))
                .hasMessage("CREGIS_BSC_PROOF_INVALID");
        canonicalHash = HASH;
        logTx = "0x" + "e".repeat(64);
        assertThatThrownBy(() -> proof.verifyPayout(TX,TO,BigDecimal.ONE))
                .hasMessage("CREGIS_BSC_PROOF_INVALID");
    }

    @Test
    void defaultAndConfiguredScanUseSameInclusiveRpcRangeLimit() throws Exception {
        var proof = local();
        assertThat(proof.scan(100,599,java.util.List.of(TO))).hasSize(1);
        assertThatThrownBy(() -> proof.scan(100,600,java.util.List.of(TO))).hasMessage("CREGIS_BSC_PROOF_INVALID");
        properties.setChainScanBlocks(20000);
        assertThat(proof.scan(100,20099,java.util.List.of(TO))).hasSize(1);
        assertThat(scanTo).hasValue("0x4e83");
        assertThatThrownBy(() -> proof.scan(100,20100,java.util.List.of(TO))).hasMessage("CREGIS_BSC_PROOF_INVALID");
        logsRpcError = true;
        assertThatThrownBy(() -> proof.scan(100,20099,java.util.List.of(TO))).hasMessage("CREGIS_BSC_PROOF_UNAVAILABLE");
        properties.setChainScanBlocks(100);
        assertThatThrownBy(properties::getChainScanBlocks).hasMessage("CREGIS_CHAIN_SCAN_BLOCKS_INVALID");
    }

    @Test
    void transientRpcFailureRetriesOnceThenFailsClosed() throws Exception {
        BscDepositProof proof = local();
        unavailableResponses.set(1);
        assertThat(proof.head().number()).isEqualTo(114);
        unavailableResponses.set(2);
        assertThatThrownBy(proof::head).hasMessage("CREGIS_BSC_PROOF_UNAVAILABLE");
    }

    @Test void ethereumUsesSixDecimalsAndIndependentMainnetIdentity() throws Exception {
        network=CregisNetwork.ERC20;rpcChainId="0x1";decimals="0x6";received="1000001";
        var proof=local();
        assertThat(proof.verify(network,TX,TO,new BigDecimal("1.000001"),100)).isPresent();
        assertThat(proof.scan(network,100,100,java.util.List.of(TO))).singleElement()
            .extracting(BscDepositProof.Observation::amount).isEqualTo(new BigDecimal("1.000001"));
        assertThat(proof.zeroUsdtBalance(network,TO)).isTrue();
        rpcChainId="0x38";
        assertThatThrownBy(()->proof.verify(network,TX,TO,new BigDecimal("1.000001"),100)).hasMessage("CREGIS_BSC_PROOF_INVALID");
        assertThatThrownBy(()->proof.scan(network,100,100,java.util.List.of(TO))).hasMessage("CREGIS_BSC_PROOF_INVALID");
        assertThatThrownBy(()->proof.zeroUsdtBalance(network,TO)).hasMessage("CREGIS_BSC_PROOF_INVALID");
        rpcChainId="0x1";decimals="0x12";
        assertThatThrownBy(()->proof.verify(network,TX,TO,new BigDecimal("1.000001"),100)).hasMessage("CREGIS_BSC_PROOF_INVALID");
        decimals="0x6";tokenOverride=CregisNetwork.BEP20.tokenId();
        assertThatThrownBy(()->proof.verify(network,TX,TO,new BigDecimal("1.000001"),100)).hasMessage("CREGIS_BSC_PROOF_INVALID");
    }
    @Test void ethereumNeverFallsBackToBscEndpoint() throws Exception {
        var proof=local();properties.setEthereumRpcUrl("");methods.clear();
        assertThatThrownBy(()->proof.head(CregisNetwork.ERC20)).hasMessage("CREGIS_BSC_PROOF_INVALID");
        assertThat(methods).isEmpty();
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
            methods.add(method);
            if ("eth_getLogs".equals(method)) scanTo.set(request.path("params").get(0).path("toBlock").asText());
            String result = switch (method) {
                case "eth_chainId" -> "\""+rpcChainId+"\"";
                case "eth_blockNumber" -> "\"" + head + "\"";
                case "eth_getBalance" -> "\"0x1\"";
                case "eth_getBlockByNumber" -> "{\"hash\":\"" + canonicalHash + "\",\"number\":\""
                        + request.path("params").get(0).asText() + "\",\"timestamp\":\"" + blockTimestamp + "\"}";
                case "eth_call" -> request.path("params").get(0).path("data").asText()
                        .startsWith("0x70a08231") ? "\"" + balance + "\"" : "\"" + decimals + "\"";
                case "eth_getTransactionReceipt" -> "{\"status\":\"0x1\",\"transactionHash\":\"" + TX
                        + "\",\"blockNumber\":\"0x64\",\"blockHash\":\"" + HASH + "\",\"logs\":[" + log() + "]}";
                case "eth_getLogs" -> "[" + log() + "]";
                default -> "null";
            };
            byte[] bytes = (logsRpcError && "eth_getLogs".equals(method)
                    ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"fixture range unavailable\"}}"
                    : "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        properties = new CregisProperties();
        properties.setBscRpcUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setEthereumRpcUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setDepositConfirmations(15);
        return new BscDepositProof(properties, json, HttpClient.newHttpClient(), true);
    }

    private String log() {
        String value = new BigInteger(received).toString(16);
        return "{\"address\":\"" + (tokenOverride==null?network.tokenId():tokenOverride)
                + "\",\"topics\":[\"" + TOPIC + "\",\"0x" + "0".repeat(64)
                + "\",\"0x" + "0".repeat(24) + TO.substring(2) + "\"],\"data\":\"0x"
                + "0".repeat(64 - value.length()) + value
                + "\",\"logIndex\":\"0x2\",\"blockNumber\":\"0x64\",\"blockHash\":\"" + HASH
                + "\",\"transactionHash\":\"" + logTx + "\",\"removed\":false}";
    }
}
