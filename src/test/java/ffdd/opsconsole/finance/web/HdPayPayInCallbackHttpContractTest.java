package ffdd.opsconsole.finance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.finance.hdpay.HdPayCallbackService;
import ffdd.opsconsole.finance.hdpay.HdPayCallbackSettlementService;
import ffdd.opsconsole.finance.hdpay.HdPayCallbackVerifier;
import ffdd.opsconsole.finance.hdpay.HdPayGateway;
import ffdd.opsconsole.finance.hdpay.HdPayProperties;
import ffdd.opsconsole.finance.hdpay.HdPaySigner;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class HdPayPayInCallbackHttpContractTest {
    private static final String TEST_KEY = "0123456789abcdef0123456789abcdef";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HdPayGateway gateway = mock(HdPayGateway.class);
    private final HdPayCallbackSettlementService settlement = mock(HdPayCallbackSettlementService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        HdPayProperties properties = new HdPayProperties();
        properties.setMode(HdPayProperties.Mode.PROVIDER);
        properties.setBaseUrl("https://api.hdpayadmin.com/api/order");
        properties.setCallbackBaseUrl("https://payments.example.com");
        properties.setCallbackHosts(List.of("payments.example.com"));
        properties.setMerchantId("1234567890123456789");
        properties.setMd5Key(TEST_KEY);
        properties.setPayType("BANKQR");
        properties.setCountryCode("VN");
        HdPayCallbackService service = new HdPayCallbackService(
                gateway, new HdPayCallbackVerifier(properties), settlement);
        mockMvc = standaloneSetup(new HdPayPayInCallbackController(service))
                .setControllerAdvice(new GlobalExceptionHandler(mock(AuditLogService.class)))
                .build();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void acceptedAmountFormatsReachProviderConfirmationAndReturnPlainSuccess(boolean textAmount)
            throws Exception {
        ObjectNode body = callback(textAmount);
        var fact = new HdPayCallbackSettlementService.PaidCallbackFact(
                "test-hash", "VQR-TEST", "P-TEST", 3, new BigDecimal("897260.00"));
        var query = new HdPayGateway.PayOrder(
                "VQR-TEST", "P-TEST", 3, new BigDecimal("897260.00"), "BANKQR", "");
        when(settlement.claimForProviderQuery(any())).thenReturn(
                new HdPayCallbackSettlementService.QueryClaim(
                        HdPayCallbackSettlementService.ClaimDisposition.QUERY_PROVIDER, fact, "test-claim"));
        when(gateway.queryPayOrder("VQR-TEST")).thenReturn(query);
        when(settlement.settleConfirmed(fact, "test-claim", query)).thenReturn("success");

        mockMvc.perform(post(HdPayProperties.PAY_IN_CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("success"));

        var order = inOrder(settlement, gateway);
        var verified = ArgumentCaptor.forClass(HdPayCallbackVerifier.VerifiedCallback.class);
        order.verify(settlement).claimForProviderQuery(verified.capture());
        assertThat(verified.getValue().transAmt()).isEqualByComparingTo("897260.00");
        order.verify(gateway).queryPayOrder("VQR-TEST");
        order.verify(settlement).settleConfirmed(fact, "test-claim", query);
    }

    @ParameterizedTest
    @ValueSource(strings = {"897261.00", "897260.001"})
    void tamperedOrInvalidAmountIsRejectedBeforeAnySettlementOrProviderCall(String amount)
            throws Exception {
        ObjectNode body = callback(true);
        body.put("transAmt", amount);

        mockMvc.perform(post(HdPayProperties.PAY_IN_CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(settlement, gateway);
    }

    private ObjectNode callback(boolean textAmount) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("createTime", "2026-09-15 16:41:00");
        body.put("merchantId", "1234567890123456789");
        body.put("merchantOrderId", "VQR-TEST");
        body.put("orderId", "P-TEST");
        body.put("orderStatus", 3);
        body.put("payTime", "2026-09-15 16:53:31");
        body.put("signType", "MD5");
        body.put("standbyObject", "{}");
        body.put("transAmt", "897260.00");
        Map<String, String> fields = new LinkedHashMap<>();
        body.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue().asText()));
        body.put("sign", HdPaySigner.sign(fields, TEST_KEY));
        if (!textAmount) body.put("transAmt", new BigDecimal("897260.00"));
        return body;
    }
}
