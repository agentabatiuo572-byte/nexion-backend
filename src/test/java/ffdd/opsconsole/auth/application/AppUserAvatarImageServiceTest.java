package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.mapper.AppUserProfileMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import ffdd.opsconsole.shared.storage.StorageProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AppUserAvatarImageServiceTest {
    private static final String KEY = "users/42/avatar/a76f323e5177470f86471a42bb8fcb62.png";
    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aS9sAAAAASUVORK5CYII=");
    private final AppUserProfileMapper mapper = mock(AppUserProfileMapper.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final StorageProperties properties = properties();
    private final AppUserAvatarImageService service = serviceAt(NOW);

    private StorageProperties properties() {
        StorageProperties result = new StorageProperties();
        result.setPublicMediaOrigin("https://avatar-test.example/");
        result.setSecretKey("test-only-avatar-signing-secret");
        return result;
    }

    private AppUserAvatarImageService serviceAt(Instant instant) {
        return new AppUserAvatarImageService(mapper, storage, properties,
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void currentOwnerCanReadExactImageWithTheExistingTwentyFourHourLifetime() throws Exception {
        String url = service.issueUrl(42L, KEY);
        Token token = token(url);
        assertThat(url).startsWith("https://avatar-test.example/api/app/profile/avatar/image/42/")
                .doesNotContain("127.0.0.1", ":9000");
        assertThat(Long.parseLong(token.expires())).isEqualTo(NOW.getEpochSecond() + 86400);
        when(mapper.profile(42L)).thenReturn(Map.of("avatarObjectKey", KEY));
        var stream = new TrackingStream(PNG);
        when(storage.get(KEY)).thenReturn(stream);

        var image = read(serviceAt(NOW.plusSeconds(86399)), token);

        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.bytes()).isEqualTo(PNG);
        assertThat(stream.closed).isTrue();
        verify(mapper).profile(42L);
        verify(storage).get(KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "revision", "asset", "expires", "signature", "missingSignature",
            "nonCanonicalOwner", "nonCanonicalExpiry", "paddedAsset", "paddedSignature", "overflowOwner"})
    void tamperedOrUnsignedCapabilitiesNeverProbeUserOrObject(String field) {
        Token original = token(service.issueUrl(42L, KEY));
        String owner = original.owner();
        String revision = original.revision();
        String asset = original.asset();
        String expires = original.expires();
        String signature = original.signature();
        switch (field) {
            case "owner" -> owner = "43";
            case "revision" -> revision = "0".repeat(64);
            case "asset" -> asset = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    KEY.replace("a76f", "b76f").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            case "expires" -> expires = String.valueOf(Long.parseLong(expires) - 1);
            case "signature" -> signature = (signature.startsWith("A") ? "B" : "A") + signature.substring(1);
            case "missingSignature" -> signature = null;
            case "nonCanonicalOwner" -> owner = "042";
            case "nonCanonicalExpiry" -> expires = "0" + expires;
            case "paddedAsset" -> asset += "=";
            case "paddedSignature" -> signature += "=";
            case "overflowOwner" -> owner = "9999999999999999999";
            default -> throw new AssertionError(field);
        }
        Token changed = new Token(owner, revision, asset, expires, signature);
        assertUnavailable(() -> read(service, changed), 404);
        verifyNoInteractions(mapper, storage);
    }

    @Test
    void expiredAndExcessivelyFutureCapabilitiesFailBeforeUserLookup() {
        Token token = token(service.issueUrl(42L, KEY));
        assertUnavailable(() -> read(serviceAt(NOW.plusSeconds(86400)), token), 404);
        assertUnavailable(() -> read(serviceAt(NOW.minusSeconds(1)), token), 404);
        verifyNoInteractions(mapper, storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"users/43/avatar/a76f323e5177470f86471a42bb8fcb62.png", "../private.png",
            "users/42/avatar/../../private.png", "https://external.example/private.png",
            "users/42/avatar/A76F323E5177470F86471A42BB8FCB62.png", "users/42/avatar/arbitrary.svg"})
    void issuerNeverSignsForeignOrArbitraryObjects(String key) {
        assertThatThrownBy(() -> service.issueUrl(42L, key))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("USER_AVATAR_IMAGE_IDENTITY_INVALID");
        verifyNoInteractions(mapper, storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "cleared", "replaced"})
    void noLongerCurrentOrInactiveOwnerCannotReadEvenAnAuthenticUnexpiredCapability(String state) {
        if (!state.equals("inactive")) {
            when(mapper.profile(42L)).thenReturn(Map.of("avatarObjectKey",
                    state.equals("cleared") ? "" : KEY.replace("a76f", "b76f")));
        }
        assertUnavailable(() -> read(service, token(service.issueUrl(42L, KEY))), 404);
        verify(mapper).profile(42L);
        verifyNoInteractions(storage);
        // The mapper query itself excludes non-ACTIVE and deleted accounts.
        assertThat(Arrays.stream(AppUserProfileMapper.class.getMethods())
                .filter(method -> method.getName().equals("profile")).findFirst().orElseThrow()
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value())
                .anyMatch(sql -> sql.contains("status='ACTIVE'") && sql.contains("is_deleted=0"));
    }

    @Test
    void missingObjectAndDatabaseFailureReturnGenericAvailabilityErrors() {
        when(mapper.profile(42L)).thenReturn(Map.of("avatarObjectKey", KEY));
        when(storage.get(KEY)).thenThrow(new IllegalStateException("internal bucket/key/credentials"));
        Token token = token(service.issueUrl(42L, KEY));
        assertUnavailable(() -> read(service, token), 503);
        org.mockito.Mockito.reset(mapper, storage);
        when(mapper.profile(42L)).thenThrow(new BizException(500, "internal database detail"));
        assertUnavailable(() -> read(service, token), 503);
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "wrongMagic", "oversized"})
    void invalidStoredImageIsBoundedAndClosed(String kind) {
        byte[] bytes = switch (kind) {
            case "empty" -> new byte[0];
            case "wrongMagic" -> "<svg/>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            case "oversized" -> Arrays.copyOf(PNG, 2 * 1024 * 1024 + 1);
            default -> throw new AssertionError(kind);
        };
        when(mapper.profile(42L)).thenReturn(Map.of("avatarObjectKey", KEY));
        var stream = new TrackingStream(bytes);
        when(storage.get(KEY)).thenReturn(stream);
        assertUnavailable(() -> read(service, token(service.issueUrl(42L, KEY))), 404);
        assertThat(stream.closed).isTrue();
    }

    @Test
    void exactlyTwoMiBIsAllowedAndReadFailureClosesTheStream() {
        when(mapper.profile(42L)).thenReturn(Map.of("avatarObjectKey", KEY));
        when(storage.get(KEY)).thenReturn(new TrackingStream(Arrays.copyOf(PNG, 2 * 1024 * 1024)));
        Token token = token(service.issueUrl(42L, KEY));
        assertThat(read(service, token).bytes()).hasSize(2 * 1024 * 1024);
        var failedStream = new TrackingStream(PNG) {
            @Override
            public byte[] readNBytes(int length) throws IOException {
                throw new IOException("internal object transport detail");
            }
        };
        when(storage.get(KEY)).thenReturn(failedStream);
        assertUnavailable(() -> read(service, token), 503);
        assertThat(failedStream.closed).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpg", "webp"})
    void existingSupportedFormatsUseTheVerifiedContentType(String extension) {
        String key = KEY.replace(".png", "." + extension);
        byte[] bytes = extension.equals("jpg") ? new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1}
                : new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        when(mapper.profile(42L)).thenReturn(Map.of("avatarObjectKey", key));
        when(storage.get(key)).thenReturn(new ByteArrayInputStream(bytes));
        var image = read(service, token(service.issueUrl(42L, key)));
        assertThat(image.bytes()).isEqualTo(bytes);
        assertThat(image.contentType()).isEqualTo(extension.equals("jpg") ? "image/jpeg" : "image/webp");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "http://avatar-test.example", "https://127.0.0.1", "https://localhost",
            "https://sub.localhost", "https://[::1]", "https://avatar-test.example:9000",
            "https://user@avatar-test.example", "https://avatar-test.example/private",
            "https://avatar-test.example?origin=other", "https://avatar-test.example#fragment"})
    void missingOrInvalidPublicOriginFailsClosedWithoutStorageAccess(String origin) {
        properties.setPublicMediaOrigin(origin);
        assertThatThrownBy(() -> service.issueUrl(42L, KEY)).isInstanceOfSatisfying(BizException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(503))
                .hasMessage(origin.isEmpty() ? "USER_AVATAR_IMAGE_PUBLIC_ORIGIN_REQUIRED"
                        : "USER_AVATAR_IMAGE_PUBLIC_ORIGIN_INVALID");
        verifyNoInteractions(mapper, storage);
    }

    @Test
    void missingSigningKeyAndWrongSigningKeyFailClosed() {
        Token token = token(service.issueUrl(42L, KEY));
        properties.setSecretKey("different-test-only-secret");
        assertUnavailable(() -> read(service, token), 404);
        properties.setSecretKey("");
        assertThatThrownBy(() -> service.issueUrl(42L, KEY)).isInstanceOfSatisfying(BizException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(503))
                .hasMessage("USER_AVATAR_IMAGE_SIGNING_KEY_UNAVAILABLE");
        verifyNoInteractions(mapper, storage);
    }

    private Token token(String url) {
        URI uri = URI.create(url);
        String[] path = uri.getPath().split("/");
        Map<String, String> query = Arrays.stream(uri.getRawQuery().split("&"))
                .map(part -> part.split("=", 2)).collect(Collectors.toMap(part -> part[0], part -> part[1]));
        return new Token(path[path.length - 2], path[path.length - 1], query.get("asset"),
                query.get("expires"), query.get("signature"));
    }

    private AppUserAvatarImageService.ImageBytes read(AppUserAvatarImageService reader, Token token) {
        return reader.read(token.owner(), token.revision(), token.asset(), token.expires(), token.signature());
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BizException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo(code);
            assertThat(exception).hasMessage("USER_AVATAR_IMAGE_UNAVAILABLE");
        });
    }

    private record Token(String owner, String revision, String asset, String expires, String signature) {}

    private static class TrackingStream extends ByteArrayInputStream {
        boolean closed;
        TrackingStream(byte[] bytes) { super(bytes); }
        @Override
        public void close() throws IOException { closed = true; super.close(); }
    }
}
