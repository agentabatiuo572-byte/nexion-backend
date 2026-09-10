package ffdd.opsconsole.auth.captcha;

/** Data URLs are generated server-side PNGs; targetX intentionally is not part of this contract. */
public record SelfHostedCaptchaChallengeResponse(
        String challengeId,
        String backgroundImage,
        String pieceImage,
        int width,
        int height,
        int pieceWidth,
        int pieceHeight,
        int pieceY,
        int expiresInSec) { }
