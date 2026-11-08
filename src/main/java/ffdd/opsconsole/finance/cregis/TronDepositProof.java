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
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import static ffdd.opsconsole.finance.cregis.BscDepositProof.*;

/** Native read-only TRON proof. FullNode depth never substitutes for solidification. */
@Component
public final class TronDepositProof {
    private static final CregisNetwork NETWORK = CregisNetwork.TRC20;
    private static final String GENESIS = "00000000000000001ebf88508a03865c71d452e25f4d51194196a1d22b6653dc";
    private static final String TRANSFER = "ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private final CregisProperties config;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private long lastRequestNanos;

    public TronDepositProof(CregisProperties config,ObjectMapper json) { this.config=config; this.json=json; }

    private void validateNetwork() {
        JsonNode genesis = block(0);
        if (!GENESIS.equals(genesis.path("blockID").asText())
                || !GENESIS.equals(call("wallet/getblockbynum",Map.of("num",0)).path("blockID").asText())
                || config.getDepositConfirmations()<15 || !constant("decimals()","",true).equals(BigInteger.valueOf(6))) throw invalid();
    }

    public Head head() {
        validateNetwork();
        return headOf(call("wallet/getnowblock",Map.of()));
    }
    public long finalizedHeight(int confirmations) {
        Head full=head(), solid=headOf(call("walletsolidity/getnowblock",Map.of()));
        if (solid.number()>full.number()) throw invalid();
        return Math.min(solid.number(),full.number()-Math.max(15,confirmations)+1);
    }
    public Head allocationHead() {
        long height=finalizedHeight(config.getDepositConfirmations());
        if(height<0)throw invalid();
        return new Head(height,blockHash(height));
    }
    public String blockHash(long height) { return headOf(block(height)).hash(); }
    public Instant blockTime(long height,String expectedHash) {
        JsonNode value=block(height);
        if (!NETWORK.normalizeTxid(expectedHash).equals(headOf(value).hash())) throw invalid();
        long timestamp=value.path("block_header").path("raw_data").path("timestamp").asLong(-1);
        if(timestamp<0)throw invalid();
        return Instant.ofEpochMilli(timestamp);
    }
    public boolean zeroUsdtBalance(String address) {
        validateNetwork();
        String parameter="0".repeat(24)+TronAddress.toHex(address).substring(2);
        return constant("balanceOf(address)",parameter,true).signum()==0
                && constant("balanceOf(address)",parameter,false).signum()==0;
    }
    private BigInteger constant(String selector,String parameter,boolean solid) {
        JsonNode value=call((solid?"walletsolidity/":"wallet/")+"triggerconstantcontract",Map.of("owner_address",NETWORK.tokenId(),
                "contract_address",NETWORK.tokenId(),"function_selector",selector,"parameter",parameter,"visible",true));
        JsonNode results=value.path("constant_result");
        if(!value.path("result").path("result").asBoolean(false) || !results.isArray() || results.size()!=1
                || !results.get(0).asText().matches("(?i)[0-9a-f]{64}"))throw invalid();
        return new BigInteger(results.get(0).asText(),16);
    }

