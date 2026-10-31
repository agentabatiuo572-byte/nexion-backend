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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Read-only BSC receipt proof. A callback or provider trade never credits by itself. */
@Component
public final class BscDepositProof {
    private static final Logger log = LoggerFactory.getLogger(BscDepositProof.class);
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
    public record Proof(int logIndex, long blockNumber, String blockHash, int confirmations,String fromAddress) {
        public Proof(int logIndex,long blockNumber,String blockHash,int confirmations) {
            this(logIndex,blockNumber,blockHash,confirmations,null);
        }
    }
    public record Observation(String txid, int logIndex, String address, BigDecimal amount, long blockNumber) { }
    public record TransferProof(int logIndex,long blockNumber,String blockHash,int confirmations,
                                String fromAddress,String toAddress,BigDecimal amount,boolean senderEoa) { }
    public record ReorgProof(boolean positivelyVerifiedAbsent,TransferProof canonical,
                             long checkedHead,String originalCanonicalHash) { }

    /** Exact log identity; transaction.from is deliberately never used as token ownership. */
    public TransferProof verifyExact(CregisNetwork network,String txid,int logIndex,String address,BigDecimal amount,Long expectedBlock) {
        String normalized=hash(txid);
        if(logIndex<0 || address==null || !address.matches("(?i)0x[0-9a-f]{40}")
                || amount==null || amount.signum()<=0 || amount.stripTrailingZeros().scale()>6
                || properties.getDepositConfirmations()<15) throw invalid();
        JsonNode receipt=call(network,"eth_getTransactionReceipt","[\""+normalized+"\"]");
        return verifyReceipt(network,normalized,logIndex,address,amount,expectedBlock,receipt);
    }

