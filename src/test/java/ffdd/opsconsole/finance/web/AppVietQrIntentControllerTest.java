package ffdd.opsconsole.finance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.hdpay.HdPayHostedDepositService;
import ffdd.opsconsole.finance.dto.AppVietQrIntentCreateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.GatewaySecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.TestingAuthenticationToken;

class AppVietQrIntentControllerTest {
    private final HdPayHostedDepositService service = mock(HdPayHostedDepositService.class);
    private final GatewaySecurityProperties gatewaySecurity = new GatewaySecurityProperties();
    private final AppVietQrIntentController controller =
            new AppVietQrIntentController(service, gatewaySecurity);
    private final ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AppVietQrIntentController.class);
    private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
            new ch.qos.logback.core.read.ListAppender<>();
    private ch.qos.logback.classic.Level previousLevel;

    @BeforeEach
    void captureDiagnostics() {
        previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.WARN);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void restoreDiagnostics() {
        logger.detachAppender(logs);
        logs.stop();
        logger.setLevel(previousLevel);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HDPAY_CONFIGURATION_INCOMPLETE", "FX_QUOTE_UNAVAILABLE", "HDPAY_ORDER_SUBMISSION_UNKNOWN"})
    void create503RecordsOnlyItsSafeReasonAndRethrowsWithoutRetry(String reason) {
        BizException failure = new BizException(503, reason);
        when(service.create(41L, "app-vietqr:create:fixture-key", new BigDecimal("25"), "203.0.113.9"))
                .thenThrow(failure);

        assertThatThrownBy(() -> invokeCreate("POST", "/api/app/deposits/vietqr/intents"))
                .isSameAs(failure);

        assertThat(logs.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .containsExactly("event=VIETQR_INTENT_CREATE_UNAVAILABLE phase=CONTROLLER code=" + reason);
        assertThat(logs.list).singleElement().satisfies(event -> assertThat(event.getThrowableProxy()).isNull());
        verify(service).create(41L, "app-vietqr:create:fixture-key", new BigDecimal("25"), "203.0.113.9");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"fixture-secret merchant=123456 token=synthetic https://private.invalid", "UNRECOGNIZED_UPPERCASE_TOKEN"})
    void create503DoesNotLogAnUnlistedOrNullExceptionMessage(String reason) {
        BizException failure = new BizException(503, reason);
        when(service.create(41L, "app-vietqr:create:fixture-key", new BigDecimal("25"), "203.0.113.9"))
                .thenThrow(failure);

        assertThatThrownBy(() -> invokeCreate("POST", "/api/app/deposits/vietqr/intents"))
                .isSameAs(failure);

        assertThat(logs.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .containsExactly("event=VIETQR_INTENT_CREATE_UNAVAILABLE phase=CONTROLLER code=UNCLASSIFIED_503");
        assertThat(logs.list).singleElement().satisfies(event -> assertThat(event.getThrowableProxy()).isNull());
        verify(service).create(41L, "app-vietqr:create:fixture-key", new BigDecimal("25"), "203.0.113.9");
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/app/deposits/vietqr/intents", "POST,/api/app/deposits/vietqr/intents/other"})
    void createDiagnosticIsBoundToExactPostRoute(String method, String path) {
        BizException failure = new BizException(503, "HDPAY_CONFIGURATION_INCOMPLETE");
        when(service.create(41L, "app-vietqr:create:fixture-key", new BigDecimal("25"), "203.0.113.9"))
                .thenThrow(failure);
        assertThatThrownBy(() -> invokeCreate(method, path)).isSameAs(failure);
        assertThat(logs.list).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {409, 422})
    void settledOrConflictFailuresKeepTheirOriginalContractWithout503Diagnostic(int status) {
        BizException failure = new BizException(status, "HDPAY_ORDER_CREATE_REJECTED");
        when(service.create(41L, "app-vietqr:create:fixture-key", new BigDecimal("25"), "203.0.113.9"))
                .thenThrow(failure);
        assertThatThrownBy(() -> invokeCreate("POST", "/api/app/deposits/vietqr/intents")).isSameAs(failure);
        assertThat(logs.list).isEmpty();
    }

    private ApiResult<Map<String, Object>> invokeCreate(String method, String path) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken("41", "ignored", List.of());
        authentication.setDetails(Map.of("subjectType", "USER"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getRemoteAddr()).thenReturn("203.0.113.9");
        return controller.create("app-vietqr:create:fixture-key",
                new AppVietQrIntentCreateRequest(new BigDecimal("25")), authentication, request);
    }

    @Test
    void authenticatedUserSubjectOwnsCreateRequestIdentity() {
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken("41", "ignored", List.of());
        authentication.setDetails(Map.of("subjectType", "USER"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.9");
        when(service.create(41L, "app-vietqr:create:key-1", new BigDecimal("25"), "203.0.113.9"))
                .thenReturn(ApiResult.ok(Map.of("intentNo", "VQR-1")));

        ApiResult<Map<String, Object>> result = controller.create(
                "app-vietqr:create:key-1",
                new AppVietQrIntentCreateRequest(new BigDecimal("25")),
                authentication,
                request);

        assertThat(result.getData()).containsEntry("intentNo", "VQR-1");
        verify(service).create(41L, "app-vietqr:create:key-1", new BigDecimal("25"), "203.0.113.9");
    }

    @Test
    void adminSubjectCannotUseAppIntentEndpoints() {
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken("1", "ignored", List.of());
        authentication.setDetails(Map.of("subjectType", "ADMIN"));

        ApiResult<Map<String, Object>> result = controller.create(
                "app-vietqr:create:key-2",
                new AppVietQrIntentCreateRequest(new BigDecimal("25")),
                authentication,
                null);

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_SUBJECT_REQUIRED");
        verify(service, never()).create(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
