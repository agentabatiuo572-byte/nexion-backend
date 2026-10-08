package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportAttachment;
import ffdd.opsconsole.content.domain.SupportAssignment;
import ffdd.opsconsole.content.mapper.SupportAttachmentMapper;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportBulkMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class SupportAttachmentService {
    private static final Logger log = LoggerFactory.getLogger(SupportAttachmentService.class);
    private final SupportAttachmentMapper mapper;
    private final SupportBindingMapper bindings;
    private final SupportOwnershipService ownership;
    private final ObjectStorageService storage;
    private final SupportAttachmentPolicy policy;
    private final ProductionSupportPathGuard production;
    private final PlatformTransactionManager transactions;
    private final SupportBulkMapper bulk;
    private final ObjectMapper json;

    public SupportAttachmentPolicy.View policy() { return policy.view(); }

    @Transactional
    public Map<String,Object> uploadBulk(Long actor, String key, String clientUploadId, MultipartFile file) {
        requireActor("ADMIN",actor); positiveId(actor); token(key); token(clientUploadId);
        production.requireOpsWriteAllowed();
        ownership.requireSendingActor(actor);
        var limits=policy();
        if(!limits.available()) throw new BizException(422,"ATTACHMENT_POLICY_UNCONFIGURED");
        Encoded encoded=decode(file,limits);
        String digest=hash(actor+"|"+clientUploadId+"|"+encoded.rawHash());
        var previous=bulk.assetByCommand(actor,key);
        if(previous==null) {
            previous=bulk.assetByUpload(actor,clientUploadId);
            if(previous!=null && !key.equals(previous.get("commandKey"))) throw conflict();
        }
        if(previous!=null) {
            if(!Objects.equals(digest,previous.get("requestHash"))) throw conflict();
            BulkAsset asset=bulkAsset(previous,actor);
            exists(asset.objectKey());
            return bulkView(String.valueOf(previous.get("id")),asset);
        }
        String id=UUID.randomUUID().toString(),objectKey="private/support-bulk/"+UUID.randomUUID()+"/"+UUID.randomUUID();
        var asset=new BulkAsset(objectKey,encoded.mime(),encoded.content().length,encoded.width(),encoded.height(),
                LocalDateTime.now(ZoneOffset.UTC).plusSeconds(limits.ttlSeconds()));
        String payload=ffdd.opsconsole.content.dto.SupportMessagePayload.encode(Map.of("objectKey",objectKey,"mime",asset.mime(),
                "bytes",asset.bytes(),"width",asset.width(),"height",asset.height()));
        try { bulk.insertAsset(id,actor,key,clientUploadId,digest,payload,asset.expiresAt()); }
        catch(DuplicateKeyException ex) { throw conflict(); }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status==STATUS_ROLLED_BACK) {
                    try { storage.remove(objectKey); }
                    catch(RuntimeException ex) { log.error("Bulk attachment rollback cleanup failed: {}",id,ex); }
                }
            }
        });
        try { storage.put(objectKey,asset.mime(),new ByteArrayInputStream(encoded.content()),asset.bytes()); }
        catch(RuntimeException ex) { throw unavailable(); }
        return bulkView(id,asset);
    }

    /** A stable per-customer reference; the ASSET lock also serializes expiry with attachment commit. */
    @Transactional(propagation=Propagation.MANDATORY)
    public SupportAttachment.View materializeBulkForActor(String assetId,Long actor,Long customer,Long expectedAssignment,String attachmentId) {
        positiveId(actor); positiveId(customer); positiveId(expectedAssignment); validateAttachmentId(assetId); validateAttachmentId(attachmentId);
        production.requireAllowed(customer);
        var assignment=ownership.requireWriterForActor(actor,customer,true);
        if(!expectedAssignment.equals(assignment.id())) throw conflict();
        var source=bulk.assetForUpdate(assetId);
        BulkAsset asset=bulkAsset(source,actor);
        exists(asset.objectKey());
        String digest=hash(customer+"|ADMIN|"+actor+"|"+expectedAssignment+"|"+attachmentId+"|"+source.get("requestHash"));
        var prior=mapper.find(attachmentId);
        if(prior!=null) {
            if(!customer.equals(prior.customerId()) || !"ADMIN".equals(prior.uploaderType()) || !actor.equals(prior.uploaderId())
                    || !expectedAssignment.equals(prior.assignmentId()) || !digest.equals(prior.requestHash())
                    || !asset.objectKey().equals(prior.objectKey())) throw conflict();
            if(!"ATTACHED".equals(prior.state())) ready(prior);
            return prior.view();
        }
        var row=new SupportAttachment(attachmentId,customer,"ADMIN",actor,expectedAssignment,"bulk_"+attachmentId.replace("-",""),
                digest,asset.mime(),asset.bytes(),asset.width(),asset.height(),asset.objectKey(),"READY",asset.expiresAt(),null);
        try { mapper.insertAttachment(row); } catch(DuplicateKeyException ex) { throw conflict(); }
        return row.view();
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public SupportAttachment.View attachToMessageForActor(Long customer,Long actor,Long expectedAssignment,String attachmentId,Long messageId) {
        positiveId(customer); positiveId(actor); positiveId(messageId); positiveId(expectedAssignment);
        production.requireAllowed(customer);
        var assignment=ownership.requireWriterForActor(actor,customer,true);
        if(!expectedAssignment.equals(assignment.id())) throw conflict();
        return attach(customer,"ADMIN",actor,expectedAssignment,attachmentId,messageId);
    }

    /** The authenticated subject type must match as admin/user identifiers can overlap. */
    public Long actor(String type) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new BizException(401, "LOGIN_REQUIRED");
        if (!(auth.getDetails() instanceof Map<?, ?> details) || !type.equals(details.get("subjectType"))
                || !("ADMIN".equals(type) || "USER".equals(type)))
            throw new BizException(403, "SUPPORT_SUBJECT_REQUIRED");
        Long id = ownership.actorId();
        positiveId(id);
        return id;
    }

    @Transactional
    public SupportAttachment.View upload(Long customer, String type, Long actor, Long expectedAssignment,
            String clientUploadId, String key, MultipartFile file) {
        requireActor(type, actor);
        token(clientUploadId); token(key); positiveId(customer);
        production.requireAllowed(customer);
        ownership.lockCustomer(customer);
        Long assignment = writer(customer, type, actor, expectedAssignment);
        SupportAttachmentPolicy.View limits = policy();
        if (!limits.available()) throw new BizException(422, "ATTACHMENT_POLICY_UNCONFIGURED");
        Encoded encoded = decode(file, limits);
        String hash = hash(customer + "|" + type + "|" + actor + "|" + expectedAssignment + "|" + clientUploadId + "|" + encoded.rawHash());
        String commandId = mapper.command(type, actor, "UPLOAD", key);
        SupportAttachment previous = commandId == null ? mapper.findUpload(type, actor, clientUploadId) : mapper.find(commandId);
        if (previous != null) {
            if (!hash.equals(previous.requestHash())) throw conflict();
            requireReadable(previous, type, actor);
            if (!"READY".equals(previous.state()) && !"ATTACHED".equals(previous.state())) throw conflict();
            if (commandId == null) command(type, actor, "UPLOAD", key, previous.id());
            return previous.view();
        }
        String id = UUID.randomUUID().toString();
        String objectKey = "private/support/" + UUID.randomUUID() + "/" + UUID.randomUUID();
        SupportAttachment row = new SupportAttachment(id, customer, type, actor, assignment, clientUploadId,
                hash, encoded.mime(), encoded.content().length, encoded.width(), encoded.height(), objectKey,
                "READY", LocalDateTime.now(ZoneOffset.UTC).plusSeconds(limits.ttlSeconds()), null);
        try {
            mapper.insertAttachment(row);
            command(type, actor, "UPLOAD", key, id);
        } catch (DuplicateKeyException ex) { throw conflict(); }
        // Rollback compensation never removes an attached historical object.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try { storage.remove(objectKey); }
                    catch (RuntimeException ex) { log.error("Support attachment rollback cleanup failed: {}", id, ex); }
                }
            }
        });
        try { storage.put(objectKey, row.mime(), new ByteArrayInputStream(encoded.content()), row.bytes()); }
        catch (RuntimeException ex) { throw unavailable(); }
        return row.view();
    }

    /** Called by the message writer in the same customer-locked transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupportAttachment.View attachToMessage(Long customer, String type, Long actor, Long expectedAssignment,
            String attachmentId, Long messageId) {
        requireActor(type, actor); positiveId(customer); positiveId(messageId);
        production.requireAllowed(customer);
        ownership.lockCustomer(customer);
        writer(customer, type, actor, expectedAssignment);
        return attach(customer,type,actor,expectedAssignment,attachmentId,messageId);
    }

    private SupportAttachment.View attach(Long customer,String type,Long actor,Long expectedAssignment,String attachmentId,Long messageId) {
        SupportAttachment row = find(attachmentId);
        if (!customer.equals(row.customerId()) || !type.equals(row.uploaderType()) || !actor.equals(row.uploaderId()))
            throw missing();
        if ("ADMIN".equals(type) && !Objects.equals(expectedAssignment, row.assignmentId())) throw conflict();
        if ("ATTACHED".equals(row.state()) && messageId.equals(row.messageId())) return row.view();
        ready(row);
        // A successful temporary upload alone cannot prove the object still exists at send time.
        try { if (!storage.exists(row.objectKey())) throw unavailable(); }
        catch (RuntimeException ex) { throw unavailable(); }
        if (mapper.attach(row.id(), messageId) != 1) throw conflict();
        return find(row.id()).view();
    }

    @Transactional
    public SupportAttachment.View metadata(String id, String type, Long actor) {
        requireActor(type, actor);
        SupportAttachment row = locked(id);
        requireReadable(row, type, actor);
        return row.view();
    }

    public record Content(String mime, byte[] bytes) {}

    /** Materialize under the customer lock; no URL or unguarded lazy storage stream escapes. */
    @Transactional
    public Content content(String id, String type, Long actor) {
        requireActor(type, actor);
        SupportAttachment row = locked(id);
        requireReadable(row, type, actor);
        try (InputStream stream = storage.get(row.objectKey())) {
            byte[] bytes = stream.readNBytes(Math.toIntExact(row.bytes()) + 1);
            if (bytes.length != row.bytes()) throw unavailable();
            return new Content(row.mime(), bytes);
        } catch (IOException | RuntimeException ex) { throw unavailable(); }
    }

    @Transactional
    public SupportAttachment.View cancel(String id, String type, Long actor, String key) {
        requireActor(type, actor); token(key);
        SupportAttachment row = locked(id);
        production.requireAllowed(row.customerId());
        if (!type.equals(row.uploaderType()) || !actor.equals(row.uploaderId())) throw missing();
        requireCustomer(row, type, actor);
        String prior = mapper.command(type, actor, "CANCEL", key);
        if (prior != null && !prior.equals(id)) throw conflict();
        if ("ATTACHED".equals(row.state())) throw conflict();
        if (prior == null) command(type, actor, "CANCEL", key, id);
        if ("READY".equals(row.state())) {
            remove(row);
            mapper.retire(id, "REJECTED");
        }
        return find(id).view();
    }

    @Scheduled(fixedDelayString = "${nexion.support.attachments.cleanup-delay-ms:60000}")
    public void cleanupExpired() {
        if (!production.productionSupportAutomationAllowed()) return;
        for (String id : mapper.expired()) {
            try {
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    SupportAttachment row = locked(id);
                    if ("READY".equals(row.state()) && !row.expiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
                        remove(row);
                        mapper.retire(id, "EXPIRED");
                    }
                });
            } catch (RuntimeException ex) { log.warn("Support attachment expiry cleanup failed: {}", id, ex); }
        }
    }

    @Scheduled(fixedDelayString="${nexion.support.attachments.cleanup-delay-ms:60000}")
    public void cleanupExpiredBulkAssets() {
        if(!production.productionSupportAutomationAllowed()) return;
        var transaction=new TransactionTemplate(transactions);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        for(var candidate:bulk.expiredAssets()) {
            String id=String.valueOf(candidate.get("id"));
            try {
                transaction.executeWithoutResult(status->{
                    var row=bulk.assetForUpdate(id);
                    if(row==null || !"READY".equals(row.get("state"))) return;
                    var asset=readBulkAsset(row);
                    String key=asset.objectKey();
                    if(asset.expiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) return;
                    if(!key.startsWith("private/support-bulk/")) throw unavailable();
                    if(mapper.liveObjectReferences(key)==0) storage.remove(key);
                    bulk.retireAsset(id,"EXPIRED");
                });
            } catch(RuntimeException ex) { log.warn("Bulk attachment expiry cleanup failed: {}",id,ex); }
        }
    }

    private Long writer(Long customer, String type, Long actor, Long expected) {
        if ("USER".equals(type)) {
            if (!customer.equals(actor)) throw missing();
            SupportAssignment assignment = bindings.current(customer);
            return assignment == null ? null : assignment.id();
        }
        if (!SupportOwnershipService.hasAuthority("service_m3_write")) throw new BizException(403, "SUPPORT_WRITE_FORBIDDEN");
        SupportAssignment assignment = ownership.requireWriter(customer, true);
        positiveId(expected);
        if (!expected.equals(assignment.id())) throw conflict();
        return assignment.id();
    }

    private void requireReadable(SupportAttachment row, String type, Long actor) {
        production.requireAllowed(row.customerId());
        requireCustomer(row, type, actor);
        if ("ATTACHED".equals(row.state())) return;
        if (!type.equals(row.uploaderType()) || !actor.equals(row.uploaderId())) throw missing();
        ready(row);
    }

    private void requireCustomer(SupportAttachment row, String type, Long actor) {
        if ("USER".equals(type)) {
            if (!actor.equals(row.customerId())) throw missing();
        } else {
            if (!SupportOwnershipService.hasAuthority("service_m3_read")) throw new BizException(403, "SUPPORT_READ_FORBIDDEN");
            if (!ownership.canRead(actor, row.customerId())) throw missing();
            // A supervisor who uploaded while acting as an advisor also loses the old unsent draft.
            if (!"ATTACHED".equals(row.state()) && "ADMIN".equals(row.uploaderType())) {
                SupportAssignment current = bindings.current(row.customerId());
                if (current == null || !actor.equals(current.agentAdminId())
                        || !Objects.equals(row.assignmentId(), current.id())) throw missing();
            }
        }
    }

    private SupportAttachment locked(String id) {
        validateAttachmentId(id);
        Long customer = mapper.customer(id);
        if (customer == null) throw missing();
        ownership.lockCustomer(customer);
        return find(id);
    }

    private SupportAttachment find(String id) {
        validateAttachmentId(id);
        SupportAttachment row = mapper.find(id);
        if (row == null) throw missing();
        return row;
    }

    private void validateAttachmentId(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw missing();
    }

    private void requireActor(String type, Long actor) { if (!actor(type).equals(actor)) throw missing(); }
    private void ready(SupportAttachment row) {
        if (!"READY".equals(row.state()) || !row.expiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC)))
            throw new BizException(409, "ATTACHMENT_NOT_READY");
    }
    private void remove(SupportAttachment row) {
        // Shared bulk bytes are owned by ASSET cleanup; a customer's cancellation cannot remove them.
        if(row.objectKey().startsWith("private/support-bulk/")) return;
        try { storage.remove(row.objectKey()); } catch (RuntimeException ex) { throw unavailable(); }
    }

    private record BulkAsset(String objectKey,String mime,long bytes,int width,int height,LocalDateTime expiresAt) {}
    private BulkAsset bulkAsset(Map<String,Object> row,Long actor) {
        if(row==null || !(row.get("actorId") instanceof Number owner) || owner.longValue()!=actor) throw missing();
        if(!"READY".equals(row.get("state"))) throw new BizException(409,"ATTACHMENT_NOT_READY");
        var asset=readBulkAsset(row);
        if(!asset.expiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) throw new BizException(409,"ATTACHMENT_NOT_READY");
        var limits=policy();
        if(!limits.available() || !limits.allowedMimeTypes().contains(asset.mime()) || asset.bytes()<=0 || asset.bytes()>limits.maxBytes()
                || asset.width()<=0 || asset.height()<=0 || (long)asset.width()*asset.height()>limits.maxPixels()
                || !asset.objectKey().startsWith("private/support-bulk/")) throw new BizException(422,"ATTACHMENT_POLICY_UNCONFIGURED");
        return asset;
    }
    private BulkAsset readBulkAsset(Map<String,Object> row) {
        try {
            var node=json.readTree(String.valueOf(row.get("assetJson")));
            Object expiry=row.get("expiresAt");
            LocalDateTime expires=expiry instanceof java.sql.Timestamp stamp?stamp.toLocalDateTime():
                    expiry instanceof LocalDateTime date?date:null;
            if(expires==null) throw unavailable();
            return new BulkAsset(node.path("objectKey").asText(),node.path("mime").asText(),node.path("bytes").asLong(),
                    node.path("width").asInt(),node.path("height").asInt(),expires);
        } catch(java.io.IOException ex) { throw unavailable(); }
    }
    private void exists(String key) {
        try { if(!storage.exists(key)) throw unavailable(); } catch(RuntimeException ex) { throw unavailable(); }
    }
    private Map<String,Object> bulkView(String id,BulkAsset asset) {
        return Map.of("assetId",id,"status","READY","mime",asset.mime(),"bytes",asset.bytes(),"width",asset.width(),
                "height",asset.height(),"expiresAt",asset.expiresAt());
    }
    private void command(String type, Long actor, String operation, String key, String id) {
        try { mapper.commandInsert(type, actor, operation, key, id); }
        catch (DuplicateKeyException ex) { throw conflict(); }
    }
    static void token(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{8,128}")) throw new BizException(422, "ATTACHMENT_COMMAND_REQUIRED");
    }
    public static void positiveId(Long id) {
        if (id == null || id <= 0 || id > 9007199254740991L) throw new BizException(422, "SAFE_INTEGER_REQUIRED");
    }
    private static BizException missing() { return new BizException(404, "ATTACHMENT_NOT_FOUND"); }
    private static BizException conflict() { return new BizException(409, "ATTACHMENT_CONFLICT"); }
    private static BizException unavailable() { return new BizException(503, "ATTACHMENT_STORAGE_UNAVAILABLE"); }

    record Encoded(String mime, int width, int height, byte[] content, String rawHash) {}

    static Encoded decode(MultipartFile file, SupportAttachmentPolicy.View limits) {
        if (file == null || file.isEmpty()) throw new BizException(422, "ATTACHMENT_FILE_REQUIRED");
        String name = file.getOriginalFilename();
        if (name != null && (name.contains("/") || name.contains("\\") || name.contains(":")
                || name.contains("..") || name.chars().anyMatch(Character::isISOControl)))
            throw new BizException(422, "ATTACHMENT_FILENAME_INVALID");
        if (file.getSize() > limits.maxBytes()) throw new BizException(413, "ATTACHMENT_TOO_LARGE");
        String declared = file.getContentType();
        if (!limits.allowedMimeTypes().contains(declared)) throw new BizException(415, "ATTACHMENT_TYPE_UNSUPPORTED");
        try (InputStream input = file.getInputStream()) {
            byte[] original = input.readNBytes(Math.toIntExact(limits.maxBytes()) + 1);
            if (original.length > limits.maxBytes()) throw new BizException(413, "ATTACHMENT_TOO_LARGE");
            boolean png = original.length >= 20 && original[0] == (byte) 0x89 && original[1] == 'P'
                    && Arrays.equals(Arrays.copyOfRange(original, original.length - 12, original.length),
                        new byte[]{0,0,0,0,73,69,78,68,(byte)174,66,96,(byte)130});
            boolean jpeg = original.length >= 4 && original[0] == (byte) 0xff && original[1] == (byte) 0xd8
                    && original[original.length - 2] == (byte) 0xff && original[original.length - 1] == (byte) 0xd9;
            if (!(png || jpeg)) throw new BizException(422, "ATTACHMENT_DECODE_FAILED");
            try (var imageInput = new MemoryCacheImageInputStream(new ByteArrayInputStream(original))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) throw new BizException(415, "ATTACHMENT_TYPE_UNSUPPORTED");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(imageInput, false, true);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    String mime = switch (format) { case "png" -> "image/png"; case "jpeg", "jpg" -> "image/jpeg"; default -> ""; };
                    if (!declared.equals(mime) || !limits.allowedMimeTypes().contains(mime))
                        throw new BizException(415, "ATTACHMENT_TYPE_UNSUPPORTED");
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || (long) width * height > limits.maxPixels())
                        throw new BizException(413, "ATTACHMENT_PIXEL_LIMIT");
                    boolean[] warned = {false};
                    reader.addIIOReadWarningListener((source, warning) -> warned[0] = true);
                    BufferedImage decoded = reader.read(0);
                    if (decoded == null || warned[0]) throw new BizException(422, "ATTACHMENT_DECODE_FAILED");
                    // Fresh pixel-only image discards EXIF, comments, ancillary chunks and appended data.
                    BufferedImage clean = new BufferedImage(width, height,
                            "image/jpeg".equals(mime) ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
                    var graphics = clean.createGraphics();
                    try { graphics.drawImage(decoded, 0, 0, null); } finally { graphics.dispose(); decoded.flush(); }
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    if (!ImageIO.write(clean, "image/jpeg".equals(mime) ? "jpeg" : "png", output)) throw unavailable();
                    clean.flush();
                    if (output.size() > limits.maxBytes()) throw new BizException(413, "ATTACHMENT_TOO_LARGE");
                    return new Encoded(mime, width, height, output.toByteArray(), digest(original));
                } finally { reader.dispose(); }
            }
        } catch (IOException ex) { throw new BizException(422, "ATTACHMENT_DECODE_FAILED"); }
    }

    private static String hash(String text) { return digest(text.getBytes(StandardCharsets.UTF_8)); }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
