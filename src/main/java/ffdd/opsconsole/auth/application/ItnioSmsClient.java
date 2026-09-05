package ffdd.opsconsole.auth.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@Slf4j
public class ItnioSmsClient {
    private static final String SEND_SMS_URL = "https://api.itniotech.com/sms/sendSms";

    private final ItnioConfiguration configuration;
    private final RestClient restClient;
    private final ItnioSmsSigner signer;
    private final Clock clock;

    @Autowired
    public ItnioSmsClient(
            RestClient.Builder restClientBuilder,
            ItnioSmsSigner signer,
            @Value("${nexgrid.sms.itnio.enabled:false}") boolean enabled,
            @Value("${nexgrid.sms.itnio.api-key:}") String apiKey,
            @Value("${nexgrid.sms.itnio.api-secret:}") String apiSecret,
            @Value("${nexgrid.sms.itnio.app-id:}") String appId,
            @Value("${nexgrid.sms.itnio.sender-id:}") String senderId,
            @Value("${nexgrid.sms.itnio.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${nexgrid.sms.itnio.read-timeout-ms:5000}") int readTimeoutMs) {
        this(enabled, apiKey, apiSecret, appId, senderId,
                buildRestClient(restClientBuilder, connectTimeoutMs, readTimeoutMs),
                signer, Clock.systemUTC());
    }

    ItnioSmsClient(
            boolean enabled,
            String apiKey,
            String apiSecret,
            String appId,
            String senderId,
            RestClient restClient,
            ItnioSmsSigner signer,
            Clock clock) {
        this.configuration = new ItnioConfiguration(enabled, apiKey, apiSecret, appId, senderId);
        this.restClient = restClient;
        this.signer = signer;
        this.clock = clock;
    }

    boolean enabled() {
        return configuration.enabled();
    }

    boolean available() {
        return configuration.enabled() && requiredConfigurationPresent() && validSenderId();
    }

    DeliveryReceipt send(String countryCode, String phone, String challengeNo, String code, int ttlMinutes) {
        if (!configuration.enabled() || !requiredConfigurationPresent()) {
            throw new IllegalStateException("USER_OTP_DELIVERY_UNAVAILABLE");
        }
        if (!validSenderId()) {
            throw new IllegalStateException("ITNIO_SMS_CONFIGURATION_INVALID");
        }
        String number = OtpPhoneCanonicalizer.toE164Digits(countryCode, phone);
        String content = NexGridOtpMessage.render(countryCode, challengeNo, code, ttlMinutes);
        long epochSeconds = clock.instant().getEpochSecond();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appId", configuration.appId().trim());
        body.put("numbers", number);
        body.put("content", content);
        if (StringUtils.hasText(configuration.senderId())) {
            body.put("senderId", configuration.senderId().trim());
        }
        body.put("trackClicks", 0);

        ItnioSendResponse response;
        try {
            response = restClient.post()
                    .uri(SEND_SMS_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Api-Key", configuration.apiKey().trim())
                    .header("Timestamp", Long.toString(epochSeconds))
                    .header("Sign", signer.sign(
                            configuration.apiKey().trim(), configuration.apiSecret(), epochSeconds))
                    .body(body)
                    .retrieve()
                    .body(ItnioSendResponse.class);
        } catch (RestClientException exception) {
            log.warn("ITNIO_SMS_TRANSPORT_FAILED type={} destination={}",
                    exception.getClass().getSimpleName(), maskDestination(number));
            throw deliveryFailed(exception);
        }

        ItnioAcceptedMessage acceptedMessage = acceptedMessage(response, number);
        if (acceptedMessage == null) {
            log.warn("ITNIO_SMS_REJECTED providerStatus={} success={} fail={} acceptedItems={} destination={}",
                    providerMetric(response == null ? null : response.status()),
                    providerMetric(response == null ? null : response.success()),
                    providerMetric(response == null ? null : response.fail()),
                    response == null || response.array() == null ? 0 : response.array().size(),
                    maskDestination(number));
            throw deliveryFailed(null);
        }
        log.info("ITNIO_SMS_ACCEPTED messageId={} destination={}",
                acceptedMessage.msgId(), maskDestination(number));
        return new DeliveryReceipt(acceptedMessage.msgId());
    }

    private ItnioAcceptedMessage acceptedMessage(ItnioSendResponse response, String number) {
        if (response == null
                || !"0".equals(response.status())
                || !"1".equals(response.success())
                || !"0".equals(response.fail())
                || response.array() == null) {
            return null;
        }
        return response.array().stream()
                .filter(item -> item != null
                        && StringUtils.hasText(item.msgId())
                        && number.equals(item.number()))
                .findFirst()
                .orElse(null);
    }

    private String maskDestination(String number) {
        return "****" + number.substring(number.length() - 4);
    }

    private String providerMetric(String value) {
        return value != null && value.matches("[+-]?[0-9]{1,6}") ? value : "invalid";
    }

    private boolean requiredConfigurationPresent() {
        return StringUtils.hasText(configuration.apiKey())
                && StringUtils.hasText(configuration.apiSecret())
                && StringUtils.hasText(configuration.appId());
    }

    private boolean validSenderId() {
        if (!StringUtils.hasText(configuration.senderId())) return true;
        String value = configuration.senderId().trim();
        return value.length() <= 32 && value.matches("[\\x20-\\x7E]+");
    }

    private static RestClient buildRestClient(
            RestClient.Builder builder, int connectTimeoutMs, int readTimeoutMs) {
        if (connectTimeoutMs < 100 || connectTimeoutMs > 30_000
                || readTimeoutMs < 100 || readTimeoutMs > 30_000) {
            throw new IllegalArgumentException("ITNIO_SMS_TIMEOUT_INVALID");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        return builder.requestFactory(requestFactory).build();
    }

    private IllegalStateException deliveryFailed(Throwable cause) {
        return cause == null
                ? new IllegalStateException("USER_OTP_DELIVERY_FAILED")
                : new IllegalStateException("USER_OTP_DELIVERY_FAILED", cause);
    }

    private record ItnioSendResponse(
            String status,
            String reason,
            String success,
            String fail,
            List<ItnioAcceptedMessage> array) {
    }

    private record ItnioAcceptedMessage(String msgId, String number, String orderId) {
    }

    record DeliveryReceipt(String messageId) {
    }

    private record ItnioConfiguration(
            boolean enabled,
            String apiKey,
            String apiSecret,
            String appId,
            String senderId) {
    }
}
