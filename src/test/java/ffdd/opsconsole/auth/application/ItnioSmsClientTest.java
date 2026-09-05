package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class ItnioSmsClientTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.ofEpochSecond(1_630_468_800L), ZoneOffset.UTC);

    @Test
    void sendsTheSignedNexGridLoginMessageAndAcceptsOnlyTheTargetNumber(CapturedOutput output) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ItnioSmsClient client = client(true, "api-key", "api-secret", "sms-app", "", builder);

        server.expect(once(), requestTo("https://api.itniotech.com/sms/sendSms"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Api-Key", "api-key"))
                .andExpect(header("Timestamp", "1630468800"))
                .andExpect(header("Sign", "916cca171614e17d6ccea6b02e53d807"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "appId":"sms-app",
                          "numbers":"84901234567",
                          "content":"Mã xác minh của bạn là 654321, có hiệu lực trong 5 phút. [NexGrid]",
                          "trackClicks":0
                        }
                        """, true))
                .andRespond(withSuccess("""
                        {"status":"0","reason":"success","success":"1","fail":"0",
                         "array":[{"msgId":"msg-1","number":"84901234567","orderId":""}]}
                        """, MediaType.APPLICATION_JSON));

        ItnioSmsClient.DeliveryReceipt receipt = client.send(
                "+84", "0901234567", "LOGIN-hidden", "654321", 5);

        assertThat(receipt.messageId()).isEqualTo("msg-1");
        assertThat(output).contains("msg-1", "****4567")
                .doesNotContain("84901234567", "654321");
        server.verify();
    }

    @Test
    void includesAnApprovedAsciiSenderIdWhenConfigured() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ItnioSmsClient client = client(true, "api-key", "api-secret", "sms-app", "NexGrid", builder);

        server.expect(requestTo("https://api.itniotech.com/sms/sendSms"))
                .andExpect(content().json("{\"senderId\":\"NexGrid\"}", false))
                .andExpect(content().json("""
                        {"content":"Mã xác minh của bạn là 654321, có hiệu lực trong 5 phút. [NexGrid]"}
                        """, false))
                .andRespond(withSuccess("""
                        {"status":"0","reason":"success","success":"1","fail":"0",
                         "array":[{"msgId":"msg-1","number":"84900000000","orderId":""}]}
                        """, MediaType.APPLICATION_JSON));

        client.send("+84", "0900000000", "LOGIN-hidden", "654321", 5);
        server.verify();
    }

    @Test
    void failsClosedForProviderRejectionOrAmbiguousSuccess(CapturedOutput output) {
        for (String response : new String[] {
                "{\"status\":\"-1\",\"reason\":\"authentication error\",\"success\":\"0\",\"fail\":\"1\",\"array\":[]}",
                "{\"status\":\"-6\",\"reason\":\"not a template\",\"success\":\"0\",\"fail\":\"1\",\"array\":[]}",
                "{\"status\":\"0\",\"reason\":\"success\",\"success\":\"0\",\"fail\":\"0\",\"array\":[]}",
                "{\"status\":\"0\",\"reason\":\"success\",\"success\":\"1\",\"fail\":\"0\",\"array\":[{\"msgId\":\"msg-1\",\"number\":\"84999999999\"}]}"
        }) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            ItnioSmsClient client = client(true, "api-key", "api-secret", "sms-app", "", builder);
            server.expect(requestTo("https://api.itniotech.com/sms/sendSms"))
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.send("+84", "0900000000", "LOGIN-hidden", "654321", 5))
                    .hasMessage("USER_OTP_DELIVERY_FAILED")
                    .hasMessageNotContaining("authentication error");
            server.verify();
        }
        assertThat(output)
                .contains("ITNIO_SMS_REJECTED providerStatus=-1 success=0 fail=1", "destination=****0000")
                .contains("ITNIO_SMS_REJECTED providerStatus=-6 success=0 fail=1")
                .doesNotContain("authentication error", "not a template", "84900000000", "654321", "api-secret");
    }

    @Test
    void requiresExplicitEnablementAndCompleteConfiguration() {
        assertThat(client(false, "api-key", "api-secret", "sms-app", "", RestClient.builder()).enabled())
                .isFalse();
        assertThat(client(false, "api-key", "api-secret", "sms-app", "", RestClient.builder()).available())
                .isFalse();
        assertThat(client(true, "api-key", "api-secret", "", "", RestClient.builder()).enabled())
                .isTrue();
        assertThat(client(true, "api-key", "api-secret", "", "", RestClient.builder()).available())
                .isFalse();
    }

    @Test
    void rejectsMalformedNumbersAndSenderIdsBeforeNetworkIo() {
        ItnioSmsClient malformedSender = client(
                true, "api-key", "api-secret", "sms-app", "短信", RestClient.builder());
        assertThatThrownBy(() -> malformedSender.send(
                "+84", "0900000000", "LOGIN-hidden", "654321", 5))
                .hasMessage("ITNIO_SMS_CONFIGURATION_INVALID");

        ItnioSmsClient valid = client(
                true, "api-key", "api-secret", "sms-app", "", RestClient.builder());
        assertThatThrownBy(() -> valid.send(
                "+84", "09-0000-0000", "LOGIN-hidden", "654321", 5))
                .hasMessage("OTP_PHONE_DESTINATION_INVALID");
    }

    private ItnioSmsClient client(
            boolean enabled,
            String apiKey,
            String apiSecret,
            String appId,
            String senderId,
            RestClient.Builder builder) {
        return new ItnioSmsClient(
                enabled, apiKey, apiSecret, appId, senderId,
                builder.build(), new ItnioSmsSigner(), FIXED_CLOCK);
    }
}
