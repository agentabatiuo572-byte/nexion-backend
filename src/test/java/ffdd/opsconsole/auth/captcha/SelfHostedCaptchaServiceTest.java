package ffdd.opsconsole.auth.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class SelfHostedCaptchaServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void springCreatesTheProductionConstructorWithSharedRedisAndObjectMapperBeans() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(SpringFixture.class)) {
            assertThat(context.getBean(SelfHostedCaptchaService.class)).isNotNull();
        }
    }

    @Test
    void successfulChallengeVerifyAndTicketConsumptionUsesOpaquePngContract() throws Exception {
        Fixture fixture = new Fixture();
        ApiResult<SelfHostedCaptchaChallengeResponse> created = fixture.service.createChallenge(
                new SelfHostedCaptchaChallengeRequest("REGISTER"), "203.0.113.10");

        assertThat(created.getCode()).isZero();
        SelfHostedCaptchaChallengeResponse response = created.getData();
        assertThat(response.challengeId()).matches("[A-Za-z0-9_-]{43}");
        assertThat(response.backgroundImage()).startsWith("data:image/png;base64,");
        assertThat(response.pieceImage()).startsWith("data:image/png;base64,");
        assertThat(response.toString()).doesNotContain("targetX");

        int target = fixture.target(response.challengeId());
        fixture.challengeResult = fixture.values.get("captcha:v1:challenge:{" + response.challengeId() + "}:state");
        ApiResult<SelfHostedCaptchaVerifyResponse> verified = fixture.service.verifyChallenge(
                proof("REGISTER", response.challengeId(), target, "keyboard"), "203.0.113.10");

        assertThat(verified.getCode()).isZero();
        assertThat(verified.getData().ticket()).matches("[A-Za-z0-9_-]{43}");
        fixture.ticketResult = "OK";
        assertThat(fixture.service.consumeTicket(CaptchaScene.REGISTER, verified.getData().ticket(), "203.0.113.10").passed())
                .isTrue();
    }

    @Test
    void failedProofConsumesChallengeAndASecondAttemptIsAReplay() throws Exception {
        Fixture fixture = new Fixture();
        SelfHostedCaptchaChallengeResponse challenge = fixture.service.createChallenge(
                new SelfHostedCaptchaChallengeRequest("LOGIN"), "203.0.113.11").getData();
        fixture.challengeResult = fixture.values.get("captcha:v1:challenge:{" + challenge.challengeId() + "}:state");
        int wrong = Math.min(SelfHostedCaptchaService.WIDTH - SelfHostedCaptchaService.PIECE_SIZE,
                fixture.target(challenge.challengeId()) + 20);

        assertThat(fixture.service.verifyChallenge(proof("LOGIN", challenge.challengeId(), wrong, "pointer"), "203.0.113.11")
                .getMessage()).isEqualTo("USER_CAPTCHA_CHALLENGE_FAILED");
        fixture.challengeResult = "__REPLAY__";
        assertThat(fixture.service.verifyChallenge(proof("LOGIN", challenge.challengeId(), wrong, "pointer"), "203.0.113.11")
                .getMessage()).isEqualTo("USER_CAPTCHA_CHALLENGE_REPLAYED");
    }

    @Test
    void sceneIpExpiryAndTicketReplayAreNeverCrossAccepted() {
        Fixture fixture = new Fixture();
        fixture.challengeResult = "__IP_MISMATCH__";
        assertThat(fixture.service.verifyChallenge(proof("REGISTER", opaque(), 0, "keyboard"), "203.0.113.12")
                .getMessage()).isEqualTo("USER_CAPTCHA_CHALLENGE_IP_MISMATCH");

        fixture.challengeResult = "__EXPIRED__";
        assertThat(fixture.service.verifyChallenge(proof("RESET", opaque(), 0, "keyboard"), "203.0.113.12")
                .getMessage()).isEqualTo("USER_CAPTCHA_CHALLENGE_EXPIRED");

        fixture.ticketResult = "__IP_MISMATCH__";
        assertThat(fixture.service.consumeTicket(CaptchaScene.LOGIN, opaque(), "203.0.113.12").code())
                .isEqualTo("USER_CAPTCHA_TICKET_IP_MISMATCH");
        fixture.ticketResult = "__REPLAY__";
        assertThat(fixture.service.consumeTicket(CaptchaScene.LOGIN, opaque(), "203.0.113.12").code())
                .isEqualTo("USER_CAPTCHA_TICKET_REPLAYED");
    }

    @Test
    void aChallengeCannotCrossScenesAndRateLimitCannotBeBypassedByRequestingAnotherImage() throws Exception {
        Fixture fixture = new Fixture();
        SelfHostedCaptchaChallengeResponse challenge = fixture.service.createChallenge(
                new SelfHostedCaptchaChallengeRequest("REGISTER"), "203.0.113.14").getData();
        fixture.challengeResult = fixture.values.get("captcha:v1:challenge:{" + challenge.challengeId() + "}:state");
        assertThat(fixture.service.verifyChallenge(proof("LOGIN", challenge.challengeId(), fixture.target(challenge.challengeId()), "pointer"),
                "203.0.113.14").getMessage()).isEqualTo("USER_CAPTCHA_CHALLENGE_SCENE_MISMATCH");

        Fixture limited = new Fixture();
        limited.rateLimited = true;
        assertThat(limited.service.createChallenge(new SelfHostedCaptchaChallengeRequest("REGISTER"), "203.0.113.14")
                .getMessage()).isEqualTo("USER_CAPTCHA_CHALLENGE_RATE_LIMITED");
        assertThat(limited.values).isEmpty();
    }

    @Test
    void concurrentTicketConsumersGetAtMostOnePassFromTheAtomicRedisScript() throws Exception {
        Fixture fixture = new Fixture();
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        fixture.ticketSupplier = () -> first.compareAndSet(true, false) ? "OK" : "__REPLAY__";
        var pool = Executors.newFixedThreadPool(2);
        try {
            var results = pool.invokeAll(List.of(
                    (Callable<CaptchaTicketVerification>) () -> fixture.service.consumeTicket(CaptchaScene.RESET, opaque(), "203.0.113.15"),
                    (Callable<CaptchaTicketVerification>) () -> fixture.service.consumeTicket(CaptchaScene.RESET, opaque(), "203.0.113.15")));
            assertThat(results.stream().filter(result -> {
                try { return result.get().passed(); } catch (Exception exception) { throw new AssertionError(exception); }
            })).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void malformedOrOverlongTrailDoesNotIssueTicketAndRedisFailureFailsClosed() {
        Fixture fixture = new Fixture();
        assertThat(fixture.service.verifyChallenge(new SelfHostedCaptchaVerifyRequest("REGISTER", opaque(), 1,
                List.of(new SelfHostedCaptchaTrailPoint(1, 30_001L)), "keyboard"), "203.0.113.13").getMessage())
                .isEqualTo("USER_CAPTCHA_CHALLENGE_INVALID");
        fixture.redisFailure = true;
        assertThat(fixture.service.createChallenge(new SelfHostedCaptchaChallengeRequest("REGISTER"), "203.0.113.13")
                .getMessage()).isEqualTo("USER_CAPTCHA_VERIFIER_UNAVAILABLE");
        assertThat(fixture.values).isEmpty();
    }

    private SelfHostedCaptchaVerifyRequest proof(String scene, String id, int offset, String inputMethod) {
        return new SelfHostedCaptchaVerifyRequest(scene, id, offset,
                List.of(new SelfHostedCaptchaTrailPoint(offset, 0L)), inputMethod);
    }

    private String opaque() {
        return "A".repeat(43);
    }

    private final class Fixture {
        final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        final ValueOperations<String, String> operations = mock(ValueOperations.class);
        final Map<String, String> values = new HashMap<>();
        final SelfHostedCaptchaService service = new SelfHostedCaptchaService(redis, json,
                Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC), new java.security.SecureRandom());
        String challengeResult = "__MISSING__";
        String ticketResult = "__MISSING__";
        java.util.function.Supplier<String> ticketSupplier;
        boolean redisFailure;
        boolean rateLimited;

        Fixture() {
            when(redis.opsForValue()).thenReturn(operations);
            doAnswer(invocation -> {
                values.put(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(operations).set(anyString(), anyString(), any());
            when(redis.execute(any(), anyList(), anyString(), anyString())).thenAnswer(invocation -> {
                if (redisFailure) throw new IllegalStateException("redis unavailable");
                String script = ((org.springframework.data.redis.core.script.DefaultRedisScript<?>) invocation.getArgument(0))
                        .getScriptAsString();
                if (script.contains("INCR")) return rateLimited ? 0L : 1L;
                if (script.contains("CONSUMED") && script.contains("string.sub")) return challengeResult;
                return ticketSupplier == null ? ticketResult : ticketSupplier.get();
            });
        }

        int target(String challengeId) throws Exception {
            String stored = values.get("captcha:v1:challenge:{" + challengeId + "}:state");
            JsonNode state = json.readTree(stored.substring(stored.indexOf('\n') + 1));
            return state.get("targetX").asInt();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SelfHostedCaptchaService.class)
    static class SpringFixture {
        @Bean StringRedisTemplate redisTemplate() { return mock(StringRedisTemplate.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
}
