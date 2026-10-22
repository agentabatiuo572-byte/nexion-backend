package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class BscExceptionProofTest {
    final String tx="0x"+"a".repeat(64),to="0x"+"b".repeat(40),from="0x"+"c".repeat(40),old="0x"+"d".repeat(64);
    final ObjectMapper json=new ObjectMapper();HttpServer server;boolean missing,error,contract,ambiguous;String canonical;
    int currentIndex=3;long currentBlock=100;
    final AtomicInteger receipts=new AtomicInteger();
    @AfterEach void close(){if(server!=null)server.stop(0);}
    @Test void selectsExactLogAndTransferSenderRatherThanTransactionFrom() throws Exception {
        var proof=local();var verified=proof.verifyExact(tx,3,to,BigDecimal.TEN,100L);
        assertThat(verified.fromAddress()).isEqualTo(from);assertThat(verified.logIndex()).isEqualTo(3);assertThat(verified.senderEoa()).isTrue();
        assertThatThrownBy(()->proof.verifyExact(tx,2,to,BigDecimal.TEN,100L)).hasMessage("CREGIS_BSC_PROOF_INVALID");
        contract=true;assertThat(proof.verifyExact(tx,3,to,BigDecimal.TEN,100L).senderEoa()).isFalse();
    }
    @Test void absenceRequiresChangedOriginalCanonicalHashAndTwoSuccessfulMissingReceiptReads() throws Exception {
        var proof=local();missing=true;
        assertThat(proof.inspectCanonical(tx,3,to,BigDecimal.TEN,100,old).positivelyVerifiedAbsent()).isFalse();
        canonical="0x"+"e".repeat(64);receipts.set(0);
        assertThat(proof.inspectCanonical(tx,3,to,BigDecimal.TEN,100,old).positivelyVerifiedAbsent()).isTrue();assertThat(receipts).hasValue(2);
        error=true;assertThatThrownBy(()->proof.inspectCanonical(tx,3,to,BigDecimal.TEN,100,old)).hasMessage("CREGIS_BSC_PROOF_UNAVAILABLE");
    }
    @Test void relocatedGlobalIndexRequiresOriginalSenderAndUniqueCanonicalMatch() throws Exception {
        var proof=local();currentIndex=7;currentBlock=101;canonical="0x"+"e".repeat(64);
        assertThatThrownBy(()->proof.inspectCanonical(tx,3,to,BigDecimal.TEN,100,old)).hasMessage("CREGIS_BSC_PROOF_INVALID");
        var restored=proof.inspectCanonical(tx,3,to,BigDecimal.TEN,100,old,from);
        assertThat(restored.canonical().logIndex()).isEqualTo(7);assertThat(restored.canonical().blockNumber()).isEqualTo(101);
        ambiguous=true;
        assertThatThrownBy(()->proof.inspectCanonical(tx,3,to,BigDecimal.TEN,100,old,from)).hasMessage("CREGIS_BSC_PROOF_INVALID");
        ambiguous=false;
        assertThatThrownBy(()->proof.inspectCanonical(tx,3,to,BigDecimal.TEN,100,old,"0x"+"1".repeat(40))).hasMessage("CREGIS_BSC_PROOF_INVALID");
    }
    BscDepositProof local() throws Exception {
        canonical=old;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{var request=json.readTree(exchange.getRequestBody());String method=request.path("method").asText();Object result;
            if(method.equals("eth_getTransactionReceipt"))receipts.incrementAndGet();
            result=switch(method){case "eth_chainId"->"0x38";case "eth_blockNumber"->"0x80";
                case "eth_getBlockByNumber"->Map.of("number",request.path("params").get(0).asText(),"hash",canonical);
                case "eth_call"->"0x"+"0".repeat(62)+"12";case "eth_getCode"->contract?"0x6000":"0x";
                case "eth_getTransactionReceipt"->missing?null:Map.of("transactionHash",tx,"from","0x"+"f".repeat(40),"status","0x1","blockNumber","0x"+Long.toHexString(currentBlock),"blockHash",canonical,"logs",ambiguous?List.of(log(currentIndex,from),log(currentIndex+1,from)):List.of(log(1,"0x"+"f".repeat(40)),log(currentIndex,from)));
                default->null;};
            Map<String,Object> response=new HashMap<>();response.put("jsonrpc","2.0");response.put("id",1);
            if(error)response.put("error",Map.of("code",-1));else response.put("result",result);
            byte[] bytes=json.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
        server.start();var p=new CregisProperties();p.setBscRpcUrl("http://127.0.0.1:"+server.getAddress().getPort());return new BscDepositProof(p,json,HttpClient.newHttpClient(),true);
    }
    Map<String,Object> log(int index,String sender){return Map.of("logIndex","0x"+Integer.toHexString(index),"address",CregisConstants.USDT_BEP20_TOKEN_ID,
            "topics",List.of("0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef","0x"+"0".repeat(24)+sender.substring(2),"0x"+"0".repeat(24)+to.substring(2)),
            "data","0x"+String.format("%064x",BigInteger.TEN.multiply(BigInteger.TEN.pow(18))),"blockNumber","0x"+Long.toHexString(currentBlock),"blockHash",canonical,"transactionHash",tx,"removed",false);}
}