    private TransferProof verifyReceipt(CregisNetwork network,String normalized,int logIndex,String address,BigDecimal amount,
                                         Long expectedBlock,JsonNode receipt) {
        if(receipt.isNull()) return null;
        if(!receipt.isObject() || !"0x1".equalsIgnoreCase(receipt.path("status").asText())
                || !normalized.equalsIgnoreCase(receipt.path("transactionHash").asText())) throw invalid();
        long height=hexLong(receipt.path("blockNumber").asText());
        String blockHash=hash(receipt.path("blockHash").asText());
        if(expectedBlock!=null && expectedBlock!=height) throw invalid();
        Head head=head(network);
        int required=Math.max(15,properties.getDepositConfirmations());
        if(head.number()<height || head.number()-height+1<required) return null;
        if(!blockHash.equalsIgnoreCase(blockHash(network,height))) throw invalid();
        if(!hexBigInteger(call(network,"eth_call","[{\"to\":\""+network.tokenId()
                +"\",\"data\":\"0x313ce567\"},\"latest\"]").asText()).equals(BigInteger.valueOf(network.decimals()))) throw invalid();
        JsonNode logs=receipt.path("logs");
        if(!logs.isArray()) throw invalid();
        JsonNode selected=null;
        for(JsonNode entry:logs) if(hexLong(entry.path("logIndex").asText())==logIndex) {
            if(selected!=null) throw invalid(); selected=entry;
        }
        if(selected==null || selected.path("removed").asBoolean(false)
                || !network.tokenId().equalsIgnoreCase(selected.path("address").asText())
                || !normalized.equalsIgnoreCase(selected.path("transactionHash").asText())
                || height!=hexLong(selected.path("blockNumber").asText())
                || !blockHash.equalsIgnoreCase(selected.path("blockHash").asText())) throw invalid();
        JsonNode topics=selected.path("topics");
        if(!topics.isArray() || topics.size()!=3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0).asText())
                || !topics.get(1).asText().matches("(?i)0x0{24}[0-9a-f]{40}")
                || !topics.get(2).asText().matches("(?i)0x0{24}[0-9a-f]{40}")) throw invalid();
        String sender="0x"+topics.get(1).asText().substring(26).toLowerCase(Locale.ROOT);
        String recipient="0x"+topics.get(2).asText().substring(26).toLowerCase(Locale.ROOT);
        String raw=selected.path("data").asText();
        if(!raw.matches("(?i)0x[0-9a-f]{64}") || !recipient.equalsIgnoreCase(address)
                || new BigDecimal(new BigInteger(raw.substring(2),16),network.decimals()).compareTo(amount)!=0) throw invalid();
        JsonNode code=call(network,"eth_getCode","[\""+sender+"\",\""+hex(height)+"\"]");
        if(!code.isTextual() || !code.asText().matches("(?i)0x(?:[0-9a-f]{2})*")) throw invalid();
        boolean eoa="0x".equalsIgnoreCase(code.asText()) && !sender.equals("0x"+"0".repeat(40));
        return new TransferProof(logIndex,height,blockHash,
                Math.toIntExact(Math.min(Integer.MAX_VALUE,head.number()-height+1)),sender,recipient,amount,eoa);
    }

    /** A missing receipt or unavailable RPC alone is never evidence that an existing credit disappeared. */
    public ReorgProof inspectCanonical(CregisNetwork network,String txid,int logIndex,String address,BigDecimal amount,
                                       long originalBlock,String originalHash) {
        return inspectCanonical(network,txid,logIndex,address,amount,originalBlock,originalHash,null);
    }

    /** A relocated index needs the original token sender and exactly one matching Transfer. */
    public ReorgProof inspectCanonical(CregisNetwork network,String txid,int logIndex,String address,BigDecimal amount,
                                       long originalBlock,String originalHash,String expectedFrom) {
        if(originalBlock<0 || originalHash==null || logIndex<0 || address==null
                || !address.matches("(?i)0x[0-9a-f]{40}") || amount==null || amount.signum()<=0) throw invalid();
        String normalized=hash(txid), oldHash=hash(originalHash);
        Head head=head(network);
        int required=Math.max(15,properties.getDepositConfirmations());
        if(properties.getDepositConfirmations()<15 || head.number()<originalBlock
                || head.number()-originalBlock+1<required) return new ReorgProof(false,null,head.number(),null);
        String canonicalHash=blockHash(network,originalBlock);
        JsonNode first=call(network,"eth_getTransactionReceipt","[\""+normalized+"\"]");
        if(!first.isNull()) {
            int selectedIndex=logIndex;
            if(expectedFrom!=null) {
                if(!expectedFrom.matches("(?i)0x[0-9a-f]{40}")) throw invalid();
                Integer match=null;
                JsonNode entries=first.path("logs");
                if(!entries.isArray()) throw invalid();
                for(JsonNode entry:entries) {
                    JsonNode topics=entry.path("topics");
                    if(!network.tokenId().equalsIgnoreCase(entry.path("address").asText())
                            || !topics.isArray() || topics.size()!=3
                            || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0).asText())) continue;
                    String sender=topics.get(1).asText(),recipient=topics.get(2).asText(),raw=entry.path("data").asText();
                    if(!sender.matches("(?i)0x0{24}[0-9a-f]{40}") || !recipient.matches("(?i)0x0{24}[0-9a-f]{40}")
                            || !raw.matches("(?i)0x[0-9a-f]{64}")) throw invalid();
                    if(!sender.substring(26).equalsIgnoreCase(expectedFrom.substring(2))
                            || address==null || !recipient.substring(26).equalsIgnoreCase(address.substring(2))
                            || new BigDecimal(new BigInteger(raw.substring(2),16),network.decimals()).compareTo(amount)!=0) continue;
                    long index=hexLong(entry.path("logIndex").asText());
                    if(match!=null || index>Integer.MAX_VALUE) throw invalid();
                    match=(int)index;
                }
                if(match==null) throw invalid();
                selectedIndex=match;
            }
            TransferProof canonical=verifyReceipt(network,normalized,selectedIndex,address,amount,null,first);
            if(canonical!=null && expectedFrom!=null && !expectedFrom.equalsIgnoreCase(canonical.fromAddress())) throw invalid();
            // Legacy rows without a persisted sender cannot authenticate a relocated source.
            if(canonical!=null && expectedFrom==null && (canonical.blockNumber()!=originalBlock
                    || !canonical.blockHash().equalsIgnoreCase(oldHash))) throw invalid();
            return new ReorgProof(false,canonical,head.number(),canonicalHash);
        }
        if(oldHash.equalsIgnoreCase(canonicalHash)) return new ReorgProof(false,null,head.number(),canonicalHash);
        JsonNode second=call(network,"eth_getTransactionReceipt","[\""+normalized+"\"]");
        String secondHash=blockHash(network,originalBlock);
        Head latest=head(network);
        boolean absent=second.isNull() && canonicalHash.equalsIgnoreCase(secondHash)
                && latest.number()>=head.number();
        return new ReorgProof(absent,null,latest.number(),secondHash);
    }

    /** One bounded RPC batch; total user/address count is not capped. */
    public List<Observation> scan(CregisNetwork network,long from, long to, List<String> addresses) {
        if (from < 0 || to < from || to - from >= properties.getChainScanBlocks() || addresses == null
                || addresses.isEmpty() || addresses.size() > 60) throw invalid();
        validateNetwork(network);
        StringBuilder recipients = new StringBuilder();
        for (String address : addresses) {
            if (address == null || !address.matches("(?i)0x[0-9a-f]{40}")) throw invalid();
            if (!recipients.isEmpty()) recipients.append(',');
            recipients.append('"').append("0x000000000000000000000000")
                    .append(address.substring(2).toLowerCase(Locale.ROOT)).append('"');
        }
        String params = "[{\"fromBlock\":\"" + hex(from) + "\",\"toBlock\":\"" + hex(to)
                + "\",\"address\":\"" + network.tokenId()
                + "\",\"topics\":[\"" + TRANSFER_TOPIC + "\",null,[" + recipients + "]]}]";
        JsonNode logs = call(network,"eth_getLogs", params);
        if (!logs.isArray()) throw invalid();
        List<Observation> found = new ArrayList<>();
        for (JsonNode log : logs) {
            if (log.path("removed").asBoolean(false)
                    || !network.tokenId().equalsIgnoreCase(log.path("address").asText()))
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
                    new BigDecimal(new BigInteger(data.substring(2), 16), network.decimals()).stripTrailingZeros(), height));
        }
        return List.copyOf(found);
    }

    public Head head(CregisNetwork network) {
        if (hexLong(call(network,"eth_chainId", "[]").asText()) != hexLong(network.rpcChainId())) throw invalid();
        long height = hexLong(call(network,"eth_blockNumber", "[]").asText());
        JsonNode block = call(network,"eth_getBlockByNumber", "[\"" + hex(height) + "\",false]");
        if (!block.isObject()) throw invalid();
        return new Head(height, hash(block.path("hash").asText()));
    }

    public String blockHash(CregisNetwork network,long number) {
        if (number < 0) throw invalid();
        JsonNode block = call(network,"eth_getBlockByNumber", "[\"" + hex(number) + "\",false]");
        if (!block.isObject() || hexLong(block.path("number").asText()) != number) throw invalid();
        return hash(block.path("hash").asText());
    }

    public boolean zeroUsdtBalance(CregisNetwork network,String address) {
        if (address == null || !address.matches("(?i)0x[0-9a-f]{40}")) throw invalid();
        validateNetwork(network);
        String data = "0x70a08231" + "0".repeat(24) + address.substring(2).toLowerCase(Locale.ROOT);
        JsonNode result = call(network,"eth_call", "[{\"to\":\"" + network.tokenId()
                + "\",\"data\":\"" + data + "\"},\"latest\"]");
        return hexBigInteger(result.asText()).signum() == 0;
    }

    /** Read-only preparation facts. A positive BNB balance is not a gas estimate or a send permit. */
    public record PreparationBalance(String usdt, String bnb, long blockNumber, String blockHash) { }

    public PreparationBalance preparationBalance(CregisNetwork network,String address) {
        if (address == null || !address.matches("(?i)0x[0-9a-f]{40}")) throw invalid();
        Head at = head(network);
        String tag = hex(at.number());
        JsonNode decimals = call(network,"eth_call", "[{\"to\":\"" + network.tokenId()
                + "\",\"data\":\"0x313ce567\"},\"" + tag + "\"]");
        if (!hexBigInteger(decimals.asText()).equals(BigInteger.valueOf(network.decimals()))) throw invalid();
        String data = "0x70a08231" + "0".repeat(24) + address.substring(2).toLowerCase(Locale.ROOT);
        JsonNode token = call(network,"eth_call", "[{\"to\":\"" + network.tokenId()
                + "\",\"data\":\"" + data + "\"},\"" + tag + "\"]");
        JsonNode nativeCoin = call(network,"eth_getBalance", "[\"" + address.toLowerCase(Locale.ROOT) + "\",\"" + tag + "\"]");
        if (!at.hash().equalsIgnoreCase(blockHash(network,at.number()))) throw invalid();
        return new PreparationBalance(new BigDecimal(hexBigInteger(token.asText()), network.decimals()).toPlainString(),
                new BigDecimal(hexBigInteger(nativeCoin.asText()), 18).toPlainString(), at.number(), at.hash());
    }

    public Optional<Proof> verify(CregisNetwork network,String txid, String address, BigDecimal amount, long callbackHeight) {
        return verifyTransfer(network,txid, address, amount, callbackHeight, true);
    }

    /** A payout height comes from the real receipt; pending canonical confirmations remain observable. */
    public Optional<Proof> verifyPayout(CregisNetwork network,String txid, String address, BigDecimal amount) {
        String normalized = txid != null && txid.matches("(?i)[0-9a-f]{64}") ? "0x" + txid : txid;
        return verifyTransfer(network,normalized, address, amount, null, false);
    }

    private Optional<Proof> verifyTransfer(CregisNetwork network,String txid, String address, BigDecimal amount,
                                           Long callbackHeight, boolean requireFinality) {
        if (properties.getDepositConfirmations() < 15) throw invalid();
        if (txid == null || address == null || !txid.matches("(?i)0x[0-9a-f]{64}") || !address.matches("(?i)0x[0-9a-f]{40}")
                || amount == null || amount.signum() <= 0 || amount.scale() > 6) throw invalid();
        JsonNode receipt = call(network,"eth_getTransactionReceipt", "[\"" + txid + "\"]");
        if (receipt.isNull()) return Optional.empty();
        if (!receipt.isObject() || !"0x1".equalsIgnoreCase(receipt.path("status").asText())
                || !txid.equalsIgnoreCase(receipt.path("transactionHash").asText())) throw invalid();
        long blockNumber = hexLong(receipt.path("blockNumber").asText());
        String blockHash = hash(receipt.path("blockHash").asText());
        if (callbackHeight != null && blockNumber != callbackHeight) throw invalid();
        Head head = head(network);
        if (head.number() < blockNumber || (requireFinality
                && head.number() - blockNumber + 1 < properties.getDepositConfirmations()))
            return Optional.empty();
        JsonNode block = call(network,"eth_getBlockByNumber", "[\"" + hex(blockNumber) + "\",false]");
        if (!block.isObject() || hexLong(block.path("number").asText()) != blockNumber
                || !blockHash.equalsIgnoreCase(hash(block.path("hash").asText()))) throw invalid();
        JsonNode decimals = call(network,"eth_call", "[{\"to\":\"" + network.tokenId()
                + "\",\"data\":\"0x313ce567\"},\"latest\"]");
        if (!hexBigInteger(decimals.asText()).equals(BigInteger.valueOf(network.decimals()))) throw invalid();
        JsonNode logs = receipt.path("logs");
        if (!logs.isArray()) throw invalid();
        Integer found = null;
        String sender = null;
        for (JsonNode log : logs) {
            if (!network.tokenId().equalsIgnoreCase(log.path("address").asText())) continue;
            JsonNode topics = log.path("topics");
            if (!topics.isArray() || topics.size() != 3
                    || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0).asText())) continue;
            String recipient = topics.get(2).asText();
            if (!recipient.matches("(?i)0x[0-9a-f]{64}")
                    || !recipient.substring(26).equalsIgnoreCase(address.substring(2))) continue;
            String raw = log.path("data").asText();
            if (!raw.matches("(?i)0x[0-9a-f]{64}")) throw invalid();
            BigDecimal transferred = new BigDecimal(new BigInteger(raw.substring(2), 16), network.decimals());
            if (transferred.compareTo(amount) != 0) continue;
            if (log.path("removed").asBoolean(false) || !blockHash.equalsIgnoreCase(log.path("blockHash").asText())
                    || !txid.equalsIgnoreCase(log.path("transactionHash").asText())
                    || hexLong(log.path("blockNumber").asText()) != blockNumber)
                throw invalid();
            if (!topics.get(1).asText().matches("(?i)0x0{24}[0-9a-f]{40}")
                    || !recipient.matches("(?i)0x0{24}[0-9a-f]{40}")) throw invalid();
            long index = hexLong(log.path("logIndex").asText());
            if (index > Integer.MAX_VALUE || found != null) throw invalid();
            found = (int) index;
            sender = "0x" + topics.get(1).asText().substring(26).toLowerCase(Locale.ROOT);
        }
        if (found == null) throw invalid();
        return Optional.of(new Proof(found, blockNumber, blockHash,
                Math.toIntExact(Math.min(Integer.MAX_VALUE, head.number() - blockNumber + 1)),sender));
    }

    private JsonNode call(CregisNetwork network,String method, String params) {
        String configured = properties.rpcUrl(network);
        if (configured == null || configured.isBlank()) throw invalid();
        URI uri;
        try { uri = URI.create(configured); }
        catch (IllegalArgumentException ex) { throw invalid(); }
        boolean testLoopback = loopbackAllowed.test(uri);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || testLoopback) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) throw invalid();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method
                + "\",\"params\":" + params + "}";
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .header("User-Agent", "NexGrid-Cregis-ReadOnly/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream stream = response.body()) {
                    byte[] bytes = stream.readNBytes(1_048_577);
                    if (response.statusCode() != 200 || bytes.length > 1_048_576) throw invalid();
                    JsonNode root = json.readTree(new String(bytes, StandardCharsets.UTF_8));
                    if (root == null || root.hasNonNull("error") || root.path("id").asInt(-1) != 1
                            || !root.has("result")) throw invalid();
                    return root.get("result");
                }
            } catch (Exception unavailable) {
                lastFailure = unavailable;
                if (unavailable instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.warn("BSC RPC read unavailable method={} cause={}", method,
                lastFailure == null ? "UNKNOWN" : lastFailure.getClass().getSimpleName());
        throw new IllegalStateException("CREGIS_BSC_PROOF_UNAVAILABLE");
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

    private void validateNetwork(CregisNetwork network) {
        if (hexLong(call(network,"eth_chainId","[]").asText()) != hexLong(network.rpcChainId())
                || !hexBigInteger(call(network,"eth_call","[{\"to\":\""+network.tokenId()+"\",\"data\":\"0x313ce567\"},\"latest\"]").asText())
                    .equals(BigInteger.valueOf(network.decimals()))) throw invalid();
    }
    public TransferProof verifyExact(String txid,int index,String address,BigDecimal amount,Long block) { return verifyExact(CregisNetwork.BEP20,txid,index,address,amount,block); }
    public ReorgProof inspectCanonical(String txid,int index,String address,BigDecimal amount,long block,String hash) { return inspectCanonical(CregisNetwork.BEP20,txid,index,address,amount,block,hash); }
    public ReorgProof inspectCanonical(String txid,int index,String address,BigDecimal amount,long block,String hash,String from) { return inspectCanonical(CregisNetwork.BEP20,txid,index,address,amount,block,hash,from); }
    public List<Observation> scan(long from,long to,List<String> addresses) { return scan(CregisNetwork.BEP20,from,to,addresses); }
    public Head head() { return head(CregisNetwork.BEP20); }
    public String blockHash(long block) { return blockHash(CregisNetwork.BEP20,block); }
    public boolean zeroUsdtBalance(String address) { return zeroUsdtBalance(CregisNetwork.BEP20,address); }
    public PreparationBalance preparationBalance(String address) { return preparationBalance(CregisNetwork.BEP20,address); }
    public Optional<Proof> verify(String txid,String address,BigDecimal amount,long height) { return verify(CregisNetwork.BEP20,txid,address,amount,height); }
    public Optional<Proof> verifyPayout(String txid,String address,BigDecimal amount) { return verifyPayout(CregisNetwork.BEP20,txid,address,amount); }
}
