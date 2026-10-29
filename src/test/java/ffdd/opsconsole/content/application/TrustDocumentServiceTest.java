package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.TrustDocumentRow;
import ffdd.opsconsole.content.domain.TrustDocumentsView;
import ffdd.opsconsole.content.dto.TrustDocumentRestoreRequest;
import ffdd.opsconsole.content.dto.TrustDocumentUploadRequest;
import ffdd.opsconsole.content.mapper.TrustDocumentMapper;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class TrustDocumentServiceTest {
    private static final byte[] PDF = pdf(2);
    private final TrustDocumentMapper mapper = mock(TrustDocumentMapper.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final A2RuntimePolicy a2 = mock(A2RuntimePolicy.class);
    private TrustDocumentService service;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(a2.reasonMinChars()).thenReturn(3);
        doCallRealMethod().when(a2).validateReason(anyString());
        signIn("content_i4_publish_standard", "content_i4_trust_section_manage");
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(call -> ((Supplier<Object>) call.getArgument(4)).get());
        service = new TrustDocumentService(mapper, storage, audit, idempotency, a2, new ObjectMapper());
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void defaultsServeTheShippedFilesWithoutDatesOrUploaderDetails() throws Exception {
        TrustDocumentsView view = service.published();

        assertThat(view.profile().legalName()).isEqualTo("UVEL AI LTD.");
        assertThat(view.profile().authorizedCapitalUsd()).isEqualTo("50000000");
        assertThat(view.documents()).extracting(TrustDocumentsView.Document::slot)
                .containsExactly("whitepaper", "incorporation", "msb", "ein");
        assertThat(view.documents()).allSatisfy(document -> {
            assertThat(document.source()).isEqualTo("DEFAULT");
            assertThat(document.updatedBy()).isNull();
            assertThat(document.updatedAt()).isNull();
            assertThat(document.fileUrl()).isEqualTo(document.slot().equals("whitepaper") ? null
                    : "/api/content/trust/documents/" + document.slot() + "/file?v=" + document.sha256().substring(0, 12));
        });
        assertThatThrownBy(() -> service.publicFile("whitepaper")).isInstanceOf(BizException.class)
                .hasMessageContaining("TRUST_DOCUMENT_NOT_DOWNLOADABLE");
        assertThat(service.publicFile("msb").bytes()).isEqualTo(service.file("msb").bytes());
        TrustDocumentsView.Document whitepaper = view.documents().get(0);
        byte[] bytes = service.file("whitepaper").bytes();
        assertThat(whitepaper.version()).isEqualTo("v1.3");
        assertThat(whitepaper.sizeBytes()).isEqualTo(bytes.length);
        assertThat(whitepaper.sha256()).isEqualTo(sha256(bytes));
        assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(view.documents().get(2).referenceNo()).isEqualTo("31000342955895");
        assertThat(view.documents().get(2).verifyUrl()).isEqualTo("https://www.fincen.gov/msb-state-selector");
        assertThat(view.documents()).extracting(TrustDocumentsView.Document::pageCount).containsExactly(21, 3, 1, 1);
        assertThat(view.profile().registeredAddress()).isEqualTo("New York, NY 10065");
    }

    @Test
    void pagesRenderAsJpegForTheViewerAndStayWithinTheDocument() {
        byte[] first = service.page("incorporation", 1);
        assertThat(first[0]).isEqualTo((byte) 0xFF);
        assertThat(first[1]).isEqualTo((byte) 0xD8);
        assertThat(service.page("incorporation", 1)).isSameAs(first);
        assertCode(() -> service.page("incorporation", 4), "TRUST_DOCUMENT_PAGE_NOT_FOUND");
        assertCode(() -> service.page("incorporation", 0), "TRUST_DOCUMENT_PAGE_NOT_FOUND");
        assertCode(() -> service.page("passport", 1), "TRUST_DOCUMENT_NOT_FOUND");
    }

    @Test
    void uploadedRowsReplaceTheDefaultAndOnlyTheConsoleSeesWhoUploaded() {
        when(mapper.list()).thenReturn(List.of(row("ein", "abc123abc123abc123")));

        TrustDocumentsView.Document app = service.published().documents().get(3);
        TrustDocumentsView.Document console = service.console().documents().get(3);

        assertThat(app.source()).isEqualTo("UPLOADED");
        assertThat(app.fileName()).isEqualTo("ein-2026.pdf");
        assertThat(app.updatedBy()).isNull();
        assertThat(console.updatedBy()).isEqualTo("ops-admin");
        assertThat(console.updatedAt()).isNotNull();
    }

    @Test
    void uploadStoresThePdfReplacesTheSlotAndRecordsAHighRiskAudit() {
        String current = service.published().documents().get(0).sha256();
        // Pre-check and transaction both see the default; the reply reads the new row.
        when(mapper.find("whitepaper")).thenReturn(null, null, row("whitepaper", sha256(PDF)));
        when(mapper.insert(any())).thenReturn(1);

        TrustDocumentsView.Document result = service.upload("whitepaper", pdf("UVEL Whitepaper v1.4.pdf"),
                new TrustDocumentUploadRequest(" v1.4 ", "English", null, null, "publish v1.4", current), "key-1", "ops-admin");

        ArgumentCaptor<TrustDocumentRow> saved = ArgumentCaptor.forClass(TrustDocumentRow.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().version()).isEqualTo("v1.4");
        assertThat(saved.getValue().fileName()).isEqualTo("UVEL-Whitepaper-v1.4.pdf");
        assertThat(saved.getValue().sha256()).isEqualTo(sha256(PDF));
        assertThat(saved.getValue().pageCount()).isEqualTo(2);
        assertThat(saved.getValue().objectKey()).isEqualTo("admin/content/trust-documents/whitepaper/" + sha256(PDF) + ".pdf");
        verify(storage).put(eq(saved.getValue().objectKey()), eq("application/pdf"), any(ByteArrayInputStream.class), eq((long) PDF.length));
        ArgumentCaptor<AuditLogWriteRequest> audited = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(audit).recordRequired(audited.capture());
        assertThat(audited.getValue().getAction()).isEqualTo("I4_TRUST_DOCUMENT_UPLOADED");
        assertThat(audited.getValue().getRiskLevel()).isEqualTo("HIGH");
        assertThat(result.source()).isEqualTo("UPLOADED");
    }

    @Test
    void aConcurrentWriteToTheSameSlotIsReportedAsStaleInsteadOfOverwritten() {
        String defaultSha = service.published().documents().get(3).sha256();
        when(mapper.find("ein")).thenReturn(null);
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("slot"));
        assertCode(() -> service.upload("ein", pdf("ein.pdf"), request(null, "43-2288822", null, defaultSha), "k1", "a"),
                "TRUST_DOCUMENT_STALE");

        when(mapper.find("msb")).thenReturn(row("msb", "f".repeat(64)));
        when(mapper.replace(any(), eq("f".repeat(64)))).thenReturn(0);
        assertCode(() -> service.upload("msb", pdf("msb.pdf"), request(null, "31000342955895", null, "f".repeat(64)), "k2", "a"),
                "TRUST_DOCUMENT_STALE");
    }

    @Test
    void registrationsNeedTheTrustSectionAuthorityWhileTheWhitePaperTakesStandardPublishing() {
        signIn("content_i4_publish_standard");
        String msb = service.published().documents().get(2).sha256();
        assertCode(() -> service.upload("msb", pdf("msb.pdf"), request(null, "31000342955895", null, msb), "k", "a"),
                "TRUST_DOCUMENT_FORBIDDEN");
        assertCode(() -> service.restoreDefault("ein", new TrustDocumentRestoreRequest("back to default", "x"), "k", "a"),
                "TRUST_DOCUMENT_FORBIDDEN");

        String whitepaper = service.published().documents().get(0).sha256();
        when(mapper.insert(any())).thenReturn(1);
        service.upload("whitepaper", pdf("wp.pdf"), request("v2", null, null, whitepaper), "k", "a");
        verify(mapper).insert(any());
    }

    @Test
    void uploadRejectsAnythingThatIsNotACompletePublishableDocument() {
        String whitepaper = service.published().documents().get(0).sha256();
        String msb = service.published().documents().get(2).sha256();
        MockMultipartFile text = new MockMultipartFile("file", "a.pdf", "application/pdf", "hello".getBytes(StandardCharsets.UTF_8));

        assertCode(() -> service.upload("whitepaper", text, request("v2", null, null, whitepaper), "k", "a"), "TRUST_DOCUMENT_PDF_REQUIRED");
        MockMultipartFile broken = new MockMultipartFile("file", "b.pdf", "application/pdf", "%PDF-1.7 not really".getBytes(StandardCharsets.US_ASCII));
        assertCode(() -> service.upload("whitepaper", broken, request("v2", null, null, whitepaper), "k", "a"), "TRUST_DOCUMENT_PDF_REQUIRED");
        assertCode(() -> service.upload("whitepaper", pdf("a.pdf"), request(null, null, null, whitepaper), "k", "a"), "TRUST_DOCUMENT_VERSION_REQUIRED");
        assertCode(() -> service.upload("msb", pdf("a.pdf"), request(null, null, null, msb), "k", "a"), "TRUST_DOCUMENT_REFERENCE_REQUIRED");
        assertCode(() -> service.upload("msb", pdf("a.pdf"), request(null, "31000342955895", "http://www.fincen.gov", msb), "k", "a"),
                "TRUST_DOCUMENT_VERIFY_URL_INVALID");
        assertCode(() -> service.upload("msb", pdf("a.pdf"), new TrustDocumentUploadRequest(null, null, "1", null, "x \u200b y", msb), "k", "a"),
                "REASON_TOO_SHORT_MIN_3");
        assertCode(() -> service.upload("msb", pdf("a.pdf"), request(null, "1", null, msb), " ", "a"), "IDEMPOTENCY_KEY_REQUIRED");
        assertCode(() -> service.upload("passport", pdf("a.pdf"), request("v1", null, null, msb), "k", "a"), "TRUST_DOCUMENT_NOT_FOUND");
        assertCode(() -> service.upload("msb", pdf("a.pdf"), request(null, "1", null, whitepaper), "k", "a"), "TRUST_DOCUMENT_STALE");
        verify(storage, never()).put(anyString(), anyString(), any(), anyLong());
        verify(mapper, never()).insert(any());
        verify(mapper, never()).replace(any(), anyString());
    }

    @Test
    void restoreDefaultRemovesTheUploadOnlyWhenTheOperatorSawTheCurrentFile() {
        when(mapper.find("ein")).thenReturn(row("ein", "f".repeat(64)));
        when(mapper.deleteIfCurrent("ein", "f".repeat(64))).thenReturn(1);

        assertCode(() -> service.restoreDefault("ein", new TrustDocumentRestoreRequest("back to default", "0".repeat(64)), "k", "a"),
                "TRUST_DOCUMENT_STALE");
        TrustDocumentsView.Document restored = service.restoreDefault("ein",
                new TrustDocumentRestoreRequest("back to default", "f".repeat(64)), "k2", "ops-admin");

        verify(mapper).deleteIfCurrent("ein", "f".repeat(64));
        assertThat(restored.source()).isEqualTo("DEFAULT");
        assertThat(restored.referenceNo()).isEqualTo("43-2288822");
        when(mapper.find("msb")).thenReturn(null);
        assertCode(() -> service.restoreDefault("msb", new TrustDocumentRestoreRequest("back to default", "x"), "k3", "a"),
                "TRUST_DOCUMENT_ALREADY_DEFAULT");
    }

    private static void signIn(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("ops-admin", null, authorities));
    }

    private static TrustDocumentUploadRequest request(String version, String referenceNo, String verifyUrl, String expectedSha256) {
        return new TrustDocumentUploadRequest(version, null, referenceNo, verifyUrl, "publish document", expectedSha256);
    }

    private static MockMultipartFile pdf(String name) {
        return new MockMultipartFile("file", name, "application/pdf", PDF);
    }

    private static TrustDocumentRow row(String slot, String sha256) {
        return new TrustDocumentRow(slot, null, "English", "43-2288822", null, "admin/content/trust-documents/" + slot + "/x.pdf",
                slot + "-2026.pdf", 10L, 1, sha256, "ops-admin", LocalDateTime.parse("2026-10-29T10:00:00"));
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(BizException.class).hasMessageContaining(code);
    }

    private static byte[] pdf(int pages) {
        try (PDDocument document = new PDDocument(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
