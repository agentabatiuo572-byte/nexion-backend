package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.content.domain.SupportAssignment;
import ffdd.opsconsole.content.domain.SupportAttachment;
import ffdd.opsconsole.content.mapper.SupportAttachmentMapper;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SupportAttachmentServiceTest {
    private final SupportAttachmentMapper mapper = mock(SupportAttachmentMapper.class);
    private final SupportBindingMapper bindings = mock(SupportBindingMapper.class);
    private final SupportOwnershipService ownership = mock(SupportOwnershipService.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final SupportAttachmentPolicy policy = new SupportAttachmentPolicy();
    private final ProductionSupportPathGuard production = mock(ProductionSupportPathGuard.class);
    private final SupportAttachmentService service = new SupportAttachmentService(mapper, bindings, ownership,
            storage, policy, production, mock(PlatformTransactionManager.class));
    private final String id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @BeforeEach void setup() {
        policy.setAllowedMimeTypes(List.of("image/png", "image/jpeg"));
        policy.setMaxBytes(100_000L); policy.setMaxPixels(1000L); policy.setTtlSeconds(60L);
        authenticate("USER", 10L);
        when(mapper.customer(id)).thenReturn(10L);
        when(bindings.current(10L)).thenReturn(assignment(20L, 2L));
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }
    private void authenticate(String type, Long actor) {
        var auth = new UsernamePasswordAuthenticationToken(actor.toString(), "", List.of(
                new SimpleGrantedAuthority("service_m3_read"), new SimpleGrantedAuthority("service_m3_write")));
        auth.setDetails(Map.of("subjectType", type));
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(ownership.actorId()).thenReturn(actor);
    }
    private SupportAssignment assignment(Long assignment, Long agent) {
        return new SupportAssignment(assignment, 10L, agent, 1L, "MANUAL", 10L, 0, null, null);
    }
    private SupportAttachment row(String type, Long uploader, String state, Long message, boolean expired) {
        return new SupportAttachment(id, 10L, type, uploader, 20L, "client-123", "hash", "image/png", 3,
                2, 2, "private/support/random/key", state, LocalDateTime.now(ZoneOffset.UTC).plusSeconds(expired ? -60 : 60), message);
    }
    private static byte[] image(String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }
    private void code(int code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(code));
    }

    @Test void noProductionDefaultsAndUnsupportedCodecsStayUnavailable() {
        assertThat(new SupportAttachmentPolicy().view().available()).isFalse();
        policy.setAllowedMimeTypes(List.of("image/webp"));
        assertThat(service.policy().available()).isFalse();
    }

    @Test void realPngAndJpegDecodeAndReencodeWithoutAddedMetadata() throws Exception {
        for (String format : List.of("png", "jpeg")) {
            byte[] raw = image(format);
            var encoded = SupportAttachmentService.decode(new MockMultipartFile("file", "x." + format, "image/" + format, raw), policy.view());
            assertThat(ImageIO.read(new ByteArrayInputStream(encoded.content())).getWidth()).isEqualTo(2);
            assertThat(encoded.mime()).isEqualTo("image/" + format);
            assertThat(encoded.width()).isEqualTo(2); assertThat(encoded.height()).isEqualTo(2);
        }
        // JPEG comment segment is valid metadata, but must not survive the pixel-only encoding.
        byte[] jpeg = image("jpeg"), comment = "private-comment-marker".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        source.write(jpeg, 0, 2); source.write(new byte[]{(byte)255, (byte)254, 0, (byte)(comment.length + 2)});
        source.write(comment); source.write(jpeg, 2, jpeg.length - 2);
        var clean = SupportAttachmentService.decode(new MockMultipartFile("file", "x.jpg", "image/jpeg", source.toByteArray()), policy.view());
        assertThat(new String(clean.content(), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("private-comment-marker");
    }

    @Test void rejectsMimeSpoofScriptsTruncationPathsBytesAndPixels() throws Exception {
        byte[] png = image("png");
        code(415, () -> SupportAttachmentService.decode(new MockMultipartFile("file", "x.jpg", "image/jpeg", png), policy.view()));
        code(415, () -> SupportAttachmentService.decode(new MockMultipartFile("file", "x.svg", "image/svg+xml", "<svg/>".getBytes()), policy.view()));
        code(422, () -> SupportAttachmentService.decode(new MockMultipartFile("file", "x.png", "image/png", "<script>x</script>".getBytes()), policy.view()));
        code(422, () -> SupportAttachmentService.decode(new MockMultipartFile("file", "x.png", "image/png", Arrays.copyOf(png, png.length - 1)), policy.view()));
        code(422, () -> SupportAttachmentService.decode(new MockMultipartFile("file", "https://elsewhere/x.png", "image/png", png), policy.view()));
        policy.setMaxPixels(3L);
        code(413, () -> SupportAttachmentService.decode(new MockMultipartFile("file", "x.png", "image/png", png), policy.view()));
        policy.setMaxBytes(3L);
        code(413, () -> SupportAttachmentService.decode(new MockMultipartFile("file", "x.png", "image/png", png), policy.view()));
    }

    @Test void adminTokenCannotImpersonateAnEqualUserId() {
        authenticate("ADMIN", 10L);
        code(403, () -> service.content(id, "USER", 10L));
        verifyNoInteractions(storage);
    }

    @Test void readyOnlyUploaderAndTransferRevokesOldAndNewAdvisorDraftAccess() {
        when(mapper.find(id)).thenReturn(row("ADMIN", 2L, "READY", null, false));
        code(404, () -> service.content(id, "USER", 10L));
        authenticate("ADMIN", 3L); when(ownership.canRead(3L, 10L)).thenReturn(true);
        when(bindings.current(10L)).thenReturn(assignment(21L, 3L));
        code(404, () -> service.content(id, "ADMIN", 3L));
        authenticate("ADMIN", 2L); when(ownership.canRead(2L, 10L)).thenReturn(false);
        code(404, () -> service.content(id, "ADMIN", 2L));
        verifyNoInteractions(storage);
    }

    @Test void attachedCurrentReaderWorksButUnrelatedClientCannotRead() {
        when(mapper.find(id)).thenReturn(row("ADMIN", 2L, "ATTACHED", 8L, true));
        when(storage.get(anyString())).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        assertThat(service.content(id, "USER", 10L).bytes()).containsExactly(1, 2, 3);
        authenticate("USER", 11L); code(404, () -> service.content(id, "USER", 11L));
        authenticate("ADMIN", 3L); when(ownership.canRead(3L, 10L)).thenReturn(true);
        assertThat(service.metadata(id, "ADMIN", 3L).messageId()).isEqualTo(8L);
    }

    @Test void expiredOrCancelledCannotAttachAndAttachedCannotCancel() {
        when(mapper.find(id)).thenReturn(row("USER", 10L, "READY", null, true));
        code(409, () -> service.attachToMessage(10L, "USER", 10L, null, id, 8L));
        when(mapper.find(id)).thenReturn(row("USER", 10L, "ATTACHED", 8L, true));
        code(409, () -> service.cancel(id, "USER", 10L, "cancel-123"));
        assertThat(service.attachToMessage(10L, "USER", 10L, null, id, 8L).messageId()).isEqualTo(8L);
        code(409, () -> service.attachToMessage(10L, "USER", 10L, null, id, 9L));
        verifyNoInteractions(storage);
    }

    @Test void storageUnavailableNeverAttachesMessage() {
        when(mapper.find(id)).thenReturn(row("USER", 10L, "READY", null, false));
        when(storage.exists(anyString())).thenThrow(new BizException(500, "storage unavailable"));
        code(503, () -> service.attachToMessage(10L, "USER", 10L, null, id, 8L));
        verify(mapper, never()).attach(anyString(), anyLong());
    }

    @Test void uploadClientIdAndCommandKeysArePersistentAndPayloadBound() throws Exception {
        byte[] bytes = image("png");
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", bytes);
        TransactionSynchronizationManager.initSynchronization();
        var captured = org.mockito.ArgumentCaptor.forClass(SupportAttachment.class);
        service.upload(10L, "USER", 10L, null, "client-123", "upload-123", file);
        verify(mapper).insertAttachment(captured.capture());
        SupportAttachment stored = captured.getValue();
        assertThat(stored.objectKey()).startsWith("private/support/").doesNotContain(stored.id());
        when(mapper.findUpload("USER", 10L, "client-123")).thenReturn(stored);
        var replay = service.upload(10L, "USER", 10L, null, "client-123", "upload-456", file);
        assertThat(replay.id()).isEqualTo(stored.id());
        verify(storage, times(1)).put(anyString(), anyString(), any(), anyLong());
        when(mapper.command("USER", 10L, "UPLOAD", "upload-123")).thenReturn(stored.id());
        when(mapper.find(stored.id())).thenReturn(stored);
        code(409, () -> service.upload(10L, "USER", 10L, null, "other-123", "upload-123", file));
    }
}