    public TransferProof verifyExact(String txid,int index,String address,BigDecimal amount,Long expectedBlock) {
        String id=NETWORK.normalizeTxid(txid), recipient=NETWORK.normalizeAddress(address);
        if(index<0 || amount==null || amount.signum()<=0 || amount.stripTrailingZeros().scale()>6)throw invalid();
        long finalized=finalizedHeight(config.getDepositConfirmations());
        JsonNode body=call("walletsolidity/gettransactionbyid",Map.of("value",id));
        JsonNode receipt=call("walletsolidity/gettransactioninfobyid",Map.of("value",id));
        if(body.isEmpty() || receipt.isEmpty())return null;
        long height=receipt.path("blockNumber").asLong(-1);
        if(height<0 || expectedBlock!=null && expectedBlock!=height || !id.equals(receipt.path("id").asText())
                || !id.equals(body.path("txID").asText()))throw invalid();
        if(height>finalized)return null;
        if(!successful(receipt) || !"SUCCESS".equals(body.path("ret").path(0).path("contractRet").asText()))throw invalid();
        JsonNode canonical=block(height);
        if(!transactionIds(canonical).contains(id))throw invalid();
        JsonNode logs=receipt.path("log");
        if(!logs.isArray() || index>=logs.size())throw invalid();
        Transfer transfer=transfer(logs.get(index));
        if(transfer==null || !recipient.equals(transfer.to()) || amount.compareTo(transfer.amount())!=0)throw invalid();
        JsonNode sender=call("walletsolidity/getaccount",Map.of("address",transfer.from(),"visible",true));
        if(!NETWORK.addressEquals(transfer.from(),sender.path("address").asText()))throw invalid();
        boolean eoa=!"Contract".equalsIgnoreCase(sender.path("type").asText())
                && !TronAddress.toHex(transfer.from()).equals("41"+"0".repeat(40));
        long full=headOf(call("wallet/getnowblock",Map.of())).number();
        if(full<height)throw invalid();
        return new TransferProof(index,height,headOf(canonical).hash(),
                Math.toIntExact(Math.min(Integer.MAX_VALUE,full-height+1)),transfer.from(),recipient,amount,eoa);
    }

    public java.util.Optional<Proof> verify(String txid,String address,BigDecimal amount,Long expectedBlock) {
        String id=NETWORK.normalizeTxid(txid);
        JsonNode receipt=call("walletsolidity/gettransactioninfobyid",Map.of("value",id));
        if(receipt.isEmpty())return java.util.Optional.empty();
        JsonNode logs=receipt.path("log");
        if(!logs.isArray())throw invalid();
        Integer match=null;
        for(int i=0;i<logs.size();i++) {
            Transfer value=transfer(logs.get(i));
            if(value!=null && NETWORK.addressEquals(address,value.to()) && value.amount().compareTo(amount)==0) {
                if(match!=null)throw invalid(); match=i;
            }
        }
        if(match==null)throw invalid();
        TransferProof proof=verifyExact(id,match,address,amount,expectedBlock);
        return proof==null ? java.util.Optional.empty() : java.util.Optional.of(new Proof(proof.logIndex(),proof.blockNumber(),
                proof.blockHash(),proof.confirmations(),proof.fromAddress()));
    }

    /** Complete native receipt batches are cross-checked against each solidified block's transaction set. */
    public List<Observation> scan(long from,long to,List<String> addresses) {
        if(from<0 || to<from || to-from>=config.getChainScanBlocks() || addresses==null || addresses.isEmpty() || addresses.size()>60)throw invalid();
        if(to>finalizedHeight(config.getDepositConfirmations()))throw invalid();
        Set<String> recipients=new HashSet<>();
        for(String address:addresses)recipients.add(NETWORK.normalizeAddress(address));
        List<Observation> found=new ArrayList<>();
        for(long height=from;height<=to;height++) {
            JsonNode canonical=block(height), receipts=call("walletsolidity/gettransactioninfobyblocknum",Map.of("num",height));
            if(!receipts.isArray())throw invalid();
            Set<String> expected=transactionIds(canonical), observed=new HashSet<>();
            for(JsonNode receipt:receipts) {
                String id=NETWORK.normalizeTxid(receipt.path("id").asText());
                if(receipt.path("blockNumber").asLong(-1)!=height || !observed.add(id))throw invalid();
                if(!successful(receipt))continue;
                JsonNode logs=receipt.path("log");
                if(logs.isMissingNode())continue;
                if(!logs.isArray())throw invalid();
                for(int i=0;i<logs.size();i++) {
                    Transfer value=transfer(logs.get(i));
                    if(value!=null && recipients.contains(value.to()) && value.amount().signum()>0)
                        found.add(new Observation(id,i,value.to(),value.amount(),height));
                }
            }
            if(!observed.equals(expected) || !headOf(canonical).hash().equals(blockHash(height)))throw invalid();
        }
        return List.copyOf(found);
    }

