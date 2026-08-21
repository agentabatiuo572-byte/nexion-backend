package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.dto.UserOAuthSandboxChallengeRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class OAuthSandboxChallengeServiceTest {
    private static final String LOOPBACK = "127.0.0.1";
    private static final String LOCAL_ORIGIN = "http://127.0.0.1:5173";

    @Test
    void sandboxChallengeIsServerIssuedProviderBoundAndOneTime() {
        Environment environment = sandboxEnvironment();
        OAuthSandboxChallengeService service = new OAuthSandboxChallengeService(environment);

        var issued = service.issue(new UserOAuthSandboxChallengeRequest("google"), LOOPBACK, LOCAL_ORIGIN);

        assertThat(issued.getCode()).isZero();
        assertThat(issued.getData().challengeNo()).matches("OAUTH-[a-f0-9]{32}");
        assertThat(service.consume("APPLE", issued.getData().challengeNo())).isEmpty();
        var subject = service.consume("GOOGLE", issued.getData().challengeNo());
        assertThat(subject).hasValueSatisfying(value -> assertThat(value).startsWith("sandbox-"));
        assertThat(service.consume("GOOGLE", issued.getData().challengeNo())).isEmpty();
    }

    @Test
    void passkeyChallengesKeepOneTimeNoncesButReuseOneDevelopmentSubject() {
        OAuthSandboxChallengeService service = new OAuthSandboxChallengeService(sandboxEnvironment());

        var first = service.issue(new UserOAuthSandboxChallengeRequest("PASSKEY"), LOOPBACK, LOCAL_ORIGIN);
        var second = service.issue(new UserOAuthSandboxChallengeRequest("PASSKEY"), LOOPBACK, LOCAL_ORIGIN);

        assertThat(first.getData().challengeNo()).isNotEqualTo(second.getData().challengeNo());
        assertThat(service.consume("PASSKEY", first.getData().challengeNo()))
                .contains("development-passkey-fixed-account");
        assertThat(service.consume("PASSKEY", second.getData().challengeNo()))
                .contains("development-passkey-fixed-account");
    }

    @Test
    void passkeyChallengeRejectsANonLocalDevelopmentCaller() {
        OAuthSandboxChallengeService service = new OAuthSandboxChallengeService(sandboxEnvironment());

        var result = service.issue(new UserOAuthSandboxChallengeRequest("PASSKEY"),
                "192.168.1.20", "http://192.168.1.20:5173");

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("OAUTH_DEVELOPMENT_PASSKEY_LOCAL_ONLY");
    }

    @Test
    void everySandboxProviderRejectsANonLocalDevelopmentCaller() {
        OAuthSandboxChallengeService service = new OAuthSandboxChallengeService(sandboxEnvironment());

        for (String provider : new String[] {"GOOGLE", "APPLE", "TELEGRAM"}) {
            var result = service.issue(new UserOAuthSandboxChallengeRequest(provider),
                    "192.168.1.20", "http://192.168.1.20:5173");

            assertThat(result.getCode()).isEqualTo(403);
            assertThat(result.getMessage()).isEqualTo("OAUTH_DEVELOPMENT_PASSKEY_LOCAL_ONLY");
        }
    }

    @Test
    void passkeyChallengeFailsClosedWhenForwardedHeaderRewritingIsEnabled() {
        Environment environment = sandboxEnvironment();
        when(environment.getProperty("server.forward-headers-strategy")).thenReturn("native");
        OAuthSandboxChallengeService service = new OAuthSandboxChallengeService(environment);

        var result = service.issue(
                new UserOAuthSandboxChallengeRequest("PASSKEY"), LOOPBACK, LOCAL_ORIGIN);

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("OAUTH_DEVELOPMENT_PASSKEY_NETWORK_POLICY_INVALID");
    }

    @Test
    void productionAndMixedProfilesCannotIssueSandboxChallenges() {
        for (String[] profiles : new String[][] {{"prod"}, {"prod", "dev"}}) {
            Environment environment = mock(Environment.class);
            when(environment.getActiveProfiles()).thenReturn(profiles);
            OAuthSandboxChallengeService service = new OAuthSandboxChallengeService(environment);

            var issued = service.issue(new UserOAuthSandboxChallengeRequest("GOOGLE"), LOOPBACK, LOCAL_ORIGIN);

            assertThat(issued.getCode()).isEqualTo(503);
            assertThat(issued.getMessage()).isEqualTo("OAUTH_SANDBOX_CHALLENGE_FORBIDDEN");
        }
    }

    @Test
    void challengeExpiresAtTheFiveMinuteBoundary() {
        Environment environment = sandboxEnvironment();
        MutableClock clock = new MutableClock(Instant.parse("2026-08-18T00:00:00Z"));
        OAuthSandboxChallengeService service = new OAuthSandboxChallengeService(environment, clock, 10);
        var issued = service.issue(new UserOAuthSandboxChallengeRequest("GOOGLE"), LOOPBACK, LOCAL_ORIGIN);

        clock.set(Instant.parse("2026-08-18T00:04:59.999Z"));
        assertThat(service.consume("GOOGLE", issued.getData().challengeNo())).isPresent();
        var expiring = service.issue(new UserOAuthSandboxChallengeRequest("GOOGLE"), LOOPBACK, LOCAL_ORIGIN);
        clock.set(Instant.parse("2026-08-18T00:09:59.999Z"));
        assertThat(service.consume("GOOGLE", expiring.getData().challengeNo())).isEmpty();
    }

    @Test
    void concurrentIssueCannotExceedCapacityAndConcurrentConsumeHasOneWinner() throws Exception {
        OAuthSandboxChallengeService service = new OAuthSandboxChallengeService(
                sandboxEnvironment(), Clock.systemUTC(), 3);
        var issuePool = Executors.newFixedThreadPool(12);
        try {
            ArrayList<Callable<Integer>> issues = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                issues.add(() -> service.issue(
                        new UserOAuthSandboxChallengeRequest("TELEGRAM"), LOOPBACK, LOCAL_ORIGIN).getCode());
            }
            var results = issuePool.invokeAll(issues).stream().map(future -> {
                try { return future.get(); } catch (Exception exception) { throw new AssertionError(exception); }
            }).toList();
            assertThat(results.stream().filter(code -> code == 0).count()).isEqualTo(3);
            assertThat(results.stream().filter(code -> code == 429).count()).isEqualTo(9);
        } finally {
            issuePool.shutdownNow();
        }

        OAuthSandboxChallengeService oneTime = new OAuthSandboxChallengeService(
                sandboxEnvironment(), Clock.systemUTC(), 10);
        var challenge = oneTime.issue(
                new UserOAuthSandboxChallengeRequest("PASSKEY"), LOOPBACK, LOCAL_ORIGIN)
                .getData().challengeNo();
        var consumePool = Executors.newFixedThreadPool(8);
        try {
            ArrayList<Callable<Boolean>> consumes = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                consumes.add(() -> oneTime.consume("PASSKEY", challenge).isPresent());
            }
            assertThat(consumePool.invokeAll(consumes).stream().filter(future -> {
                try { return future.get(); } catch (Exception exception) { throw new AssertionError(exception); }
            }).count()).isEqualTo(1);
        } finally {
            consumePool.shutdownNow();
        }
    }

    private static Environment sandboxEnvironment() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
        when(environment.getProperty("server.forward-headers-strategy")).thenReturn("none");
        return environment;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void set(Instant value) { instant = value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
