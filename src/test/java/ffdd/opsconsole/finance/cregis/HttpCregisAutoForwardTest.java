package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

/** Contract tests exclusively against a loopback server. No provider credentials or public endpoints. */
class HttpCregisAutoForwardTest {
    final ObjectMapper json=new ObjectMapper();final CregisProperties config=new CregisProperties();
    final AtomicInteger requests=new AtomicInteger();final AtomicReference<String> path=new AtomicReference<>(),reply=new AtomicReference<>("{\"code\":\"00000\",\"data\":{\"cid\":123}}");
    final AtomicReference<Map<String,Object>> sent=new AtomicReference<>();int responseStatus=200;HttpServer server;
    final String source="0x"+"a".repeat(40),tx="0x"+"b".repeat(64);
    @BeforeEach void setup()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e->{requests.incrementAndGet();path.set(e.getRequestURI().getPath());
            @SuppressWarnings("unchecked")var body=(Map<String,Object>)json.readValue(e.getRequestBody(),Map.class);sent.set(body);
            assertThat(new CregisSigner().verify(config.getApiKey(),body,String.valueOf(body.get("sign")))).isTrue();
            byte[] bytes=reply.get().getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(responseStatus,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        config.setMode(CregisProperties.Mode.PROVIDER);config.setPayoutEnabled(true);config.setProjectId(42);config.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
        config.setCallbackBaseUrl("https://example.invalid/cregis");config.setApiKey("offline-fixture-key");
    }
    @AfterEach void close(){server.stop(0);}
    HttpCregisGateway gateway(){return new HttpCregisGateway(config,json,HttpClient.newHttpClient(),Clock.systemUTC(),()->"abc123",true);}
    CregisGateway.CollectionRequest collection(){return new CregisGateway.CollectionRequest(source,CregisFundsPreparationService.PLATFORM,new BigDecimal("5.000000"));}
    CregisGateway.PayoutRequest payment(){return new CregisGateway.PayoutRequest(CregisConstants.USDT_BEP20_CURRENCY,CregisFundsPreparationService.COUNTERPARTY,new BigDecimal("4.000000"),"CA-P-42-100","https://example.invalid/cregis/payout","auto fixture");}
    @Test void defaultTransportBlockPreventsAllProviderWrites(){
        assertThatThrownBy(()->gateway().createCollection(collection())).isInstanceOf(CregisGatewayException.class);
        assertThatThrownBy(()->gateway().createAutoForwardPayout(payment())).isInstanceOf(CregisGatewayException.class);assertThat(requests).hasValue(0);
    }
    @Test void collectionIsExplicitAmountAndPayoutV2PinsBothAddresses(){config.setAutoForwardEnabled(true);
        assertThat(gateway().createCollection(collection())).isEqualTo(123);
        assertThat(path).hasValue("/api/v1/collection");assertThat(sent.get()).containsEntry("from_address",source).containsEntry("to_address",CregisFundsPreparationService.PLATFORM).containsEntry("amount","5.000000");
        assertThat(gateway().createAutoForwardPayout(payment()).cid()).isEqualTo(123);assertThat(path).hasValue("/api/v2/payout");
        assertThat(sent.get()).containsEntry("from_address",CregisFundsPreparationService.PLATFORM).containsEntry("to_address",CregisFundsPreparationService.COUNTERPARTY).containsEntry("amount","4").containsEntry("third_party_id","CA-P-42-100");
    }
    @Test void collectionTransportNeverRetriesUnknownAndRejectsTargetOverride(){config.setAutoForwardEnabled(true);responseStatus=503;
        assertThatThrownBy(()->gateway().createCollection(collection())).hasMessage("CREGIS_SUBMISSION_UNKNOWN");assertThat(requests).hasValue(1);
        assertThatThrownBy(()->gateway().createCollection(new CregisGateway.CollectionRequest(source,CregisFundsPreparationService.COUNTERPARTY,BigDecimal.ONE))).isInstanceOf(CregisGatewayException.class);
        assertThat(requests).hasValue(1);
    }
    @Test void collectionQueryIsReadOnlyWhenDisabledAndRejectsForeignIdentity()throws Exception {
        Map<String,Object> row=new HashMap<>(Map.of("pid",42L,"cid",123L,"chain_id","2510","token_id",CregisConstants.USDT_BEP20_TOKEN_ID,"from_address",source,"to_address",CregisFundsPreparationService.PLATFORM,"amount","5.000000","business_type","5","trade_type","2","status",1));
        row.put("currency",CregisConstants.USDT_BEP20_CURRENCY);row.put("txid",tx);setRow(row);
        assertThat(gateway().queryCollection(123,collection())).contains(new CregisGateway.CollectionOrder(123,1,tx));assertThat(path).hasValue("/api/v1/trade/page");
        for(String field:List.of("pid","cid","from_address","to_address","amount","business_type","trade_type","chain_id","token_id","currency")){
            var wrong=new HashMap<>(row);wrong.put(field,Set.of("pid","cid").contains(field)?99L:field.equals("amount")?"4":"wrong");setRow(wrong);
            assertThatThrownBy(()->gateway().queryCollection(123,collection())).isInstanceOf(CregisGatewayException.class);
        }
    }
    void setRow(Map<String,Object> row)throws Exception{reply.set(json.writeValueAsString(Map.of("code","00000","data",Map.of("total",1,"rows",List.of(row)))));}
}
