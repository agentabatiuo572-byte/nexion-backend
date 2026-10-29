package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Trust-centre documents (white paper and company registrations). Each slot serves the
 * operator's upload when there is one, otherwise the default file shipped in resources.
 */
@Service
public class TrustDocumentService {
    public static final List<String> SLOTS = List.of("whitepaper", "incorporation", "msb", "ein");
    private static final String WHITEPAPER = "whitepaper";
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final int MAX_PAGES = 80;
    private static final float PAGE_DPI = 144f;
    private static final int PAGE_CACHE_LIMIT = 256;
    private static final String IDEMPOTENCY_SCOPE = "I4_TRUST_DOCUMENT";
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,31}");
    private static final Pattern LANGUAGE = Pattern.compile("[\\p{L}][\\p{L} ()/-]{0,31}");
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 #./-]{0,63}");

    private final TrustDocumentMapper mapper;
    private final ObjectStorageService storage;
    private final AuditLogService audit;
    private final AdminIdempotencyService idempotency;
    private final A2RuntimePolicy a2;
    private final Clock clock;
    private final TrustDocumentsView.Profile profile;
    private final Map<String, DefaultDocument> defaults;
    /** Rendered pages keyed by file hash and page number; a replaced file gets new keys. */
    private final Map<String, byte[]> pageImages = new ConcurrentHashMap<>();

    private record DefaultEntry(String slot, String resource, String sha256, String version, String language, String referenceNo, String verifyUrl) {}
    private record Defaults(TrustDocumentsView.Profile profile, List<DefaultEntry> documents) {}
    private record DefaultDocument(DefaultEntry entry, byte[] bytes, String sha256, int pageCount) {}
    public record Content(String fileName, byte[] bytes) {}

    public TrustDocumentService(TrustDocumentMapper mapper, ObjectStorageService storage, AuditLogService audit,
            AdminIdempotencyService idempotency, A2RuntimePolicy a2, Clock clock, ObjectMapper json) {
        this.mapper = mapper;
        this.storage = storage;
        this.audit = audit;
        this.idempotency = idempotency;
        this.a2 = a2;
        this.clock = clock;
        Defaults loaded;
        try {
            loaded = json.readValue(PublishedContentDefaults.read("/trust-documents/defaults.json"), Defaults.class);
        } catch (IOException ex) {
            throw new IllegalStateException("TRUST_DOCUMENT_DEFAULTS_UNREADABLE", ex);
        }
        this.profile = loaded.profile();
        this.defaults = loaded.documents().stream().collect(Collectors.toMap(DefaultEntry::slot, entry -> {
            byte[] bytes = resourceBytes("/trust-documents/" + entry.resource());
            // defaults.json pins each shipped file, so a swapped resource stops startup instead of being served.
            if (!sha256(bytes).equals(entry.sha256())) throw new IllegalStateException("TRUST_DOCUMENT_DEFAULT_CHANGED " + entry.resource());
            return new DefaultDocument(entry, bytes, entry.sha256(), pageCount(bytes));
        }));
        if (!defaults.keySet().equals(java.util.Set.copyOf(SLOTS))) throw new IllegalStateException("TRUST_DOCUMENT_DEFAULT_SLOTS");
    }

    public TrustDocumentsView published() {
        return view(false);
    }

    public TrustDocumentsView console() {
        return view(true);
    }

    public Content file(String slot) {
        requireSlot(slot);
        TrustDocumentRow row = mapper.find(slot);
        if (row == null) {
            DefaultDocument fallback = defaults.get(slot);
            return new Content(fallback.entry().resource(), fallback.bytes());
        }
        try (InputStream stream = storage.get(row.objectKey())) {
            return new Content(row.fileName(), stream.readAllBytes());
        } catch (IOException ex) {
            throw new BizException(503, "TRUST_DOCUMENT_UNAVAILABLE");
        }
    }

    /** The PDF behind the public file URL; the white paper is only shown as page images. */
    public Content publicFile(String slot) {
        requireSlot(slot);
        if (WHITEPAPER.equals(slot)) throw new BizException(404, "TRUST_DOCUMENT_NOT_DOWNLOADABLE");
        return file(slot);
    }

    /** One page of the current file as a JPEG, for the App viewer. */
    public byte[] page(String slot, int page) {
        requireSlot(slot);
        TrustDocumentRow row = mapper.find(slot);
        DefaultDocument fallback = defaults.get(slot);
        int pages = row == null ? fallback.pageCount() : row.pageCount();
        if (page < 1 || page > pages) throw new BizException(404, "TRUST_DOCUMENT_PAGE_NOT_FOUND");
        String sha = row == null ? fallback.sha256() : row.sha256();
        String key = sha + ":" + page;
        byte[] cached = pageImages.get(key);
        if (cached != null) return cached;
        byte[] rendered = render(row == null ? fallback.bytes() : file(slot).bytes(), page - 1);
        if (pageImages.size() >= PAGE_CACHE_LIMIT) pageImages.clear();
        pageImages.put(key, rendered);
        return rendered;
    }

    public TrustDocumentsView.Document upload(String slot, MultipartFile file, TrustDocumentUploadRequest request,
            String idempotencyKey, String actor) {
        requireSlot(slot);
        String key = requireIdempotencyKey(idempotencyKey);
        String reason = requireReason(request.reason());
        byte[] bytes = pdfBytes(file);
        String version = optional(request.version(), VERSION, "TRUST_DOCUMENT_VERSION_INVALID");
        if (WHITEPAPER.equals(slot) && version == null) throw invalid("TRUST_DOCUMENT_VERSION_REQUIRED");
        String language = optional(request.language(), LANGUAGE, "TRUST_DOCUMENT_LANGUAGE_INVALID");
        String referenceNo = optional(request.referenceNo(), REFERENCE, "TRUST_DOCUMENT_REFERENCE_INVALID");
        if (!WHITEPAPER.equals(slot) && referenceNo == null) throw invalid("TRUST_DOCUMENT_REFERENCE_REQUIRED");
        String verifyUrl = verifyUrl(request.verifyUrl());
        String fileName = fileName(file.getOriginalFilename(), slot);
        int pageCount = pageCount(bytes);
        String sha256 = sha256(bytes);
        String requestHash = sha256(String.join("\u0000", slot, sha256, text(version), text(language), text(referenceNo),
                text(verifyUrl), fileName, reason, text(request.expectedSha256()), text(actor)).getBytes(StandardCharsets.UTF_8));
        return idempotency.execute(IDEMPOTENCY_SCOPE, key, requestHash, TrustDocumentsView.Document.class, () -> {
            TrustDocumentRow current = mapper.lock(slot);
            String previous = requireCurrent(slot, current, request.expectedSha256());
            LocalDate today = LocalDate.now(clock);
            String objectKey = "admin/content/trust-documents/%s/%04d%02d%02d/%s.pdf".formatted(
                    slot, today.getYear(), today.getMonthValue(), today.getDayOfMonth(), UUID.randomUUID());
            storage.put(objectKey, "application/pdf", new ByteArrayInputStream(bytes), bytes.length);
            mapper.upsert(new TrustDocumentRow(slot, version, language, referenceNo, verifyUrl, objectKey, fileName,
                    (long) bytes.length, pageCount, sha256, actor, null));
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reason", reason);
            detail.put("fileName", fileName);
            detail.put("sizeBytes", bytes.length);
            detail.put("pageCount", pageCount);
            detail.put("sha256", sha256);
            detail.put("previousSha256", previous);
            detail.put("objectKey", objectKey);
            detail.put("version", version);
            detail.put("language", language);
            detail.put("referenceNo", referenceNo);
            detail.put("verifyUrl", verifyUrl);
            detail.put("idempotencyKey", key);
            record("I4_TRUST_DOCUMENT_UPLOADED", slot, actor, detail);
            return document(slot, mapper.find(slot), true);
        });
    }

    public TrustDocumentsView.Document restoreDefault(String slot, TrustDocumentRestoreRequest request,
            String idempotencyKey, String actor) {
        requireSlot(slot);
        String key = requireIdempotencyKey(idempotencyKey);
        String reason = requireReason(request.reason());
        String requestHash = sha256(String.join("\u0000", slot, reason, text(request.expectedSha256()), text(actor))
                .getBytes(StandardCharsets.UTF_8));
        return idempotency.execute(IDEMPOTENCY_SCOPE, key, requestHash, TrustDocumentsView.Document.class, () -> {
            TrustDocumentRow current = mapper.lock(slot);
            if (current == null) throw new BizException(409, "TRUST_DOCUMENT_ALREADY_DEFAULT");
            String previous = requireCurrent(slot, current, request.expectedSha256());
            mapper.delete(slot);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reason", reason);
            detail.put("previousSha256", previous);
            detail.put("previousObjectKey", current.objectKey());
            detail.put("idempotencyKey", key);
            record("I4_TRUST_DOCUMENT_RESTORED", slot, actor, detail);
            return document(slot, null, true);
        });
    }

    private TrustDocumentsView view(boolean console) {
        Map<String, TrustDocumentRow> rows = mapper.list().stream()
                .collect(Collectors.toMap(TrustDocumentRow::slot, Function.identity()));
        return new TrustDocumentsView(profile, SLOTS.stream().map(slot -> document(slot, rows.get(slot), console)).toList());
    }

    private TrustDocumentsView.Document document(String slot, TrustDocumentRow row, boolean console) {
        if (row == null) {
            DefaultDocument fallback = defaults.get(slot);
            DefaultEntry entry = fallback.entry();
            return new TrustDocumentsView.Document(slot, "DEFAULT", entry.version(), entry.language(), entry.referenceNo(),
                    entry.verifyUrl(), entry.resource(), fallback.bytes().length, fallback.pageCount(), fallback.sha256(),
                    fileUrl(slot, fallback.sha256()), null, null);
        }
        return new TrustDocumentsView.Document(slot, "UPLOADED", row.version(), row.language(), row.referenceNo(),
                row.verifyUrl(), row.fileName(), row.sizeBytes(), row.pageCount(), row.sha256(), fileUrl(slot, row.sha256()),
                console ? row.updatedBy() : null, console ? row.updatedAt() : null);
    }

    private String requireCurrent(String slot, TrustDocumentRow current, String expectedSha256) {
        String actual = current == null ? defaults.get(slot).sha256() : current.sha256();
        if (!actual.equals(text(expectedSha256))) throw new BizException(409, "TRUST_DOCUMENT_STALE");
        return actual;
    }

    private void record(String action, String slot, String actor, Map<String, Object> detail) {
        audit.recordRequired(AuditLogWriteRequest.builder().action(action).resourceType("TRUST_DOCUMENT")
                .resourceId(slot).actorType("ADMIN").actorUsername(actor).result("SUCCESS").riskLevel("HIGH")
                .detail(detail).build());
    }

    private static String fileUrl(String slot, String sha256) {
        if (WHITEPAPER.equals(slot)) return null;
        return "/api/content/trust/documents/" + slot + "/file?v=" + sha256.substring(0, 12);
    }

    private static void requireSlot(String slot) {
        if (!SLOTS.contains(slot)) throw new BizException(404, "TRUST_DOCUMENT_NOT_FOUND");
    }

    private static String requireIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BizException(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        return idempotencyKey.trim();
    }

    private String requireReason(String reason) {
        String value = text(reason);
        if (value.length() < a2.reasonMinChars() || value.length() > 200) {
            throw new BizException(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return value;
    }

    private static byte[] pdfBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) throw invalid("TRUST_DOCUMENT_FILE_REQUIRED");
        if (file.getSize() > MAX_BYTES) throw invalid("TRUST_DOCUMENT_TOO_LARGE");
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new BizException(500, "UPLOAD_STREAM_READ_FAILED");
        }
        byte[] magic = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < magic.length || !java.util.Arrays.equals(bytes, 0, magic.length, magic, 0, magic.length)) {
            throw invalid("TRUST_DOCUMENT_PDF_REQUIRED");
        }
        return bytes;
    }

    /** A file that does not open as a PDF, or is too long to show page by page, is rejected before upload. */
    private static int pageCount(byte[] bytes) {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            int pages = pdf.getNumberOfPages();
            if (pages < 1) throw invalid("TRUST_DOCUMENT_PDF_REQUIRED");
            if (pages > MAX_PAGES) throw invalid("TRUST_DOCUMENT_TOO_MANY_PAGES");
            return pages;
        } catch (IOException ex) {
            throw invalid("TRUST_DOCUMENT_PDF_REQUIRED");
        }
    }

    private static byte[] render(byte[] pdfBytes, int index) {
        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            BufferedImage image = new PDFRenderer(pdf).renderImageWithDPI(index, PAGE_DPI, ImageType.RGB);
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.9f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(image, null, null), param);
            } finally {
                writer.dispose();
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new BizException(503, "TRUST_DOCUMENT_UNAVAILABLE");
        }
    }

    private static String optional(String value, Pattern pattern, String error) {
        String normalized = text(value);
        if (normalized.isEmpty()) return null;
        if (!pattern.matcher(normalized).matches()) throw invalid(error);
        return normalized;
    }

    private static String verifyUrl(String value) {
        String normalized = text(value);
        if (normalized.isEmpty()) return null;
        try {
            URI uri = new URI(normalized);
            if (normalized.length() <= 512 && "https".equals(uri.getScheme()) && StringUtils.hasText(uri.getHost())
                    && uri.getUserInfo() == null) return normalized;
        } catch (java.net.URISyntaxException ignored) {
            // Falls through to the validation error below.
        }
        throw invalid("TRUST_DOCUMENT_VERIFY_URL_INVALID");
    }

    /** ASCII-only so the name can go into Content-Disposition unencoded. */
    private static String fileName(String original, String slot) {
        String base = text(original).replaceAll(".*[/\\\\]", "");
        base = base.replaceAll("(?i)\\.pdf$", "").replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[-.]+|[-.]+$", "");
        if (base.isEmpty()) base = slot;
        if (base.length() > 150) base = base.substring(0, 150);
        return base + ".pdf";
    }

    private static byte[] resourceBytes(String resource) {
        try (InputStream stream = TrustDocumentService.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("TRUST_DOCUMENT_DEFAULT_MISSING " + resource);
            return stream.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("TRUST_DOCUMENT_DEFAULT_UNREADABLE " + resource, ex);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static BizException invalid(String code) {
        return new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), code);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