    public ReorgProof inspectCanonical(String txid,int index,String address,BigDecimal amount,long originalBlock,String originalHash,String expectedFrom) {
        String id=NETWORK.normalizeTxid(txid), oldHash=NETWORK.normalizeTxid(originalHash);
        long finalized=finalizedHeight(config.getDepositConfirmations());
        if(originalBlock<0 || originalBlock>finalized)return new ReorgProof(false,null,finalized,null);
        String canonicalHash=blockHash(originalBlock);
        JsonNode receipt=call("walletsolidity/gettransactioninfobyid",Map.of("value",id));
        if(!receipt.isEmpty()) {
            int selected=index;
            if(expectedFrom!=null) {
                Integer match=null; JsonNode logs=receipt.path("log");
                if(!logs.isArray())throw invalid();
                for(int i=0;i<logs.size();i++) {
                    Transfer value=transfer(logs.get(i));
                    if(value!=null && NETWORK.addressEquals(expectedFrom,value.from()) && NETWORK.addressEquals(address,value.to()) && value.amount().compareTo(amount)==0) {
                        if(match!=null)throw invalid(); match=i;
                    }
                }
                if(match==null)throw invalid(); selected=match;
            }
            TransferProof proof=verifyExact(id,selected,address,amount,null);
            if(proof!=null && expectedFrom==null && (proof.blockNumber()!=originalBlock || !oldHash.equals(proof.blockHash())))throw invalid();
            return new ReorgProof(false,proof,finalized,canonicalHash);
        }
        // Native empty-body/receipt responses cannot prove non-inclusion. Retain the incident and funds.
        return new ReorgProof(false,null,finalized,canonicalHash);
    }

