package ffdd.opsconsole.auth.captcha;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * First-party slider challenge authority. Redis holds every answer and ticket so
 * a multi-node deployment has one bounded, fail-closed source of truth.
 */
@Service
public class SelfHostedCaptchaService {
    static final int WIDTH = 320;
    static final int HEIGHT = 160;
    static final int PIECE_SIZE = 48;
    static final int CHALLENGE_TTL_SECONDS = 120;
    static final int TICKET_TTL_SECONDS = 300;
    private static final int STATUS_TTL_SECONDS = 180;
    private static final int CHALLENGES_PER_WINDOW = 12;
    private static final int TICKETS_PER_WINDOW = 8;
    private static final int RATE_WINDOW_SECONDS = 600;
    private static final int POSITION_TOLERANCE = 5;
    private static final int MAX_TRAIL_POINTS = 128;
    private static final long MAX_TRAIL_MILLIS = 30_000L;
    private static final String PREFIX = "captcha:v1:";
    private static final String IP_MISMATCH = "__IP_MISMATCH__";
    private static final String MISSING = "__MISSING__";
    private static final String REPLAY = "__REPLAY__";
    private static final String EXPIRED = "__EXPIRED__";

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
            if count > tonumber(ARGV[1]) then return 0 end
            return count
            """, Long.class);
    private static final DefaultRedisScript<String> TAKE_CHALLENGE_SCRIPT = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value then
              local status = redis.call('GET', KEYS[2])
              if status == 'CONSUMED' then return '__REPLAY__' end
              if status == 'ACTIVE' then return '__EXPIRED__' end
              return '__MISSING__'
            end
            if string.sub(value, 1, string.len(ARGV[1]) + 1) ~= ARGV[1] .. string.char(10) then return '__IP_MISMATCH__' end
            redis.call('DEL', KEYS[1])
            redis.call('SET', KEYS[2], 'CONSUMED', 'EX', ARGV[2])
            return value
            """, String.class);
    private static final DefaultRedisScript<String> TAKE_TICKET_SCRIPT = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value then
              local status = redis.call('GET', KEYS[2])
              if status == 'CONSUMED' then return '__REPLAY__' end
              if status == 'ACTIVE' then return '__EXPIRED__' end
              return '__MISSING__'
            end
            if value ~= ARGV[1] then return '__IP_MISMATCH__' end
            redis.call('DEL', KEYS[1])
            redis.call('SET', KEYS[2], 'CONSUMED', 'EX', ARGV[2])
            return 'OK'
            """, String.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom random;

    @Autowired
    public SelfHostedCaptchaService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this(redis, objectMapper, Clock.systemUTC(), new SecureRandom());
    }

    SelfHostedCaptchaService(StringRedisTemplate redis, ObjectMapper objectMapper, Clock clock, SecureRandom random) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.random = random;
    }

    public ApiResult<SelfHostedCaptchaChallengeResponse> createChallenge(
            SelfHostedCaptchaChallengeRequest request, String clientAddress) {
        CaptchaScene scene = scene(request == null ? null : request.scene());
        if (scene == null) return ApiResult.fail(422, "USER_CAPTCHA_SCENE_INVALID");
        String ipHash = ipHash(clientAddress);
        if (ipHash == null) return ApiResult.fail(422, "USER_CAPTCHA_CLIENT_ADDRESS_INVALID");
        RateDecision rate = withinRate("challenge", scene, ipHash, CHALLENGES_PER_WINDOW);
        if (rate != RateDecision.ALLOWED) return rate == RateDecision.UNAVAILABLE
                ? ApiResult.fail(503, "USER_CAPTCHA_VERIFIER_UNAVAILABLE")
                : ApiResult.fail(429, "USER_CAPTCHA_CHALLENGE_RATE_LIMITED");
        try {
            int targetX = 28 + random.nextInt(WIDTH - PIECE_SIZE - 56);
            int pieceY = 28 + random.nextInt(HEIGHT - PIECE_SIZE - 56);
            PuzzleImages images = drawPuzzle(targetX, pieceY);
            String challengeId = opaqueId();
            StoredChallenge stored = new StoredChallenge(scene.name(), targetX, pieceY,
                    clock.millis() + CHALLENGE_TTL_SECONDS * 1000L);
            String value = ipHash + "\n" + objectMapper.writeValueAsString(stored);
            storeWithStatus(challengeKey(challengeId), challengeStatusKey(challengeId), value, CHALLENGE_TTL_SECONDS);
            return ApiResult.ok(new SelfHostedCaptchaChallengeResponse(
                    challengeId, images.background(), images.piece(), WIDTH, HEIGHT, PIECE_SIZE, PIECE_SIZE,
                    pieceY, CHALLENGE_TTL_SECONDS));
        } catch (RuntimeException | JsonProcessingException exception) {
            return ApiResult.fail(503, "USER_CAPTCHA_VERIFIER_UNAVAILABLE");
        }
    }

    public ApiResult<SelfHostedCaptchaVerifyResponse> verifyChallenge(
            SelfHostedCaptchaVerifyRequest request, String clientAddress) {
        CaptchaScene requestedScene = scene(request == null ? null : request.scene());
        if (requestedScene == null || !validChallengeId(request == null ? null : request.challengeId())
                || !validProof(request)) {
            return ApiResult.fail(422, "USER_CAPTCHA_CHALLENGE_INVALID");
        }
        String ipHash = ipHash(clientAddress);
        if (ipHash == null) return ApiResult.fail(422, "USER_CAPTCHA_CLIENT_ADDRESS_INVALID");
        String stored;
        try {
            stored = redis.execute(TAKE_CHALLENGE_SCRIPT,
                    List.of(challengeKey(request.challengeId()), challengeStatusKey(request.challengeId())),
                    ipHash, String.valueOf(STATUS_TTL_SECONDS));
        } catch (RuntimeException exception) {
            return ApiResult.fail(503, "USER_CAPTCHA_VERIFIER_UNAVAILABLE");
        }
        if (IP_MISMATCH.equals(stored)) return ApiResult.fail(422, "USER_CAPTCHA_CHALLENGE_IP_MISMATCH");
        if (REPLAY.equals(stored)) return ApiResult.fail(422, "USER_CAPTCHA_CHALLENGE_REPLAYED");
        if (EXPIRED.equals(stored)) return ApiResult.fail(422, "USER_CAPTCHA_CHALLENGE_EXPIRED");
        if (MISSING.equals(stored) || stored == null) return ApiResult.fail(422, "USER_CAPTCHA_CHALLENGE_INVALID");
        StoredChallenge challenge;
        try {
            int separator = stored.indexOf('\n');
            if (separator != 64 || !ipHash.equals(stored.substring(0, separator))) {
                return ApiResult.fail(422, "USER_CAPTCHA_CHALLENGE_INVALID");
            }
            challenge = objectMapper.readValue(stored.substring(separator + 1), StoredChallenge.class);
        } catch (RuntimeException | JsonProcessingException exception) {
            return ApiResult.fail(503, "USER_CAPTCHA_VERIFIER_UNAVAILABLE");
        }
        if (!requestedScene.name().equals(challenge.scene()) || challenge.expiresAtEpochMs() <= clock.millis()) {
            return ApiResult.fail(422, !requestedScene.name().equals(challenge.scene())
                    ? "USER_CAPTCHA_CHALLENGE_SCENE_MISMATCH" : "USER_CAPTCHA_CHALLENGE_EXPIRED");
        }
        if (Math.abs(request.offsetX() - challenge.targetX()) > POSITION_TOLERANCE) {
            return ApiResult.fail(422, "USER_CAPTCHA_CHALLENGE_FAILED");
        }
        RateDecision ticketRate = withinRate("ticket", requestedScene, ipHash, TICKETS_PER_WINDOW);
        if (ticketRate != RateDecision.ALLOWED) return ticketRate == RateDecision.UNAVAILABLE
                ? ApiResult.fail(503, "USER_CAPTCHA_VERIFIER_UNAVAILABLE")
                : ApiResult.fail(429, "USER_CAPTCHA_TICKET_RATE_LIMITED");
        try {
            String ticket = opaqueId();
            storeWithStatus(ticketKey(requestedScene, ticket), ticketStatusKey(requestedScene, ticket),
                    ipHash, TICKET_TTL_SECONDS);
            return ApiResult.ok(new SelfHostedCaptchaVerifyResponse(ticket, TICKET_TTL_SECONDS));
        } catch (RuntimeException exception) {
            return ApiResult.fail(503, "USER_CAPTCHA_VERIFIER_UNAVAILABLE");
        }
    }

    CaptchaTicketVerification consumeTicket(CaptchaScene scene, String ticket, String clientAddress) {
        if (scene == null || !validTicket(ticket) || ipHash(clientAddress) == null) {
            return CaptchaTicketVerification.reject("USER_CAPTCHA_TICKET_INVALID");
        }
        String result;
        try {
            result = redis.execute(TAKE_TICKET_SCRIPT,
                    List.of(ticketKey(scene, ticket), ticketStatusKey(scene, ticket)),
                    ipHash(clientAddress), String.valueOf(STATUS_TTL_SECONDS));
        } catch (RuntimeException exception) {
            return CaptchaTicketVerification.reject("USER_CAPTCHA_VERIFIER_UNAVAILABLE");
        }
        if ("OK".equals(result)) return CaptchaTicketVerification.pass();
        if (REPLAY.equals(result)) return CaptchaTicketVerification.reject("USER_CAPTCHA_TICKET_REPLAYED");
        if (EXPIRED.equals(result)) return CaptchaTicketVerification.reject("USER_CAPTCHA_TICKET_EXPIRED");
        if (IP_MISMATCH.equals(result)) return CaptchaTicketVerification.reject("USER_CAPTCHA_TICKET_IP_MISMATCH");
        return CaptchaTicketVerification.reject("USER_CAPTCHA_TICKET_INVALID");
    }

    private boolean validProof(SelfHostedCaptchaVerifyRequest request) {
        if (request == null || request.offsetX() == null || request.offsetX() < 0
                || request.offsetX() > WIDTH - PIECE_SIZE || !validInputMethod(request.inputMethod())
                || request.trail() == null || request.trail().isEmpty() || request.trail().size() > MAX_TRAIL_POINTS) {
            return false;
        }
        long previousTime = -1;
        for (SelfHostedCaptchaTrailPoint point : request.trail()) {
            if (point == null || point.x() == null || point.t() == null || point.x() < 0
                    || point.x() > WIDTH - PIECE_SIZE || point.t() < 0 || point.t() > MAX_TRAIL_MILLIS
                    || point.t() < previousTime) return false;
            previousTime = point.t();
        }
        return request.trail().get(request.trail().size() - 1).x().equals(request.offsetX());
    }

    private boolean validInputMethod(String value) {
        return "pointer".equals(value) || "keyboard".equals(value);
    }

    private RateDecision withinRate(String action, CaptchaScene scene, String ipHash, int maximum) {
        try {
            Long accepted = redis.execute(RATE_LIMIT_SCRIPT, List.of(PREFIX + "rate:" + action + ":"
                    + scene.name().toLowerCase() + ":" + ipHash), String.valueOf(maximum), String.valueOf(RATE_WINDOW_SECONDS));
            return accepted == null ? RateDecision.UNAVAILABLE
                    : accepted > 0 ? RateDecision.ALLOWED : RateDecision.LIMITED;
        } catch (RuntimeException exception) {
            return RateDecision.UNAVAILABLE;
        }
    }

    private void storeWithStatus(String valueKey, String statusKey, String value, int ttlSeconds) {
        try {
            redis.opsForValue().set(valueKey, value, Duration.ofSeconds(ttlSeconds));
            redis.opsForValue().set(statusKey, "ACTIVE", Duration.ofSeconds(ttlSeconds + STATUS_TTL_SECONDS));
        } catch (RuntimeException exception) {
            try {
                redis.delete(List.of(valueKey, statusKey));
            } catch (RuntimeException ignored) {
                // A Redis fault means issuance fails closed. Keys still have finite TTL if cleanup also fails.
            }
            throw exception;
        }
    }

    private PuzzleImages drawPuzzle(int targetX, int targetY) {
        try {
            BufferedImage background = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = background.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setPaint(new GradientPaint(0, 0, new Color(34, 91, 154), WIDTH, HEIGHT, new Color(96, 178, 121)));
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            for (int index = 0; index < 24; index++) {
                graphics.setColor(new Color(255, 255, 255, 18 + random.nextInt(36)));
                int radius = 8 + random.nextInt(30);
                graphics.fill(new Ellipse2D.Double(random.nextInt(WIDTH), random.nextInt(HEIGHT), radius, radius));
            }
            for (int index = 0; index < 8; index++) {
                graphics.setColor(new Color(10, 45, 85, 35));
                graphics.setStroke(new BasicStroke(1 + random.nextInt(3)));
                graphics.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT), random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }
            BufferedImage original = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
            Graphics2D originalGraphics = original.createGraphics();
            originalGraphics.drawImage(background, 0, 0, null);
            originalGraphics.dispose();
            Area shape = pieceShape(0, 0);
            Graphics2D hole = (Graphics2D) graphics.create();
            hole.translate(targetX, targetY);
            hole.setComposite(AlphaComposite.SrcOver);
            hole.setColor(new Color(20, 40, 60, 118));
            hole.fill(shape);
            hole.setColor(new Color(255, 255, 255, 150));
            hole.setStroke(new BasicStroke(2));
            hole.draw(shape);
            hole.dispose();
            graphics.dispose();

            BufferedImage piece = new BufferedImage(PIECE_SIZE, PIECE_SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D pieceGraphics = piece.createGraphics();
            pieceGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            pieceGraphics.setClip(shape);
            pieceGraphics.drawImage(original.getSubimage(targetX, targetY, PIECE_SIZE, PIECE_SIZE), 0, 0, null);
            pieceGraphics.setClip(null);
            pieceGraphics.setColor(new Color(255, 255, 255, 190));
            pieceGraphics.setStroke(new BasicStroke(2));
            pieceGraphics.draw(shape);
            pieceGraphics.dispose();
            return new PuzzleImages(dataUrl(background), dataUrl(piece));
        } catch (RuntimeException exception) {
            throw exception;
        }
    }

    private Area pieceShape(int x, int y) {
        Area shape = new Area(new Rectangle2D.Double(x + 6, y + 6, PIECE_SIZE - 12, PIECE_SIZE - 12));
        shape.add(new Area(new Ellipse2D.Double(x + PIECE_SIZE - 10, y + PIECE_SIZE / 2 - 6, 12, 12)));
        shape.subtract(new Area(new Ellipse2D.Double(x + PIECE_SIZE / 2 - 6, y - 2, 12, 12)));
        return shape;
    }

    private String dataUrl(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG_WRITER_UNAVAILABLE");
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("PNG_WRITE_FAILED", exception);
        }
    }

    private String opaqueId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String ipHash(String clientAddress) {
        if (!StringUtils.hasText(clientAddress) || clientAddress.length() > 128) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(clientAddress.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            return null;
        }
    }

    private CaptchaScene scene(String value) {
        if (value == null) return null;
        try {
            return CaptchaScene.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean validChallengeId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{43}");
    }

    private boolean validTicket(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{43}");
    }

    // State/status pairs participate in one Lua operation. The shared hash tag
    // keeps each pair in a single Redis Cluster slot as well as standalone Redis.
    private String challengeKey(String id) { return PREFIX + "challenge:{" + id + "}:state"; }
    private String challengeStatusKey(String id) { return PREFIX + "challenge:{" + id + "}:status"; }
    private String ticketKey(CaptchaScene scene, String ticket) {
        return PREFIX + "ticket:{" + scene.name().toLowerCase() + ":" + ticket + "}:state";
    }
    private String ticketStatusKey(CaptchaScene scene, String ticket) {
        return PREFIX + "ticket:{" + scene.name().toLowerCase() + ":" + ticket + "}:status";
    }

    private record StoredChallenge(String scene, int targetX, int pieceY, long expiresAtEpochMs) { }
    private record PuzzleImages(String background, String piece) { }
    private enum RateDecision { ALLOWED, LIMITED, UNAVAILABLE }
}
