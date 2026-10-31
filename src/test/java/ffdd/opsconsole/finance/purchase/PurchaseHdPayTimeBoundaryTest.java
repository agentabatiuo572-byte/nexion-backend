package ffdd.opsconsole.finance.purchase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.values;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import ffdd.opsconsole.device.application.TestCloudActivationAdmission;
import ffdd.opsconsole.finance.hdpay.*;
import ffdd.opsconsole.finance.mapper.PurchasePaymentMapper;
import java.net.InetSocketAddress;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

/** Actual loopback HTTP JSON -> production gateway -> production observation; SQL is an isolated double. */
class PurchaseHdPayTimeBoundaryTest {
    static final Instant CREATED=Instant.parse("2026-11-01T00:02:00Z"),DEADLINE=Instant.parse("2026-11-01T00:30:00Z");
    @ParameterizedTest
    @CsvSource({"-1,false,MANUAL_REVIEW,PAYMENT_BEFORE_ALLOCATION","-1,true,MANUAL_REVIEW,PAYMENT_BEFORE_ALLOCATION",
            "0,false,FUNDS_CONFIRMED,OK","0,true,FUNDS_CONFIRMED,OK","1,false,FUNDS_CONFIRMED,OK","1,true,FUNDS_CONFIRMED,OK"})
    void originalIntentCreationBoundsTheParsedProviderPayment(long paidOffset,boolean grace,String expectedStatus,String expectedReason) throws Exception {
        String no="HDPAY-TIME-BOUNDARY";Instant paid=CREATED.plusSeconds(paidOffset);
        var json=new ObjectMapper();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] response=json.writeValueAsBytes(Map.of("code",200,"data",values("merchantId","100001","merchantOrderId",no,
                "orderId","PROVIDER-BOUNDARY","orderStatus",3,"transAmt",500000,"payType","BANKQR","appLink","",
                "expirationTime",DEADLINE.toString(),"payTime",paid.toString())));
        server.createContext("/api/order/api/payOrder/queryPayOrder",exchange->{
            exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,response.length);
            exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try {
            var hd=new HdPayProperties();hd.setMode(HdPayProperties.Mode.PROVIDER);
            hd.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/api/order");hd.setAllowInsecureBaseUrlForTests(true);
            hd.setMerchantId("100001");hd.setMd5Key("PUBLIC-FIXTURE-ONLY-KEY");
            hd.setCallbackBaseUrl("https://callback.fixture.example");hd.setCallbackHosts(List.of("callback.fixture.example"));
            var gateway=new HttpHdPayGateway(hd,json);assertThat(gateway.queryPayOrder(no).paidAt()).isEqualTo(paid);
            var db=mock(PurchasePaymentMapper.class);var row=values("intent_no",no,"user_id",7L,"kind","ORDER_PAY",
                    "payment_method","HDPAY_BANK","created_at",Timestamp.from(CREATED),"expires_at",Timestamp.from(DEADLINE),
                    "quote_json","{\"amountVnd\":\"500000\"}","payload_json","{\"orderNo\":\"ORDER\"}");
            when(db.one("SELECT * FROM nx_purchase_payment_intent WHERE intent_no=? FOR UPDATE",no)).thenReturn(row);
            when(db.one("SELECT * FROM nx_purchase_payment_intent WHERE intent_no=?",no)).thenReturn(row);
            var confirmation=new AtomicReference<Object[]>();
            when(db.write(anyString(),any(Object[].class))).thenAnswer(call->{
                if(call.getArgument(0,String.class).startsWith("UPDATE nx_purchase_payment_intent SET provider_reference=COALESCE"))
                    confirmation.set((Object[])call.getRawArguments()[1]);return 1;
            });
            var source=mock(DataSource.class);var connection=mock(Connection.class);
            when(source.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
            var service=new PurchasePaymentService(db,null,null,null,null,hd,gateway,null,null,null,null,
                    new DataSourceTransactionManager(source),json,null,null,mock(TestCloudActivationAdmission.class),
                    Clock.fixed(grace?DEADLINE.plusSeconds(60):CREATED.plusSeconds(10),ZoneOffset.UTC));
            assertThat((Boolean)ReflectionTestUtils.invokeMethod(service,"observeHd",row)).isTrue();
            Object[] args=confirmation.get();assertThat(args).isNotNull();
            assertThat(args[4]).isEqualTo(expectedStatus);assertThat(args[5]).isEqualTo("OK".equals(expectedReason)?"":expectedReason);
            assertThat(((Timestamp)args[2]).toInstant()).as("retain the provider's original absolute time even when rejected").isEqualTo(paid);
        } finally {server.stop(0);}
    }
}