    private record Transfer(String from,String to,BigDecimal amount) { }
    private Transfer transfer(JsonNode log) {
        String contract=log.path("address").asText();
        if(!contract.matches("(?i)[0-9a-f]{40}"))throw invalid();
        if(!TronAddress.toHex(NETWORK.tokenId()).substring(2).equalsIgnoreCase(contract))return null;
        JsonNode topics=log.path("topics");
        if(!topics.isArray() || topics.isEmpty() || !TRANSFER.equalsIgnoreCase(topics.get(0).asText()))return null;
        String data=log.path("data").asText();
        if(topics.size()!=3 || !topics.get(1).asText().matches("(?i)0{24}[0-9a-f]{40}")
                || !topics.get(2).asText().matches("(?i)0{24}[0-9a-f]{40}") || !data.matches("(?i)[0-9a-f]{64}"))throw invalid();
        return new Transfer(TronAddress.fromHex("41"+topics.get(1).asText().substring(24)),
                TronAddress.fromHex("41"+topics.get(2).asText().substring(24)),new BigDecimal(new BigInteger(data,16),6));
    }
    private boolean successful(JsonNode receipt) { return !"FAILED".equals(receipt.path("result").asText()) && "SUCCESS".equals(receipt.path("receipt").path("result").asText()); }
    private JsonNode block(long height) {
        if(height<0)throw invalid();
        JsonNode value=call("walletsolidity/getblockbynum",Map.of("num",height));
        if(headOf(value).number()!=height)throw invalid();
        return value;
    }
    private Head headOf(JsonNode value) {
        JsonNode number=value.path("block_header").path("raw_data").path("number");
        // protobuf JSON omits the zero-valued genesis number.
        long height=number.isMissingNode() && GENESIS.equals(value.path("blockID").asText()) ? 0 : number.asLong(-1);
        if(height<0)throw invalid();
        String hash=NETWORK.normalizeTxid(value.path("blockID").asText());
        if(!hash.startsWith(String.format(java.util.Locale.ROOT,"%016x",height)))throw invalid();
        return new Head(height,hash);
    }
    private Set<String> transactionIds(JsonNode block) {
        JsonNode transactions=block.path("transactions"); Set<String> ids=new HashSet<>();
        if(transactions.isMissingNode())return ids;
        if(!transactions.isArray())throw invalid();
        for(JsonNode transaction:transactions)if(!ids.add(NETWORK.normalizeTxid(transaction.path("txID").asText())))throw invalid();
        return ids;
    }
    private synchronized JsonNode call(String path,Map<String,?> body) {
        URI base;
        try { base=URI.create(config.getTronRpcUrl()); }
        catch(IllegalArgumentException invalid){throw unavailable();}
        if(!"https".equalsIgnoreCase(base.getScheme()) || base.getHost()==null || base.getUserInfo()!=null
                || base.getQuery()!=null || base.getFragment()!=null)throw unavailable();
        try {
            URI endpoint=URI.create(base.toString().replaceAll("/+$","")+"/"+path);
            HttpRequest.Builder request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if(config.getTronApiKey()!=null && !config.getTronApiKey().isBlank())request.header("TRON-PRO-API-KEY",config.getTronApiKey());
            HttpRequest prepared=request.build();
            long intervalNanos=TimeUnit.MILLISECONDS.toNanos(config.getTronRequestMinIntervalMs());
            // One shared TRON reader serializes requests; no chain proof or balance result is cached.
            for(int attempt=0;attempt<2;attempt++) {
                if(lastRequestNanos!=0)TimeUnit.NANOSECONDS.sleep(Math.max(0,intervalNanos-(System.nanoTime()-lastRequestNanos)));
                lastRequestNanos=System.nanoTime();
                HttpResponse<InputStream> response=http.send(prepared,HttpResponse.BodyHandlers.ofInputStream());
                long retryWait=-1;
                try(InputStream stream=response.body()) {
                    if(response.statusCode()==429 && attempt==0)retryWait=retryAfterMillis(response);
                    if(response.statusCode()!=200 && retryWait<0)
                        throw unavailable(new java.io.IOException("TRON_HTTP_STATUS_"+response.statusCode()));
                    if(retryWait<0) {
                        byte[] bytes=stream.readNBytes(8_388_609);
                        if(bytes.length>8_388_608)throw unavailable(new java.io.IOException("TRON_RESPONSE_TOO_LARGE"));
                        JsonNode value;
                        try { value=json.readTree(bytes); }
                        catch(com.fasterxml.jackson.core.JsonProcessingException invalid) {
                            // Parser exception messages can quote the response body; retain only a safe diagnostic.
                            throw unavailable(new java.io.IOException("TRON_RESPONSE_JSON_INVALID_"+invalid.getClass().getSimpleName()));
                        }
                        if(value==null || value.has("Error") || value.has("error"))
                            throw unavailable(new java.io.IOException("TRON_RESPONSE_REJECTED"));
                        return value;
                    }
                }
                TimeUnit.MILLISECONDS.sleep(retryWait);
            }
            throw unavailable(new java.io.IOException("TRON_RATE_LIMIT_RETRY_EXHAUSTED"));
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw unavailable(interrupted); }
        catch(java.io.IOException invalid) { throw unavailable(invalid); }
    }
    private long retryAfterMillis(HttpResponse<?> response) {
        String value=response.headers().firstValue("Retry-After").orElse("").trim();
        if(value.isEmpty())return 1000;
        if(value.matches("[0-9]+")) {
            BigInteger seconds=new BigInteger(value);
            return seconds.compareTo(BigInteger.valueOf(5))>0 ? -1 : Math.max(1000,seconds.longValue()*1000);
        }
        try {
            long millis=Duration.between(Instant.now(),ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis();
            return millis>5000 ? -1 : Math.max(1000,millis);
        } catch(java.time.DateTimeException | ArithmeticException invalid) { return 1000; }
    }
    private static IllegalStateException invalid() { return new IllegalStateException("CREGIS_TRON_PROOF_INVALID"); }
    private static IllegalStateException unavailable() { return new IllegalStateException("CREGIS_TRON_PROOF_UNAVAILABLE"); }
    private static IllegalStateException unavailable(Throwable cause) { return new IllegalStateException("CREGIS_TRON_PROOF_UNAVAILABLE",cause); }
}
