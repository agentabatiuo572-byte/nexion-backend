package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import ffdd.opsconsole.shared.storage.StorageProperties;
import ffdd.opsconsole.shared.storage.StoredObject;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Test-context-only evidence. Intent, an object name, or a successful HEAD never grants ownership. */
public final class SupportObjectEvidenceLedger {
    enum Kind { AVATAR, ATTACHMENT, BULK_ASSET, CUSTOMER_AVATAR }
    record Request(String suite, String testcase, Kind kind, String actorType, Long actorId,
            Long customerId, Long assignmentId, String clientUploadId, String commandKey,
            String exactKey, String expectedBucket, boolean expectedMissingBucket) {}
    record CustomerInsert(long generatedId, int affectedRows, long referralLookupId) {}
    record Intent(String id, Request request, JsonNode reference) {}
    /** Explicit isolated-test bean only; business contexts do not define it and consume the real environment. */
    record IsolatedEnvironment(Map<String,String> values) {
        IsolatedEnvironment { values=Map.copyOf(values); }
    }
    private record Entry(JsonNode body, JsonNode reference) {}
    private record Located(Kind kind, String id, String key, String table, Map<String,Object> row) {}
    private record Creation(Intent intent, Located located, JsonNode creator, JsonNode putIntent,
            long threadId, boolean transactional) {}
    private static final String NORMAL_BUCKET = "cs-enhance-20261001-private";
    private static final String ENDPOINT = "http://127.0.0.1:19041";
    private static final int MAX_EVENTS = 10000, MAX_EVENT_BYTES = 2 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024, MAX_OBJECT_BYTES = 16L * 1024 * 1024;
    private static final Object FILE_LOCK = new Object();
    private static final Set<String> EVENTS = Set.of("REQUEST_INTENT", "PUT_INTENT", "PUT_RETURNED", "PUT_UNKNOWN",
            "TX_OUTCOME", "HTTP_OUTCOME", "DIRECT_OUTCOME", "REQUEST_UNKNOWN", "CALLBACK_OBSERVATION", "REMOVE_INTENT",
            "REMOVE_RETURNED", "REMOVE_UNKNOWN", "EXACT_HEAD", "CUSTOMER_CREATE_INTENT", "CUSTOMER_CREATED", "CUSTOMER_CREATE_UNKNOWN");
    private static final List<String> TABLES = List.of("nx_admin", "nx_user", "nx_support_admin_avatar_asset",
            "nx_support_attachment", "nx_support_attachment_command", "nx_support_bulk_job", "nx_admin_account_state");
    private static final String USER_COLUMNS = "id,referral_code,created_at,avatar_url";
    private static final String AVATAR_COLUMNS = "id,uploader_id,client_upload_id,idempotency_key,request_hash,mime,byte_count,object_key,state,attached_admin_id,expires_at,created_at";
    private static final String ATTACHMENT_COLUMNS = "id,customer_id,uploader_type,uploader_id,assignment_id,client_upload_id,request_hash,mime,bytes,width,height,object_key,state,expires_at,message_id";
    private static final String BULK_COLUMNS = "id,record_type,actor_id,command_key,client_upload_id,request_hash,asset_json,state,expires_at,created_at,updated_at";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PlatformTransactionManager transactions;
    private final StorageProperties properties;
    private final MinioClient minio;
    private final Map<String,String> environment;
    private final Path phase, directory, contextPath;
    private final JsonNode context, before, contextRef;
    private final Map<String,Intent> active = new ConcurrentHashMap<>();
    private final Map<String,Creation> pending = new ConcurrentHashMap<>();
    private final Map<String,Integer> naturalCompletions = new ConcurrentHashMap<>();
    private volatile ObjectStorageService delegate;

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean SupportObjectEvidenceLedger supportObjectEvidenceLedger(JdbcTemplate jdbc, ObjectMapper json,
                PlatformTransactionManager transactions, StorageProperties properties, MinioClient minio,
                ObjectProvider<IsolatedEnvironment> isolatedEnvironment) {
            IsolatedEnvironment isolated=isolatedEnvironment.getIfAvailable();
            return new SupportObjectEvidenceLedger(jdbc, json, transactions, properties, minio,
                    isolated==null?System.getenv():isolated.values());
        }
        @Bean static BeanPostProcessor objectEvidenceBoundary(ObjectProvider<SupportObjectEvidenceLedger> ledger) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof ObjectStorageService storage)) return bean;
                    require("objectStorageService".equals(name) && bean.getClass() == ObjectStorageService.class,
                            "Exactly the original concrete storage bean may be wrapped");
                    return ledger.getObject().wrap(storage);
                }
            };
        }
    }

    /** The environment overload is used only by resource-isolated tests of this actual implementation. */
    SupportObjectEvidenceLedger(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactions,
            StorageProperties properties, MinioClient minio, Map<String,String> environment) {
        this.jdbc = Objects.requireNonNull(jdbc); this.json = Objects.requireNonNull(json);
        this.transactions = Objects.requireNonNull(transactions); this.properties = Objects.requireNonNull(properties);
        this.minio = Objects.requireNonNull(minio); this.environment = Map.copyOf(environment);
        phase = Path.of(env("CS_ENHANCE_EVIDENCE_DIR")).toAbsolutePath().normalize();
        directory = phase.resolve("object-ledger");
        contextPath = Path.of(env("CS_ENHANCE_ACTOR_CONTEXT")).toAbsolutePath().normalize();
        try {
            plain(phase, true); plain(contextPath, false);
            byte[] bytes = boundedRead(contextPath);
            require(sha(bytes).equals(env("CS_ENHANCE_ACTOR_CONTEXT_SHA256")), "Object actor context hash changed");
            context = json.readTree(bytes); contextRef = reference(contextPath, bytes);
            before = readReference(context.path("objectBefore"), MAX_TOTAL_BYTES);
            validateContext();
            if (!present(directory)) Files.createDirectory(directory);
            plain(directory, true);
            readEntries();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    ObjectStorageService wrap(ObjectStorageService original) {
        require(original != null && original.getClass() == ObjectStorageService.class, "Original concrete storage required");
        synchronized (this) { require(delegate == null, "One storage boundary per context required"); delegate = original; }
        return new Boundary(original, this, minio, properties);
    }

    /** Every public method delegates to the same original instance, including its warmed bucket cache. */
    static final class Boundary extends ObjectStorageService {
        private final ObjectStorageService original;
        private final SupportObjectEvidenceLedger ledger;
        Boundary(ObjectStorageService original, SupportObjectEvidenceLedger ledger, MinioClient minio, StorageProperties properties) {
            super(minio, properties); this.original = original; this.ledger = ledger;
        }
        @Override public StoredObject put(String key, String type, InputStream stream, long size) {
            return ledger.put(key, type, stream, size, original);
        }
        @Override public String presignGet(String key, Duration expiry) { return original.presignGet(key, expiry); }
        @Override public String presignPut(String key, String type, Duration expiry) { return original.presignPut(key, type, expiry); }
        @Override public InputStream get(String key) { return original.get(key); }
        @Override public boolean exists(String key) { return original.exists(key); }
        @Override public void remove(String key) { ledger.remove(key, false, "BUSINESS", original); }
        @Override public void removeQuietly(String key) { ledger.remove(key, true, "BUSINESS", original); }
    }

    Intent request(Request request) {
        Objects.requireNonNull(request); text(request.suite(), "suite"); text(request.testcase(), "testcase");
        Objects.requireNonNull(request.kind()); text(request.actorType(), "actor type"); text(request.expectedBucket(), "expected bucket");
        require(Set.of("ADMIN", "USER", "UNKNOWN").contains(request.actorType()), "Known actor type or explicit UNKNOWN required");
        beforeBusiness();
        // Invalid business requests still reach the real HTTP service; creator evidence is required only at PUT.
        String id = UUID.randomUUID().toString();
        JsonNode ref = append("REQUEST_INTENT", request.suite(), request.testcase(), id, null, null, null,
                map("request", request, "ownership", "UNPROVEN"));
        Intent intent = new Intent(id, request, ref);
        require(active.putIfAbsent(id, intent) == null, "Duplicate request nonce");
        return intent;
    }

    void httpOutcome(Intent intent, int status, JsonNode body) {
        requireIntent(intent); require(status >= 100 && status <= 599, "Actual HTTP status required");
        Integer code = body != null && body.path("code").isIntegralNumber() ? body.path("code").intValue() : null;
        String resultId = resultId(body);
        List<Entry> attempts = forRequest(readEntries(), intent.id(), "PUT_INTENT");
        JsonNode creation = null; String classification; RuntimeException unresolved = null;
        if (status >= 200 && status < 300 && Integer.valueOf(0).equals(code)) {
            try {
                List<Entry> matches = committedCreations(intent.request(), resultId);
                require(matches.size() == 1, "Successful upload/replay requires one exact committed creation");
                creation = matches.get(0).reference();
                classification = attempts.isEmpty() ? "REPLAY" : "CREATED";
                require(attempts.size() <= 1, "Multiple real puts for a single request");
            } catch (RuntimeException failure) { classification = "UNRESOLVED"; unresolved = failure; }
        } else if (code != null && code != 0 && expectedStorageFailure(intent,attempts,readEntries())) classification = "EXPECTED_STORAGE_FAILURE";
        else classification = code != null && attempts.isEmpty() ? "REJECTED" : "UNRESOLVED";
        append("HTTP_OUTCOME", intent, null, null, map("httpStatus", status, "businessCode", code,
                "resultId", resultId, "classification", classification, "creationRef", creation));
        require(active.remove(intent.id(), intent), "HTTP request already closed");
        closePending(intent);
        if (unresolved != null) throw unresolved;
    }

    void requestFailure(Intent intent, Throwable failure) {
        require(intent != null, "Original request intent required"); validateIntentFile(intent);
        try { append("REQUEST_UNKNOWN", intent, null, null, map("failure", failure(failure), "ownership", "UNPROVEN")); }
        catch (Throwable evidenceFailure) { failure.addSuppressed(evidenceFailure); }
        finally { active.remove(intent.id(), intent); closePending(intent); }
    }

    <T> T direct(Intent intent, Callable<T> action) throws Exception {
        requireIntent(intent);
        try {
            T result = action.call();
            List<Entry> entries = readEntries(); List<Entry> attempts = forRequest(entries, intent.id(), "PUT_INTENT");
            String id = resultId(json.valueToTree(map("data", result)));
            JsonNode creation = null; String classification;
            if (attempts.isEmpty()) {
                List<Entry> replay = committedCreations(intent.request(), id);
                require(replay.size() == 1, "Direct replay must return one actual committed object's ID");
                creation = replay.get(0).reference(); classification = "REPLAY";
            } else {
                require(attempts.size() == 1, "Direct request must have exactly one PUT attempt");
                List<Entry> outcomes = forRequest(entries, intent.id(), "TX_OUTCOME");
                require(outcomes.size() == 1, "Direct call lacks one natural transaction outcome");
                String status = outcomes.get(0).body().path("payload").path("status").asText();
                classification = "ROLLED_BACK".equals(status) ? "ROLLED_BACK" : "CREATED";
                if (!"ROLLED_BACK".equals(status)) {
                    require(committed(attempts.get(0), entries), "Direct call did not prove its committed object");
                    creation = attempts.get(0).reference();
                }
            }
            append("DIRECT_OUTCOME", intent, null, null, map("resultId", id, "classification", classification, "creationRef", creation));
            active.remove(intent.id(), intent); closePending(intent); return result;
        } catch (Exception | Error failure) { requestFailure(intent, failure); throw failure; }
    }

    void callbackObservation(Intent intent, int status, String mechanism) {
        require(intent != null, "Original request intent required"); validateIntentFile(intent);
        append("CALLBACK_OBSERVATION", intent, null, null, map("statusCode", status,
                "mechanism", text(mechanism, "callback mechanism"), "actualTransactionOutcomeUnchanged", true));
    }

    long createCustomer(String suite, String testcase, String referral, Callable<CustomerInsert> exactInsert) {
        text(suite, "suite"); text(testcase, "testcase"); text(referral, "referral");
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "Customer creator must own the commit boundary");
        beforeBusiness(); String operation = UUID.randomUUID().toString(); String referralHash = sha(referral.getBytes(StandardCharsets.UTF_8));
        JsonNode[] intentRef = new JsonNode[1];
        try {
            CustomerInsert result = new TransactionTemplate(transactions).execute(status -> {
                Map<String,Object> db = resourceBoundary(NORMAL_BUCKET);
                require(jdbc.queryForList("SELECT " + USER_COLUMNS + " FROM nx_user WHERE referral_code=?", referral).isEmpty(), "Customer referral already exists");
                intentRef[0] = append("CUSTOMER_CREATE_INTENT", suite, testcase, operation, null, null, null,
                        map("referralSha256", referralHash, "beforeAbsent", true, "databaseIdentity", db, "ownership", "UNPROVEN"));
                CustomerInsert inserted;
                try { inserted = exactInsert.call(); } catch (Exception failure) { throw new IllegalStateException("Customer insertion failed", failure); }
                require(inserted != null && inserted.affectedRows() == 1 && inserted.generatedId() > 0
                        && inserted.generatedId() == inserted.referralLookupId(), "Actual single INSERT generated key and referral readback required");
                require(absentId("nx_user", String.valueOf(inserted.generatedId())), "Historical customer cannot be owned");
                Map<String,Object> row = one(jdbc.queryForList("SELECT " + USER_COLUMNS + " FROM nx_user WHERE referral_code=?", referral), "new customer");
                require(number(row, "id") == inserted.generatedId(), "Same transaction customer ID mismatch");
                return inserted;
            });
            require(result != null, "Missing customer transaction result");
            resourceBoundary(NORMAL_BUCKET);
            Map<String,Object> row = one(jdbc.queryForList("SELECT " + USER_COLUMNS + " FROM nx_user WHERE id=?", result.generatedId()), "committed customer");
            require(referral.equals(string(row, "referral_code")), "Committed customer referral mismatch");
            append("CUSTOMER_CREATED", suite, testcase, operation, intentRef[0], null, null,
                    map("generatedId", result.generatedId(), "affectedRows", result.affectedRows(), "referralLookupId", result.referralLookupId(),
                            "referralSha256", referralHash, "transactionOutcome", "COMMIT_RETURNED", "row", row,
                            "intentRef", intentRef[0], "ownership", "EXACT_CURRENT_CREATION"));
            return result.generatedId();
        } catch (RuntimeException | Error failure) {
            try { append("CUSTOMER_CREATE_UNKNOWN", suite, testcase, operation, intentRef[0], null, null,
                    map("failure", failure(failure), "intentRef", intentRef[0], "ownership", "UNPROVEN")); }
            catch (Throwable evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        }
    }

    private StoredObject put(String key, String type, InputStream stream, long size, ObjectStorageService original) {
        try { return guardedPut(key,type,stream,size,original); }
        catch (RuntimeException | Error failure) {
            // Guard rejection must not be mistaken for the original business negative case.
            try {
                List<Entry> entries = readEntries();
                boolean recorded = entries.stream().anyMatch(entry -> "PUT_UNKNOWN".equals(entry.body().path("eventType").asText())
                        && Objects.equals(key,entry.body().path("object").path("key").asText()));
                if (!recorded) append("PUT_UNKNOWN","STORAGE_BOUNDARY","unresolved-put",null,null,null,null,
                        map("delegated",false,"attemptedKey",key,"attemptedBucket",properties.getBucket(),"failure",failure(failure),"ownership","UNPROVEN"));
            } catch (Throwable evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        }
    }

    private StoredObject guardedPut(String key, String type, InputStream stream, long size, ObjectStorageService original) {
        text(key, "exact object key"); require(key.equals(key.trim()), "Exact untrimmed object key required");
        require(size > 0 && size <= MAX_OBJECT_BYTES, "Bounded actual object required");
        beforeBusiness();
        Located located = locate(key);
        Intent intent = correlate(located);
        Request request = intent.request();
        if (located.kind() == Kind.AVATAR) require(number(located.row(),"byte_count") == size && Objects.equals(string(located.row(),"mime"),type), "Avatar SQL content and PUT arguments differ");
        if (located.kind() == Kind.ATTACHMENT) require(number(located.row(),"bytes") == size && Objects.equals(string(located.row(),"mime"),type), "Attachment SQL content and PUT arguments differ");
        if (located.kind() == Kind.BULK_ASSET) {
            JsonNode asset=parse(string(located.row(),"asset_json"));
            require(asset.path("bytes").asLong(-1)==size && Objects.equals(asset.path("mime").asText(),type), "Bulk SQL content and PUT arguments differ");
        }
        Map<String,Object> database = resourceBoundary(request.expectedBucket());
        JsonNode creator = creator(request.actorType(), request.actorId());
        JsonNode customerCreator = request.customerId() == null ? null : customerCreator(request.customerId());
        require(absentId(located.table(), located.id()), "Object row existed in independent before");
        require(before.path("objects").findValuesAsText("key").stream().noneMatch(key::equals), "Object key existed before this run");
        boolean transactional = TransactionSynchronizationManager.isActualTransactionActive();
        require(located.kind() == Kind.CUSTOMER_AVATAR || transactional, "Put evidence must read the actual inserting transaction");
        Map<String,Object> absence;
        if (request.expectedMissingBucket()) {
            require(!NORMAL_BUCKET.equals(request.expectedBucket()), "Negative bucket must be distinct from the isolated normal bucket");
            require(originalWarmed(original), "Genuine missing-bucket attempt requires the original warmed delegate");
            try {
                require(!minio.bucketExists(BucketExistsArgs.builder().bucket(request.expectedBucket()).build()), "Negative bucket actually exists");
            } catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException("Cannot prove the exact negative bucket absent", failure); }
            absence = map("existed", "unknown", "bucketExists", false, "expectedNegative", true,
                    "beforeRef", context.path("objectBefore"), "currentIdentity", database, "customerCreatorRef", customerCreator);
        } else {
            require(NORMAL_BUCKET.equals(request.expectedBucket()) && !original.exists(key), "New exact object key must actually be absent");
            absence = map("existed", false, "expectedNegative", false, "beforeRef", context.path("objectBefore"),
                    "currentIdentity", database, "customerCreatorRef", customerCreator);
        }
        JsonNode object = object(located, request.expectedBucket());
        JsonNode putIntent = append("PUT_INTENT", intent, creator, object,
                map("request", request, "exactRow", located.row(), "rowTable", located.table(), "databaseIdentity", database,
                        "transactionActive", transactional, "before", absence, "contentType", type,
                        "sizeBytes", size, "ownership", "UNPROVEN"));
        Creation creation = new Creation(intent, located, creator, putIntent, Thread.currentThread().getId(), transactional);
        require(pending.putIfAbsent(key, creation) == null, "Object already has a pending put");
        if (transactional) {
            require(TransactionSynchronizationManager.isSynchronizationActive(), "Natural transaction synchronization required");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public int getOrder() { return org.springframework.core.Ordered.HIGHEST_PRECEDENCE; }
                @Override public void afterCompletion(int status) {
                    // Spring clears synchronization before this callback. Record the actual terminal status first,
                    // so the existing lower-order service compensation can use evidence, never a pending guess.
                    append("TX_OUTCOME", intent, creator, object, map("status", transactionName(status), "statusCode", status,
                            "mechanism", "ACTUAL_TRANSACTION_SYNCHRONIZATION", "ownership", "UNPROVEN"));
                    naturalCompletions.put(key,status);
                }
            });
        }
        boolean delegated = false;
        try {
            beforeBusiness(); require(request.expectedBucket().equals(properties.getBucket()), "Storage bucket changed after intent");
            delegated = true;
            StoredObject result = original.put(key, type, stream, size);
            require(!request.expectedMissingBucket(), "Negative bucket put unexpectedly returned; no ownership claim");
            require(result != null && key.equals(result.getObjectKey()) && request.expectedBucket().equals(result.getBucket())
                    && size == result.getSizeBytes(), "Original storage result does not match the exact attempted object");
            Map<String,Object> content = content(original, key);
            require(((Number) content.get("bytes")).longValue() == size, "Actual stored byte count differs from real PUT input size");
            append("PUT_RETURNED", intent, creator, object, map("delegated", true,
                    "returned", map("bucket", result.getBucket(), "objectKey", result.getObjectKey(), "contentType", result.getContentType(), "sizeBytes", result.getSizeBytes()),
                    "content", content, "ownership", "UNPROVEN"));
            if (!transactional) append("TX_OUTCOME", intent, creator, object, map("status", "NON_TRANSACTIONAL_RETURN",
                    "statusCode", null, "mechanism", "ACTUAL_DIRECT_RETURN", "ownership", "UNPROVEN"));
            return result;
        } catch (RuntimeException | Error failure) {
            try { append("PUT_UNKNOWN", intent, creator, object, map("delegated", delegated, "failure", failure(failure), "ownership", "UNPROVEN")); }
            catch (Throwable evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        } finally { if (!transactional) pending.remove(key, creation); }
    }

    private Located locate(String key) {
        List<Located> rows = new ArrayList<>();
        for (Map<String,Object> row : jdbc.queryForList("SELECT " + AVATAR_COLUMNS + " FROM nx_support_admin_avatar_asset WHERE object_key=?", key))
            rows.add(new Located(Kind.AVATAR, string(row,"id"), key, "nx_support_admin_avatar_asset", row));
        for (Map<String,Object> row : jdbc.queryForList("SELECT " + ATTACHMENT_COLUMNS + " FROM nx_support_attachment WHERE object_key=?", key))
            rows.add(new Located(Kind.ATTACHMENT, string(row,"id"), key, "nx_support_attachment", row));
        for (Map<String,Object> row : jdbc.queryForList("SELECT " + BULK_COLUMNS + " FROM nx_support_bulk_job WHERE record_type='ASSET' AND JSON_UNQUOTE(JSON_EXTRACT(asset_json,'$.objectKey'))=?", key)) {
            JsonNode asset = parse(string(row,"asset_json"));
            require(key.equals(asset.path("objectKey").asText()), "Bulk SQL and decoded object key disagree");
            rows.add(new Located(Kind.BULK_ASSET, string(row,"id"), key, "nx_support_bulk_job", row));
        }
        for (Intent intent : active.values()) if (intent.request().kind() == Kind.CUSTOMER_AVATAR && key.equals(intent.request().exactKey())) {
            Long id = intent.request().customerId(); require(id != null && id > 0, "Exact direct customer required");
            Map<String,Object> row = one(jdbc.queryForList("SELECT " + USER_COLUMNS + " FROM nx_user WHERE id=?", id), "direct customer");
            rows.add(new Located(Kind.CUSTOMER_AVATAR, String.valueOf(id), key, "nx_user", row));
        }
        require(rows.size() == 1, "Exactly one actual inserting row/direct customer correlation required");
        return rows.get(0);
    }

    private Intent correlate(Located located) {
        List<Intent> matches = new ArrayList<>();
        List<Entry> entries = readEntries();
        for (Intent intent : active.values()) {
            validateIntentFile(intent);
            require(forRequest(entries, intent.id(), "HTTP_OUTCOME").isEmpty()
                    && forRequest(entries, intent.id(), "REQUEST_UNKNOWN").isEmpty(), "Closed request cannot correlate a new PUT");
            Request request = intent.request();
            if (request.kind() != located.kind()) continue;
            Map<String,Object> row = located.row(); boolean match;
            switch (located.kind()) {
                case AVATAR -> match = "ADMIN".equals(request.actorType()) && sameNumber(request.actorId(), row.get("uploader_id"))
                        && Objects.equals(request.clientUploadId(), string(row,"client_upload_id")) && Objects.equals(request.commandKey(), string(row,"idempotency_key"));
                case ATTACHMENT -> {
                    match = Objects.equals(request.actorType(), string(row,"uploader_type")) && sameNumber(request.actorId(), row.get("uploader_id"))
                            && sameNumber(request.customerId(), row.get("customer_id")) && sameNumber(request.assignmentId(), row.get("assignment_id"))
                            && Objects.equals(request.clientUploadId(), string(row,"client_upload_id"));
                    if (match) {
                        List<Map<String,Object>> commands = jdbc.queryForList("SELECT actor_type,actor_id,operation,command_key,attachment_id FROM nx_support_attachment_command WHERE operation='UPLOAD' AND actor_type=? AND actor_id=? AND command_key=?", request.actorType(), request.actorId(), request.commandKey());
                        require(commands.size() == 1 && located.id().equals(string(commands.get(0),"attachment_id")), "Exact same-transaction attachment command row required");
                        for(JsonNode old:before.path("tables").path("nx_support_attachment_command")) require(!("UPLOAD".equals(old.path("operation").asText())
                                && request.actorType().equals(old.path("actor_type").asText()) && request.actorId()==old.path("actor_id").asLong(-1)
                                && request.commandKey().equals(old.path("command_key").asText())), "Attachment command existed in independent before");
                        row = new LinkedHashMap<>(row); row.put("uploadCommand", commands.get(0));
                        located.row().put("uploadCommand", commands.get(0));
                    }
                }
                case BULK_ASSET -> match = "ADMIN".equals(request.actorType()) && sameNumber(request.actorId(), row.get("actor_id"))
                        && Objects.equals(request.clientUploadId(), string(row,"client_upload_id")) && Objects.equals(request.commandKey(), string(row,"command_key"));
                case CUSTOMER_AVATAR -> match = sameNumber(request.customerId(), row.get("id")) && Objects.equals(request.exactKey(), located.key());
                default -> throw new IllegalStateException("Unrecognized object kind");
            }
            if (match) { require(forRequest(entries, intent.id(), "PUT_INTENT").isEmpty(), "Request already attempted a PUT"); matches.add(intent); }
        }
        require(matches.size() == 1, "Missing or multiple durable request correlations; no PUT permission");
        return matches.get(0);
    }

    private JsonNode creator(String type, Long id) {
        require(id != null && id > 0, "Actual write requires independently created actor");
        if ("USER".equals(type)) return customerCreator(id);
        require("ADMIN".equals(type), "Unknown actor cannot write object bytes");
        require(absentId("nx_admin", String.valueOf(id)), "Historical admin cannot own an object");
        try {
            Path folder = phase.resolve("fixture-actors"); plain(folder, true);
            List<Entry> candidates = new ArrayList<>();
            try (var paths = Files.list(folder)) {
                for (Path path : paths.toList()) {
                    plain(path, false); byte[] bytes = boundedRead(path); JsonNode body = json.readTree(bytes);
                    require(path.getFileName().toString().equals(body.path("operationId").asText()+"-"+body.path("adminId").asText()+".json"), "Noncanonical or incomplete admin creator evidence");
                    if (body.path("adminId").asLong(-1) == id || body.path("actor").path("id").asLong(-1) == id
                            || body.path("create").path("committedReadback").path("id").asLong(-1) == id)
                        candidates.add(new Entry(body, reference(path, bytes)));
                }
            }
            require(candidates.size() == 1, "Exactly one independent admin creator required");
            Entry entry = candidates.get(0); JsonNode body = entry.body();
            require(body.path("schemaVersion").asInt() == 3 && "CREATED".equals(body.path("event").asText())
                    && body.path("ownsActor").asBoolean(false) && body.path("adminId").asLong(-1) == id
                    && body.path("actor").path("id").asLong(-1) == id && contextRef.path("sha256").asText().equals(body.path("contextSha256").asText()), "Exact current admin creation required");
            for (String field : List.of("identity", "candidate", "windowId", "resourceIdentity", "businessDeadline", "hardDeadline", "leaseSha256", "businessReleaseSha256"))
                require(context.path(field).equals(body.path(field)), "Admin creator context mismatch: " + field);
            JsonNode create = body.path("create"); String mechanism = create.path("kind").asText();
            String username=text(body.path("actor").path("username").asText(),"original actor username");
            require(username.equals(body.path("originalUsername").asText())
                    && username.equals(create.path("request").path("username").asText())
                    && username.equals(create.path("committedReadback").path("username").asText()), "Admin creator birth-name evidence contradicts itself");
            String born=text(body.path("actor").path("createdAt").asText(),"original actor birth");
            require(born.equals(body.path("createdAt").asText()) && sameCreationTime(born,create.path("committedReadback").path("created_at")),
                    "Admin creator birth-time evidence contradicts itself");
            require(create.path("successfulExactResponse").asBoolean(false) && create.path("committedReadback").path("id").asLong(-1) == id
                    && Set.of("SQL_INSERT_COMMIT", "HTTP_CREATE_RESPONSE", "A2_APPROVAL_RESPONSE").contains(mechanism), "Actual successful independent admin creation required");
            if ("SQL_INSERT_COMMIT".equals(mechanism)) require(create.path("response").path("generatedKey").asLong(-1) == id
                    && create.path("response").path("affectedRows").asInt(-1) == 1 && "COMMIT_RETURNED".equals(create.path("transactionOutcome").asText()), "Actual SQL admin creation required");
            String operation = body.path("operationId").asText(); requireUuid(operation);
            JsonNode intent = readReference(body.path("intent"));
            require(Path.of(body.path("intent").path("path").asText()).toAbsolutePath().normalize().equals(phase.resolve("fixture-actor-intents").resolve(operation+".json"))
                    && "CREATE_INTENT".equals(intent.path("event").asText()) && operation.equals(intent.path("operationId").asText())
                    && contextRef.path("sha256").asText().equals(intent.path("contextSha256").asText()) && intent.path("request").equals(create.path("request"))
                    && username.equals(intent.path("request").path("username").asText()), "Admin creator intent mismatch");
            strictActorAuxiliary(operation);
            Map<String,Object> current = one(jdbc.queryForList("SELECT id,created_at FROM nx_admin WHERE id=?", id), "current admin");
            require(number(current,"id") == id && string(current,"created_at").equals(body.path("actor").path("createdAt").asText()), "Admin immutable current identity changed");
            return entry.reference();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private void strictActorAuxiliary(String operation) throws IOException {
        for (String folderName : List.of("fixture-actor-intents", "fixture-actor-outcomes")) {
            Path folder = phase.resolve(folderName); if (!present(folder)) continue; plain(folder,true);
            try (var paths = Files.list(folder)) {
                for (Path path : paths.toList()) {
                    plain(path,false); JsonNode body = json.readTree(boundedRead(path));
                    require(path.getFileName().toString().equals(body.path("operationId").asText()+".json"), "Unknown or partial admin creator artifact");
                    if (operation.equals(body.path("operationId").asText())) require(folderName.equals("fixture-actor-intents") && "CREATE_INTENT".equals(body.path("event").asText()), "Contradictory admin creator outcome");
                }
            }
        }
    }

    private JsonNode customerCreator(long id) {
        require(absentId("nx_user", String.valueOf(id)), "Historical customer cannot own an object");
        List<Entry> candidates = readEntries().stream().filter(entry -> entry.body().path("eventType").asText().equals("CUSTOMER_CREATED")
                && entry.body().path("payload").path("generatedId").asLong(-1) == id).toList();
        require(candidates.size() == 1, "Exactly one independent committed customer creation required");
        Entry entry = candidates.get(0); JsonNode payload = entry.body().path("payload");
        require(payload.path("affectedRows").asInt(-1) == 1 && payload.path("referralLookupId").asLong(-1) == id
                && "COMMIT_RETURNED".equals(payload.path("transactionOutcome").asText()), "Customer INSERT and commit not proven");
        JsonNode intent = ledgerEntry(payload.path("intentRef")).body();
        require(intent.path("eventType").asText().equals("CUSTOMER_CREATE_INTENT") && intent.path("requestId").equals(entry.body().path("requestId"))
                && intent.path("payload").path("beforeAbsent").asBoolean(false)
                && intent.path("payload").path("referralSha256").equals(payload.path("referralSha256")), "Customer creation intent mismatch");
        require(forRequest(readEntries(), entry.body().path("requestId").asText(), "CUSTOMER_CREATE_UNKNOWN").isEmpty(), "Unresolved customer creation cannot own objects");
        Map<String,Object> row = one(jdbc.queryForList("SELECT " + USER_COLUMNS + " FROM nx_user WHERE id=?", id), "current customer");
        require(number(row,"id") == id && string(row,"created_at").equals(payload.path("row").path("created_at").asText())
                && sha(string(row,"referral_code").getBytes(StandardCharsets.UTF_8)).equals(payload.path("referralSha256").asText()), "Customer immutable identity changed");
        return entry.reference();
    }

    /** Reuses the caller's already guarded live resources. It starts no server and never calls put. */
    public static void cleanupPersisted(java.sql.Connection connection, MinioClient minio, ObjectMapper json,
            Map<String,String> environment) {
        var dataSource = new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
        var properties = new StorageProperties(); properties.setEndpoint(ENDPOINT); properties.setBucket(NORMAL_BUCKET);
        var ledger = new SupportObjectEvidenceLedger(new JdbcTemplate(dataSource), json,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource), properties, minio, environment);
        ledger.wrap(new ObjectStorageService(minio, properties));
        Set<String> scopes = new LinkedHashSet<>(); List<Runnable> cleanup = new ArrayList<>();
        for (Entry entry : ledger.readEntries()) {
            if (!"REQUEST_INTENT".equals(entry.body().path("eventType").asText())) continue;
            String suite = entry.body().path("suite").asText(), testcase = entry.body().path("testcase").asText();
            if (scopes.add(suite + "\n" + testcase)) cleanup.add(() -> ledger.cleanup(suite, testcase));
        }
        cleanupIndependently(cleanup.toArray(Runnable[]::new));
    }

    void cleanup(String suite, String testcase) {
        beforeResource(); require(delegate != null, "Actual storage boundary is not attached");
        List<Entry> entries = readEntries(); List<Throwable> failures = new ArrayList<>();
        if (entries.stream().anyMatch(entry -> Set.of("PUT_UNKNOWN","REMOVE_UNKNOWN").contains(entry.body().path("eventType").asText())
                && entry.body().path("requestId").isNull())) failures.add(new IllegalStateException("Uncorrelated storage boundary rejection remains unresolved"));
        Set<String> keys = new LinkedHashSet<>(); List<Entry> attempts = new ArrayList<>();
        for (Entry entry : entries) {
            JsonNode body = entry.body();
            if (!suite.equals(body.path("suite").asText()) || !testcase.equals(body.path("testcase").asText())) continue;
            String event = body.path("eventType").asText();
            if ("REQUEST_INTENT".equals(event)) {
                String id = body.path("requestId").asText();
                try {
                    int closed = forRequest(entries,id,"HTTP_OUTCOME").size() + forRequest(entries,id,"DIRECT_OUTCOME").size();
                    require(closed == 1 && forRequest(entries,id,"REQUEST_UNKNOWN").isEmpty(), "Request outcome is missing or unknown");
                } catch (Throwable failure) { failures.add(failure); }
            }
            if ("PUT_INTENT".equals(event)) {
                String key = body.path("object").path("key").asText();
                try { require(keys.add(key), "Multiple PUT intents claim one exact key"); attempts.add(entry); }
                catch (Throwable failure) { failures.add(failure); }
            }
        }
        for (Entry attempt : attempts) {
            try { cleanupOne(attempt, readEntries()); } catch (Throwable failure) { failures.add(failure); }
        }
        throwFailures(failures, "Object cleanup has unresolved actual failures");
    }

    static void cleanupIndependently(Runnable... domains) {
        List<Throwable> failures = new ArrayList<>();
        for (Runnable domain : domains) try { domain.run(); } catch (Throwable failure) { failures.add(failure); }
        throwFailures(failures, "Independent cleanup domains retain failures");
    }

    private void cleanupOne(Entry attempt, List<Entry> entries) {
        JsonNode body = attempt.body(); Intent intent = intent(body); JsonNode object = body.path("object");
        String key = object.path("key").asText(), bucket = object.path("bucket").asText();
        Request request = intent.request(); resourceBoundary(NORMAL_BUCKET);
        if (request.expectedMissingBucket()) {
            require(!NORMAL_BUCKET.equals(bucket), "Negative attempt must not claim the normal bucket");
            try { require(!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()), "Negative bucket exists after failed delegation"); }
            catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException("Cannot read exact negative bucket", failure); }
            append("EXACT_HEAD", intent, null, object, map("exists", false, "reason", "EXPECTED_MISSING_BUCKET",
                    "content", null, "ownership", "UNPROVEN"));
            require(forRequest(entries,intent.id(),"PUT_UNKNOWN").size() == 1
                    && forRequest(entries,intent.id(),"PUT_UNKNOWN").get(0).body().path("payload").path("delegated").asBoolean(false), "Missing-bucket test never reached the real delegate");
            require(onlyTransaction(entries,intent.id()).equals("ROLLED_BACK"), "Negative bucket transaction unresolved");
            return;
        }
        require(NORMAL_BUCKET.equals(bucket) && NORMAL_BUCKET.equals(properties.getBucket()), "Exact cleanup bucket required");
        boolean exists = delegate.exists(key);
        if (!provenAttempt(attempt, entries)) {
            append("EXACT_HEAD", intent, null, object, map("exists", exists, "reason", "UNPROVEN_TRANSACTION_READBACK",
                    "content", null, "ownership", "UNPROVEN"));
            throw new IllegalStateException("Unknown object attempt cannot be adopted for cleanup, even when currently absent");
        }
        currentCreation(attempt, entries);
        if (exists) {
            remove(key, false, "CLEANUP", delegate);
        } else append("EXACT_HEAD", intent, body.path("creatorRef"), object,
                map("exists", false, "reason", "CLEANUP_ALREADY_ABSENT", "content", null, "ownership", ownership(attempt,entries)));
    }

    private void remove(String key, boolean quietly, String reason, ObjectStorageService original) {
        try { guardedRemove(key,quietly,reason,original); }
        catch (RuntimeException | Error failure) {
            try {
                List<Entry> entries = readEntries();
                boolean recorded = entries.stream().anyMatch(entry -> "REMOVE_UNKNOWN".equals(entry.body().path("eventType").asText())
                        && Objects.equals(key,entry.body().path("object").path("key").asText()));
                if (!recorded) append("REMOVE_UNKNOWN","STORAGE_BOUNDARY","unresolved-remove",null,null,null,null,
                        map("delegated",false,"method",quietly?"removeQuietly":"remove","attemptedKey",key,"attemptedBucket",properties.getBucket(),"failure",failure(failure),"ownership","UNPROVEN"));
            } catch (Throwable evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        }
    }

    private void guardedRemove(String key, boolean quietly, String reason, ObjectStorageService original) {
        beforeResource();
        List<Entry> entries = readEntries(); List<Entry> candidates = entries.stream()
                .filter(entry -> entry.body().path("eventType").asText().equals("PUT_INTENT") && key.equals(entry.body().path("object").path("key").asText())).toList();
        require(candidates.size() == 1, "Removal requires one exact durable PUT attempt");
        Entry attempt = candidates.get(0); Intent intent = intent(attempt.body());
        Creation live = pending.get(key);
        boolean actualRollback = "BUSINESS".equals(reason) && live != null && live.threadId() == Thread.currentThread().getId()
                && live.transactional() && Integer.valueOf(TransactionSynchronization.STATUS_ROLLED_BACK).equals(naturalCompletions.get(key))
                && "ROLLED_BACK".equals(onlyTransaction(entries,intent.id()));
        if (actualRollback && intent.request().expectedMissingBucket()) {
            negativeRemove(attempt,intent,key,quietly,original); return;
        }
        resourceBoundary(NORMAL_BUCKET);
        Map<String,Object> current = currentCreation(attempt, entries);
        String method = quietly ? "removeQuietly" : "remove";
        JsonNode removeIntent = append("REMOVE_INTENT", intent, attempt.body().path("creatorRef"), attempt.body().path("object"),
                map("method", method, "reason", reason, "creationRef", attempt.reference(), "current", current,
                        "ownership", ownership(attempt,entries)));
        boolean delegated = false;
        try {
            if ("CLEANUP".equals(reason)) clearOwnedAvatarReferences(attempt);
            beforeResource(); delegated = true;
            // removeQuietly self-invokes on the original object; this records the outer call, not a fictional inner interception.
            if (quietly) original.removeQuietly(key); else original.remove(key);
            append("REMOVE_RETURNED", intent, attempt.body().path("creatorRef"), attempt.body().path("object"),
                    map("delegated", true, "method", method, "removeIntent", removeIntent, "returnIsNotAbsenceProof", true));
            beforeResource(); boolean exists = original.exists(key);
            append("EXACT_HEAD", intent, attempt.body().path("creatorRef"), attempt.body().path("object"),
                    map("exists", exists, "reason", "AFTER_" + method, "content", null,
                            "ownership", ownership(attempt,entries)));
            require(!exists, "Real removal returned but exact bytes remain");
        } catch (RuntimeException | Error failure) {
            try { append("REMOVE_UNKNOWN", intent, attempt.body().path("creatorRef"), attempt.body().path("object"),
                    map("delegated", delegated, "method", method, "failure", failure(failure), "ownership", "UNPROVEN")); }
            catch (Throwable evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        }
    }

    private void negativeRemove(Entry attempt,Intent intent,String key,boolean quietly,ObjectStorageService original) {
        String bucket=intent.request().expectedBucket(); resourceBoundary(bucket);
        require(!NORMAL_BUCKET.equals(bucket),"Negative removal cannot target normal bucket");
        try { require(!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()),"Expected negative bucket now exists"); }
        catch(RuntimeException failure){throw failure;}
        catch(Exception failure){throw new IllegalStateException("Cannot recheck negative bucket",failure);}
        String method=quietly?"removeQuietly":"remove";
        JsonNode ref=append("REMOVE_INTENT",intent,attempt.body().path("creatorRef"),attempt.body().path("object"),
                map("method",method,"reason","BUSINESS","creationRef",attempt.reference(),
                        "current",map("bucketExists",false,"expectedNegative",true,"transactionOutcome","ROLLED_BACK"),"ownership","UNPROVEN"));
        try {
            beforeResource(); if(quietly)original.removeQuietly(key);else original.remove(key);
            append("REMOVE_RETURNED",intent,attempt.body().path("creatorRef"),attempt.body().path("object"),
                    map("delegated",true,"method",method,"removeIntent",ref,"returnIsNotAbsenceProof",true));
        } catch(RuntimeException|Error failure) {
            try { append("REMOVE_UNKNOWN",intent,attempt.body().path("creatorRef"),attempt.body().path("object"),
                    map("delegated",true,"method",method,"failure",failure(failure),"ownership","UNPROVEN")); }
            catch(Throwable evidenceFailure){failure.addSuppressed(evidenceFailure);}
            throw failure;
        }
    }

    private Map<String,Object> currentCreation(Entry attempt, List<Entry> entries) {
        Intent intent = intent(attempt.body()); Request request = intent.request();
        require(!request.expectedMissingBucket() && NORMAL_BUCKET.equals(attempt.body().path("object").path("bucket").asText()), "No creation ownership in a negative or different bucket");
        require(provenAttempt(attempt, entries), "Only a known returned put and natural transaction result authorize independent cleanup");
        JsonNode currentCreator = creator(request.actorType(), request.actorId());
        require(currentCreator.equals(attempt.body().path("creatorRef")), "Independent creator proof changed");
        if (request.customerId() != null) require(customerCreator(request.customerId()).equals(attempt.body().path("payload").path("before").path("customerCreatorRef")), "Independent customer proof changed");
        String key = attempt.body().path("object").path("key").asText();
        require(before.path("objects").findValuesAsText("key").stream().noneMatch(key::equals), "Historical object key cannot be cleaned");
        List<Entry> returned = forRequest(entries,intent.id(),"PUT_RETURNED");
        require(returned.size() == 1 && forRequest(entries,intent.id(),"PUT_UNKNOWN").isEmpty(), "Unambiguous returned PUT required");
        String table = attempt.body().path("payload").path("rowTable").asText(), id = attempt.body().path("object").path("id").asText();
        List<Map<String,Object>> rows = currentRows(table, id);
        boolean rolledBack = "ROLLED_BACK".equals(onlyTransaction(entries,intent.id()));
        require(rolledBack ? rows.isEmpty() : rows.size() == 1, "Exact current creation row contradicts its natural transaction outcome");
        require(rows.size() <= 1, "Multiple current rows for exact object identity");
        if (!rows.isEmpty()) compareImmutable(table, rows.get(0), attempt.body().path("payload").path("exactRow"));
        List<Map<String,Object>> references = jdbc.queryForList("SELECT " + ATTACHMENT_COLUMNS + " FROM nx_support_attachment WHERE object_key=? ORDER BY id", key);
        for (Map<String,Object> ref : references) {
            require(absentId("nx_support_attachment",string(ref,"id")), "Preexisting shared object reference cannot be cleaned");
            customerCreator(number(ref,"customer_id")); creator(string(ref,"uploader_type"),number(ref,"uploader_id"));
            require(number(ref,"uploader_id") == request.actorId() && request.actorType().equals(string(ref,"uploader_type")), "Shared key has a foreign uploader");
        }
        List<Map<String,Object>> avatarReferences = new ArrayList<>();
        if ("AVATAR".equals(attempt.body().path("object").path("kind").asText())) {
            for (Map<String,Object> ref : jdbc.queryForList("SELECT admin_id,avatar_asset_id,avatar_version FROM nx_admin_account_state WHERE avatar_asset_id=?", id)) {
                JsonNode owner=creator("ADMIN",number(ref,"admin_id"));
                avatarReferences.add(map("row",ref,"creatorRef",owner));
            }
        }
        boolean exists = delegate.exists(key); Map<String,Object> content = exists ? content(delegate,key) : null;
        if (exists) require(canonical(content).equals(returned.get(0).body().path("payload").path("content")), "Current exact bytes changed; no delete permission");
        require(!rolledBack || (references.isEmpty()&&avatarReferences.isEmpty()), "Rolled-back object has unexpected committed references");
        return map("row", rows.isEmpty() ? null : rows.get(0), "references", references,"avatarReferences",avatarReferences, "exists", exists,
                "content", content, "pendingNaturalTransaction", false,"transactionOutcome",onlyTransaction(entries,intent.id()));
    }

    private void clearOwnedAvatarReferences(Entry attempt) {
        JsonNode object = attempt.body().path("object"); String id = object.path("id").asText(), key = object.path("key").asText();
        if (object.path("kind").asText().equals("AVATAR")) {
            for (Map<String,Object> row : jdbc.queryForList("SELECT admin_id,avatar_asset_id,avatar_version FROM nx_admin_account_state WHERE avatar_asset_id=?", id)) {
                creator("ADMIN",number(row,"admin_id")); beforeResource();
                require(jdbc.update("UPDATE nx_admin_account_state SET avatar_asset_id=NULL,avatar_version=avatar_version+1 WHERE admin_id=? AND avatar_asset_id=? AND avatar_version=?",
                        row.get("admin_id"),id,row.get("avatar_version")) == 1, "Exact owned avatar reference CAS failed");
            }
        } else if (object.path("kind").asText().equals("CUSTOMER_AVATAR")) {
            customerCreator(Long.parseLong(id)); Map<String,Object> row = one(jdbc.queryForList("SELECT " + USER_COLUMNS + " FROM nx_user WHERE id=?",id),"current avatar customer");
            JsonNode old = attempt.body().path("payload").path("exactRow").path("avatar_url");
            Object previous = old.isNull() || old.isMissingNode() ? null : old.asText();
            if (Objects.equals(key,row.get("avatar_url"))) {
                beforeResource(); require(jdbc.update("UPDATE nx_user SET avatar_url=? WHERE id=? AND avatar_url=? AND created_at=?", previous,id,key,row.get("created_at")) == 1, "Exact owned customer avatar CAS failed");
            } else require(Objects.equals(previous,row.get("avatar_url")), "Customer avatar reference changed to an unrelated key");
        }
    }

    private List<Entry> committedCreations(Request request, String resultId) {
        require(resultId != null && !resultId.isBlank(), "Actual returned object ID required");
        List<Entry> entries = readEntries(); List<Entry> matches = new ArrayList<>();
        for (Entry entry : entries) {
            if (!"PUT_INTENT".equals(entry.body().path("eventType").asText()) || !resultId.equals(entry.body().path("object").path("id").asText())) continue;
            Request original = intent(entry.body()).request();
            boolean same = original.kind() == request.kind() && Objects.equals(original.actorType(), request.actorType())
                    && Objects.equals(original.actorId(), request.actorId()) && Objects.equals(original.customerId(), request.customerId())
                    && Objects.equals(original.assignmentId(), request.assignmentId()) && Objects.equals(original.clientUploadId(), request.clientUploadId())
                    && Objects.equals(original.expectedBucket(), request.expectedBucket());
            if (request.kind() != Kind.ATTACHMENT) same = same && Objects.equals(original.commandKey(), request.commandKey());
            if (same && committed(entry, entries)) matches.add(entry);
        }
        return matches;
    }

    private boolean committed(Entry attempt, List<Entry> entries) {
        String id = attempt.body().path("requestId").asText();
        if (attempt.body().path("payload").path("before").path("expectedNegative").asBoolean(true)) return false;
        if (forRequest(entries,id,"PUT_RETURNED").size() != 1 || !forRequest(entries,id,"PUT_UNKNOWN").isEmpty()) return false;
        List<Entry> outcomes = forRequest(entries,id,"TX_OUTCOME"); if (outcomes.size() != 1) return false;
        String status = outcomes.get(0).body().path("payload").path("status").asText();
        return "COMMITTED".equals(status) || ("NON_TRANSACTIONAL_RETURN".equals(status)
                && "CUSTOMER_AVATAR".equals(attempt.body().path("object").path("kind").asText()));
    }

    private boolean provenAttempt(Entry attempt, List<Entry> entries) {
        if (committed(attempt,entries)) return true;
        String id=attempt.body().path("requestId").asText();
        return !attempt.body().path("payload").path("before").path("expectedNegative").asBoolean(true)
                && forRequest(entries,id,"PUT_RETURNED").size()==1 && forRequest(entries,id,"PUT_UNKNOWN").isEmpty()
                && forRequest(entries,id,"TX_OUTCOME").size()==1 && "ROLLED_BACK".equals(onlyTransaction(entries,id));
    }

    private String ownership(Entry attempt,List<Entry> entries) { return committed(attempt,entries)?"EXACT_CURRENT_CREATION":"EXACT_PUT_ATTEMPT"; }

    private String onlyTransaction(List<Entry> entries, String requestId) {
        List<Entry> outcomes = forRequest(entries,requestId,"TX_OUTCOME");
        require(outcomes.size() == 1, "One natural transaction result required");
        return outcomes.get(0).body().path("payload").path("status").asText();
    }

    private boolean expectedStorageFailure(Intent intent,List<Entry> attempts,List<Entry> entries) {
        if(!intent.request().expectedMissingBucket() || attempts.size()!=1) return false;
        List<Entry> unknown=forRequest(entries,intent.id(),"PUT_UNKNOWN"), outcomes=forRequest(entries,intent.id(),"TX_OUTCOME");
        return unknown.size()==1 && unknown.get(0).body().path("payload").path("delegated").asBoolean(false)
                && outcomes.size()==1 && "ROLLED_BACK".equals(outcomes.get(0).body().path("payload").path("status").asText())
                && attempts.get(0).body().path("payload").path("before").path("expectedNegative").asBoolean(false)
                && attempts.get(0).body().path("payload").path("before").path("bucketExists").isBoolean()
                && !attempts.get(0).body().path("payload").path("before").path("bucketExists").asBoolean(true)
                && forRequest(entries,intent.id(),"PUT_RETURNED").isEmpty();
    }

    private List<Map<String,Object>> currentRows(String table, String id) {
        return switch (table) {
            case "nx_support_admin_avatar_asset" -> jdbc.queryForList("SELECT " + AVATAR_COLUMNS + " FROM nx_support_admin_avatar_asset WHERE id=?", id);
            case "nx_support_attachment" -> jdbc.queryForList("SELECT " + ATTACHMENT_COLUMNS + " FROM nx_support_attachment WHERE id=?", id);
            case "nx_support_bulk_job" -> jdbc.queryForList("SELECT " + BULK_COLUMNS + " FROM nx_support_bulk_job WHERE record_type='ASSET' AND id=?", id);
            case "nx_user" -> jdbc.queryForList("SELECT " + USER_COLUMNS + " FROM nx_user WHERE id=?", id);
            default -> throw new IllegalStateException("Unrecognized exact object table");
        };
    }

    private void compareImmutable(String table, Map<String,Object> current, JsonNode original) {
        Set<String> mutable = switch (table) {
            case "nx_support_admin_avatar_asset" -> Set.of("state","attached_admin_id","expires_at");
            case "nx_support_attachment" -> Set.of("state","expires_at","message_id","uploadCommand");
            case "nx_support_bulk_job" -> Set.of("state","expires_at","updated_at");
            case "nx_user" -> Set.of("avatar_url");
            default -> throw new IllegalStateException("Unrecognized exact object table");
        };
        JsonNode now = canonical(current);
        var fields = original.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next(); if (mutable.contains(field)) continue;
            require(now.path(field).equals(original.path(field)), "Current immutable object row changed: " + field);
        }
    }

    private Map<String,Object> resourceBoundary(String expectedBucket) {
        beforeResource(); verifyImmutableInputs();
        require(ENDPOINT.equals(properties.getEndpoint()) && expectedBucket.equals(properties.getBucket()), "Actual storage properties no longer match this exact request");
        Map<String,Object> db = one(jdbc.queryForList("SELECT DATABASE() AS database_name,@@port AS database_port,@@server_uuid AS server_uuid,CONNECTION_ID() AS connection_id"), "database identity");
        JsonNode expected = before.path("databaseIdentity");
        require("cs_enhance_20261001".equals(string(db,"database_name")) && number(db,"database_port") == 33329
                && expected.path("database").asText().equals(string(db,"database_name")) && expected.path("port").asInt(-1) == number(db,"database_port")
                && expected.path("serverUuid").asText().equals(string(db,"server_uuid")) && number(db,"connection_id") > 0, "Actual database identity changed");
        List<JsonNode> markers = new ArrayList<>();
        for (JsonNode object : before.path("objects")) if (NORMAL_BUCKET.equals(object.path("bucket").asText()) && "preparation/ownership.txt".equals(object.path("key").asText())) markers.add(object);
        require(markers.size() == 1, "Independent baseline ownership marker is missing or duplicated");
        try (InputStream marker = minio.getObject(GetObjectArgs.builder().bucket(NORMAL_BUCKET).object("preparation/ownership.txt").build())) {
            Map<String,Object> actual = digest(marker, 65536);
            require(((Number) actual.get("bytes")).longValue() == markers.get(0).path("size").asLong(-1)
                    && actual.get("sha256").equals(markers.get(0).path("sha256").asText()), "Actual storage ownership marker changed");
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("Actual storage marker cannot be read", failure); }
        return map("database",string(db,"database_name"),"port",number(db,"database_port"),
                "serverUuid",string(db,"server_uuid"),"connectionId",String.valueOf(number(db,"connection_id")));
    }

    private void validateContext() {
        require(context.path("schemaVersion").asInt(-1) == 1 && "BUSINESS_PHASE".equals(context.path("purpose").asText())
                && context.path("businessAuthorized").asBoolean(false), "Current admitted business actor context required");
        for (var field : Map.of("taskId","WORKFLOW_TASK_ID","stepId","WORKFLOW_STEP_ID","checkId","WORKFLOW_CHECK_ID",
                "runId","WORKFLOW_RUN_ID","repo","WORKFLOW_REPO","snapshotHash","WORKFLOW_SNAPSHOT_HASH").entrySet())
            require(env(field.getValue()).equals(context.path("identity").path(field.getKey()).asText()), "Raw Native context mismatch: " + field.getKey());
        require(context.path("candidate").asText().matches("[0-9a-f]{40}")
                && context.path("identity").path("snapshotHash").asText().matches("[0-9a-f]{64}"), "Actual candidate and snapshot required");
        for (String field : List.of("leaseSha256","sourceHandoffSha256","businessReleaseSha256","preflightManifestSha256","rootAcceptanceSha256","preCaptureContextSha256"))
            require(context.path(field).asText().matches("[0-9a-f]{64}"), "Missing authority binding: " + field);
        text(context.path("windowId").asText(), "lease window");
        JsonNode resource = context.path("resourceIdentity");
        require("cs_enhance_20261001".equals(resource.path("database").asText()) && resource.path("databasePort").asInt(-1) == 33329
                && "127.0.0.1".equals(resource.path("redisHost").asText()) && resource.path("redisPort").asInt(-1) == 16341
                && resource.path("redisDatabase").asInt(-1) == 0 && ENDPOINT.equals(resource.path("storageEndpoint").asText())
                && NORMAL_BUCKET.equals(resource.path("storageBucket").asText()), "Exact isolated resource identity required");
        require(before.path("schemaVersion").asInt(-1) == 1 && "R20_OBJECT_BEFORE".equals(before.path("event").asText())
                && before.path("complete").asBoolean(false) && before.path("objects").isArray(), "Complete independent before required");
        for (String field : List.of("candidate","windowId","resourceIdentity")) require(context.path(field).equals(before.path(field)), "Object before context mismatch: " + field);
        for (String field : List.of("taskId","stepId","runId","repo","snapshotHash"))
            require(context.path("identity").path(field).equals(before.path("identity").path(field)), "Object before Native binding mismatch: " + field);
        require(Instant.parse(before.path("at").asText()).isBefore(Instant.now()), "Independent before must precede object ledger");
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode object : before.path("objects")) require(NORMAL_BUCKET.equals(object.path("bucket").asText())
                && keys.add(text(object.path("key").asText(),"baseline key")) && object.path("size").canConvertToLong()
                && object.path("size").asLong() >= 0 && object.path("sha256").asText().matches("[0-9a-f]{64}"), "Malformed/duplicate independent object before");
        for (String table : TABLES) require(before.path("tables").path(table).isArray(), "Missing independent table before: " + table);
        require(before.path("databaseIdentity").path("database").asText().equals("cs_enhance_20261001")
                && before.path("databaseIdentity").path("port").asInt(-1) == 33329
                && !before.path("databaseIdentity").path("serverUuid").asText().isBlank(), "Independent database identity required");
        beforeResource();
    }

    private void verifyImmutableInputs() {
        try {
            require(reference(contextPath, boundedRead(contextPath)).equals(contextRef), "Context bytes changed during operation");
            require(readReference(context.path("objectBefore"),MAX_TOTAL_BYTES).equals(before), "Independent before bytes changed");
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private boolean absentId(String table, String id) {
        require(TABLES.contains(table) && before.path("tables").path(table).isArray(), "Known complete baseline table required");
        for (JsonNode row : before.path("tables").path(table)) if (id.equals(row.path("id").asText())) return false;
        return true;
    }

    private JsonNode append(String event, Intent intent, JsonNode creator, JsonNode object, Map<String,Object> payload) {
        return append(event,intent.request().suite(),intent.request().testcase(),intent.id(),intent.reference(),creator,object,payload);
    }

    private JsonNode append(String event, String suite, String testcase, String requestId, JsonNode requestRef,
            JsonNode creatorRef, JsonNode object, Map<String,Object> payload) {
        require(EVENTS.contains(event), "Unknown object evidence event"); beforeResource(); verifyImmutableInputs();
        synchronized (FILE_LOCK) {
            try {
                plain(directory,true); Path lockPath = directory.resolve("ledger.lock");
                if (present(lockPath)) plain(lockPath,false);
                try (FileChannel channel = FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                        var lock = channel.tryLock()) {
                    require(lock != null, "Concurrent evidence writer owns ledger lock"); plain(lockPath,false);
                    List<Entry> old = readEntries(); require(old.size() < MAX_EVENTS, "Object evidence event bound reached");
                    String id = UUID.randomUUID().toString(); int sequence = old.size()+1;
                    Map<String,Object> body = map("schemaVersion",1,"event","R20_OBJECT_LEDGER","eventType",event,"sequence",sequence,
                            "eventId",id,"previous",old.isEmpty()?null:old.get(old.size()-1).reference(),"at",Instant.now().toString(),
                            "context",contextRef,"objectBefore",context.path("objectBefore"),"suite",suite,"testcase",testcase,
                            "requestId",requestId,"requestRef",requestRef,"creatorRef",creatorRef,"object",object,"payload",payload);
                    for (String field : List.of("identity","candidate","windowId","leaseSha256","sourceHandoffSha256","resourceIdentity","businessDeadline","hardDeadline")) body.put(field,context.path(field));
                    byte[] bytes = json.writeValueAsBytes(normalize(body)); require(bytes.length <= MAX_EVENT_BYTES, "Object evidence event too large");
                    long total = bytes.length; for (Entry entry : old) total += entry.reference().path("bytes").asLong();
                    require(total <= MAX_TOTAL_BYTES, "Object evidence total bound reached");
                    Path target = directory.resolve(String.format(java.util.Locale.ROOT,"%08d-%s.json",sequence,id));
                    Path temp = directory.resolve(target.getFileName()+".pending");
                    require(!present(target) && !present(temp), "Evidence path already exists");
                    try (FileChannel output = FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                        ByteBuffer buffer = ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) output.write(buffer); output.force(true);
                    }
                    Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE); plain(target,false);
                    byte[] captured = boundedRead(target); require(java.util.Arrays.equals(bytes,captured), "Durable event bytes changed");
                    return reference(target,captured);
                }
            } catch (IOException failure) { throw new UncheckedIOException(failure); }
        }
    }

    private List<Entry> readEntries() {
        synchronized(FILE_LOCK) { return readEntriesUnlocked(); }
    }

    private List<Entry> readEntriesUnlocked() {
        try {
            plain(directory,true); List<Path> paths;
            try (var stream = Files.list(directory)) { paths = stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList(); }
            List<Entry> entries = new ArrayList<>(); long total = 0; JsonNode previous = null;
            for (Path path : paths) {
                plain(path,false); String name = path.getFileName().toString();
                if ("ledger.lock".equals(name)) { require(Files.size(path) == 0,"Unexpected lock file data"); continue; }
                require(name.matches("[0-9]{8}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json"), "Unknown or partial object evidence artifact");
                require(entries.size() < MAX_EVENTS, "Object evidence count bound exceeded");
                byte[] bytes = boundedRead(path); total += bytes.length; require(total <= MAX_TOTAL_BYTES,"Object evidence byte bound exceeded");
                JsonNode body = json.readTree(bytes); int sequence = entries.size()+1;
                require(body != null && body.isObject() && body.path("schemaVersion").asInt(-1) == 1 && "R20_OBJECT_LEDGER".equals(body.path("event").asText())
                        && EVENTS.contains(body.path("eventType").asText()) && body.path("sequence").asInt(-1) == sequence
                        && name.equals(String.format(java.util.Locale.ROOT,"%08d-%s.json",sequence,body.path("eventId").asText())), "Canonical ordered evidence event required");
                require(previous == null ? body.path("previous").isNull() : previous.equals(body.path("previous")), "Object evidence chain broken");
                for (String field : List.of("identity","candidate","windowId","leaseSha256","sourceHandoffSha256","resourceIdentity","businessDeadline","hardDeadline"))
                    require(context.path(field).equals(body.path(field)),"Object event current context mismatch: "+field);
                require(contextRef.equals(body.path("context")) && context.path("objectBefore").equals(body.path("objectBefore")), "Object event has another context/baseline");
                require(!Instant.parse(body.path("at").asText()).isAfter(Instant.now())
                        && Instant.parse(body.path("at").asText()).isBefore(Instant.parse(context.path("hardDeadline").asText())), "Object event outside hard lease");
                previous = reference(path,bytes); entries.add(new Entry(body,previous));
            }
            return entries;
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private JsonNode readReference(JsonNode ref) {
        return readReference(ref,MAX_EVENT_BYTES);
    }

    private JsonNode readReference(JsonNode ref,long bound) {
        try {
            require(ref.isObject() && ref.path("sha256").asText().matches("[0-9a-f]{64}"), "Exact evidence reference required");
            Path path = Path.of(text(ref.path("path").asText(),"evidence path")).toAbsolutePath().normalize(); plain(path,false);
            byte[] bytes = boundedRead(path,bound); require(sha(bytes).equals(ref.path("sha256").asText()),"Evidence reference changed");
            if (ref.has("bytes")) require(ref.path("bytes").canConvertToLong() && bytes.length == ref.path("bytes").asLong(),"Evidence reference size changed");
            JsonNode value = json.readTree(bytes); require(value != null && value.isObject(),"Object evidence document required"); return value;
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private void requireIntent(Intent intent) { require(intent != null && active.get(intent.id()) == intent, "An open durable request intent is required"); validateIntentFile(intent); }
    private void validateIntentFile(Intent intent) {
        JsonNode body = ledgerEntry(intent.reference()).body();
        require("REQUEST_INTENT".equals(body.path("eventType").asText()) && intent.id().equals(body.path("requestId").asText())
                && contextRef.equals(body.path("context")) && canonical(intent.request()).equals(body.path("payload").path("request")), "Durable request changed");
    }
    private Entry ledgerEntry(JsonNode reference) {
        List<Entry> matches=readEntries().stream().filter(entry->entry.reference().equals(reference)).toList();
        require(matches.size()==1,"Reference must identify one exact event in this current ledger chain");return matches.get(0);
    }
    private Intent intent(JsonNode event) {
        JsonNode body = readReference(event.path("requestRef"));
        try {
            Intent intent = new Intent(body.path("requestId").asText(),json.treeToValue(body.path("payload").path("request"),Request.class),event.path("requestRef"));
            validateIntentFile(intent); return intent;
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    private static List<Entry> forRequest(List<Entry> entries, String id, String event) {
        return entries.stream().filter(entry -> id.equals(entry.body().path("requestId").asText()) && event.equals(entry.body().path("eventType").asText())).toList();
    }
    private void closePending(Intent intent) {
        for(var entry:pending.entrySet()) if(entry.getValue().intent().id().equals(intent.id()) && pending.remove(entry.getKey(),entry.getValue())) naturalCompletions.remove(entry.getKey());
    }
    private JsonNode object(Located located, String bucket) { return json.valueToTree(map("kind",located.kind().name(),"id",located.id(),"key",located.key(),"bucket",bucket)); }
    private JsonNode reference(Path path, byte[] bytes) { return json.valueToTree(map("path",path.toAbsolutePath().normalize().toString(),"sha256",sha(bytes),"bytes",bytes.length)); }
    private JsonNode parse(String value) { try { return json.readTree(value); } catch(IOException failure) { throw new UncheckedIOException(failure); } }
    private JsonNode canonical(Object value) {
        // Compare the actual persisted JSON representation: valueToTree(Long) and readTree(small integer)
        // use different Jackson numeric node classes even when their exact integer values are identical.
        try { return json.readTree(json.writeValueAsBytes(normalize(value))); }
        catch(IOException failure) { throw new UncheckedIOException(failure); }
    }
    private JsonNode rawMapperTree(Object value) {
        // SupportFixtureActors.persist serializes JDBC values with this mapper directly, before ledger normalization.
        try { return json.readTree(json.writeValueAsBytes(value)); }
        catch(IOException failure) { throw new UncheckedIOException(failure); }
    }
    private boolean sameCreationTime(String born,JsonNode readback) {
        try {
            java.time.LocalDateTime local=java.time.LocalDateTime.parse(born.replace(' ','T'));
            if(readback.isTextual()) {
                try { if(local.equals(java.time.LocalDateTime.parse(readback.asText().replace(' ','T'))))return true; }
                catch(java.time.format.DateTimeParseException differentRepresentation) { /* Compare the actual mapper representation below. */ }
            }
            // JDBC may expose Timestamp or LocalDateTime. Recreate their actual configured JSON representation,
            // retaining the independent full-precision born string and the exact current-row birth comparison.
            return rawMapperTree(java.sql.Timestamp.valueOf(local)).equals(readback) || rawMapperTree(local).equals(readback);
        } catch(java.time.format.DateTimeParseException invalidBirth) { return false; }
    }
    private void beforeBusiness() { beforeResource(); require(Instant.now().isBefore(Instant.parse(context.path("businessDeadline").asText())),"Object business deadline reached"); }
    private void beforeResource() { require(Instant.now().isBefore(Instant.parse(context.path("hardDeadline").asText())),"Object hard resource deadline reached"); }
    private String env(String name) { return text(environment.get(name),name); }
    private static String resultId(JsonNode body) {
        if (body == null || !body.path("data").isObject()) return null;
        for (String name : List.of("assetId","attachmentId","id")) if (!body.path("data").path(name).asText().isBlank()) return body.path("data").path(name).asText();
        return null;
    }
    private static Map<String,Object> one(List<Map<String,Object>> rows, String what) { require(rows.size()==1,"Exactly one "+what+" required"); return rows.get(0); }
    private static boolean sameNumber(Long expected, Object actual) { return expected == null ? actual == null : actual instanceof Number n && expected == n.longValue(); }
    private static long number(Map<String,Object> row, String field) { Object value = row.get(field); require(value instanceof Number,"Actual numeric SQL field required: "+field); return ((Number)value).longValue(); }
    private static String string(Map<String,Object> row, String field) { Object value=row.get(field); return value == null ? null : String.valueOf(value); }
    private static Map<String,Object> failure(Throwable failure) { return map("type",failure.getClass().getName()); }
    private static String transactionName(int status) { return status == TransactionSynchronization.STATUS_COMMITTED ? "COMMITTED" : status == TransactionSynchronization.STATUS_ROLLED_BACK ? "ROLLED_BACK" : "UNKNOWN"; }
    private static boolean originalWarmed(ObjectStorageService original) {
        try { var field = ObjectStorageService.class.getDeclaredField("bucketReady"); require(field.trySetAccessible(),"Original bucket cache cannot be observed"); return ((AtomicBoolean)field.get(original)).get(); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Original warmed bucket state unavailable",failure); }
    }
    private static Map<String,Object> content(ObjectStorageService storage, String key) {
        try (InputStream input = storage.get(key)) { return digest(input,MAX_OBJECT_BYTES); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    private static Map<String,Object> digest(InputStream input, long bound) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[8192]; long bytes=0; int count;
            while((count=input.read(buffer))!=-1) { bytes+=count; require(bytes<=bound,"Actual object read exceeds evidence bound"); digest.update(buffer,0,count); }
            return map("bytes",bytes,"sha256",HexFormat.of().formatHex(digest.digest()));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static byte[] boundedRead(Path path) throws IOException { return boundedRead(path,MAX_EVENT_BYTES); }
    private static byte[] boundedRead(Path path,long bound) throws IOException {
        plain(path,false); require(Files.size(path)<=bound,"Evidence file exceeds bound");
        try(InputStream input=Files.newInputStream(path)) {
            byte[] bytes=input.readNBytes(Math.toIntExact(bound+1));
            require(bytes.length<=bound&&Files.size(path)==bytes.length,"Evidence grew or changed while reading"); plain(path,false); return bytes;
        }
    }
    private static boolean present(Path path) throws IOException { try { Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS); return true; } catch(java.nio.file.NoSuchFileException absent) { return false; } }
    private static void plain(Path path, boolean directory) throws IOException { SupportFixtureActors.plainPath(path,directory); }
    private static String sha(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private static void requireUuid(String value) { require(value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),"Exact UUID required"); }
    private static String text(String value,String label) { require(value!=null&&!value.isBlank()&&value.length()<=4096,"Required bounded "+label); return value; }
    private static void require(boolean value,String message) { if(!value) throw new IllegalStateException(message); }
    private static Map<String,Object> map(Object... values) { Map<String,Object> result=new LinkedHashMap<>(); for(int i=0;i<values.length;i+=2) result.put((String)values[i],values[i+1]); return result; }
    private static Object normalize(Object value) {
        if (value instanceof java.util.Date || value instanceof java.time.temporal.TemporalAccessor) return value.toString();
        if (value instanceof JsonNode) return value;
        if (value instanceof Map<?,?> source) { Map<String,Object> result=new LinkedHashMap<>(); source.forEach((key,item)->result.put(String.valueOf(key),normalize(item))); return result; }
        if (value instanceof Iterable<?> source) { List<Object> result=new ArrayList<>(); for(Object item:source)result.add(normalize(item)); return result; }
        return value;
    }
    private static void throwFailures(List<Throwable> failures,String message) { if(!failures.isEmpty()) { AssertionError failure=new AssertionError(message); failures.forEach(failure::addSuppressed); throw failure; } }
}
