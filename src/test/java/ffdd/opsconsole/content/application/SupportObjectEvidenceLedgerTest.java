package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import ffdd.opsconsole.shared.storage.StorageProperties;
import ffdd.opsconsole.shared.storage.StoredObject;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Prepared resource-isolated regression tests of the actual boundary/ledger. Not a real SQL/S3 runtime proof. */
class SupportObjectEvidenceLedgerTest {
    private static final String BUCKET="cs-enhance-20261001-private", CREATED="2026-01-01 00:00:00.123456";
    private static final byte[] BYTES={1,2,3,4,5}, MARKER="isolated test marker".getBytes(StandardCharsets.UTF_8);
    @TempDir Path temporary;
    private final ObjectMapper json=new ObjectMapper();
    private final Map<String,byte[]> stored=new LinkedHashMap<>();
    private Map<String,Object> context,before;
    private Map<String,String> environment;
    private List<Map<String,Object>> avatars=new ArrayList<>();
    private List<Map<String,Object>> users=new ArrayList<>();
    private List<Map<String,Object>> attachments=new ArrayList<>(), attachmentCommands=new ArrayList<>(), bulkAssets=new ArrayList<>();
    private final Map<String,Object> currentAdmin=map("id",100L,"username","isolated-admin","created_at",CREATED);
    private String currentKey="private/admin-avatar/"+UUID.randomUUID(), assetId=UUID.randomUUID().toString();
    private JdbcTemplate jdbc;
    private MinioClient minio;
    private StorageProperties properties;
    private ObjectStorageService original,storage;
    private SupportObjectEvidenceLedger ledger;
    private final TestTransactions transactions=new TestTransactions();
    private AnnotationConfigApplicationContext spring;
    private final AtomicInteger delegatePuts=new AtomicInteger(), delegateRemoves=new AtomicInteger();

    @BeforeEach void prepareIsolatedRealBoundary() throws Exception {
        json.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        Files.createDirectories(temporary.resolve("evidence"));
        Map<String,Object> identity=map("taskId","isolated-unit","stepId","source-test","checkId","unit","runId","isolated-test-run",
                "repo","D:/isolated-source-fixture","snapshotHash","a".repeat(64));
        Map<String,Object> resource=map("database","cs_enhance_20261001","databasePort",33329,"redisHost","127.0.0.1","redisPort",16341,
                "redisDatabase",0,"storageEndpoint","http://127.0.0.1:19041","storageBucket",BUCKET);
        before=map("schemaVersion",1,"event","R20_OBJECT_BEFORE","identity",identity,"candidate","a".repeat(40),"windowId","isolated-window",
                "resourceIdentity",resource,"at","2026-01-01T00:00:00Z","complete",true,
                "databaseIdentity",map("database","cs_enhance_20261001","port",33329,"serverUuid","isolated-server","connectionId","101"),
                "objects",List.of(map("bucket",BUCKET,"key","preparation/ownership.txt","size",MARKER.length,"sha256",sha(MARKER))),
                "tables",map("nx_admin",new ArrayList<>(),"nx_user",new ArrayList<>(),"nx_support_admin_avatar_asset",new ArrayList<>(),
                        "nx_support_attachment",new ArrayList<>(),"nx_support_attachment_command",new ArrayList<>(),"nx_support_bulk_job",new ArrayList<>(),"nx_admin_account_state",new ArrayList<>()));
        JsonNode beforeRef=save(temporary.resolve("object-before.json"),before);
        context=map("schemaVersion",1,"purpose","BUSINESS_PHASE","businessAuthorized",true,"identity",identity,"candidate","a".repeat(40),
                "windowId","isolated-window","resourceIdentity",resource,"objectBefore",beforeRef,
                "businessDeadline",Instant.now().plusSeconds(3600).toString(),"hardDeadline",Instant.now().plusSeconds(7200).toString());
        for(String field:List.of("leaseSha256","sourceHandoffSha256","preflightManifestSha256","rootAcceptanceSha256","businessReleaseSha256","preCaptureContextSha256"))context.put(field,"b".repeat(64));
        JsonNode contextRef=save(temporary.resolve("actor-context.json"),context);
        environment=new LinkedHashMap<>();environment.put("CS_ENHANCE_EVIDENCE_DIR",temporary.resolve("evidence").toString());
        environment.put("CS_ENHANCE_ACTOR_CONTEXT",contextRef.path("path").asText());environment.put("CS_ENHANCE_ACTOR_CONTEXT_SHA256",contextRef.path("sha256").asText());
        for(var entry:Map.of("taskId","WORKFLOW_TASK_ID","stepId","WORKFLOW_STEP_ID","checkId","WORKFLOW_CHECK_ID","runId","WORKFLOW_RUN_ID","repo","WORKFLOW_REPO","snapshotHash","WORKFLOW_SNAPSHOT_HASH").entrySet())environment.put(entry.getValue(),identity.get(entry.getKey()).toString());
        jdbc=mock(JdbcTemplate.class,invocation->{
            if(invocation.getMethod().getName().equals("queryForList"))return rows(invocation.getArgument(0),invocation.getArguments());
            if(invocation.getMethod().getName().equals("update"))return 1;
            return RETURNS_DEFAULTS.answer(invocation);
        });
        minio=mock(MinioClient.class);properties=new StorageProperties();properties.setEndpoint("http://127.0.0.1:19041");properties.setBucket(BUCKET);
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenAnswer(call->BUCKET.equals(call.<BucketExistsArgs>getArgument(0).bucket()));
        when(minio.statObject(any(StatObjectArgs.class))).thenAnswer(call->{
            StatObjectArgs args=call.getArgument(0);if(stored.containsKey(args.object()))return mock(StatObjectResponse.class);
            ErrorResponse response=mock(ErrorResponse.class);when(response.code()).thenReturn("NoSuchKey");
            ErrorResponseException absent=mock(ErrorResponseException.class);when(absent.errorResponse()).thenReturn(response);throw absent;
        });
        when(minio.getObject(any(GetObjectArgs.class))).thenAnswer(call->{
            String key=call.<GetObjectArgs>getArgument(0).object();byte[] bytes="preparation/ownership.txt".equals(key)?MARKER:stored.get(key);
            assertThat(bytes).as("fixture exact stored object").isNotNull();var input=new ByteArrayInputStream(bytes);
            GetObjectResponse response=mock(GetObjectResponse.class);when(response.read(any(byte[].class))).thenAnswer(read->input.read(read.getArgument(0)));return response;
        });
        when(minio.putObject(any(PutObjectArgs.class))).thenAnswer(call->{
            PutObjectArgs args=call.getArgument(0);delegatePuts.incrementAndGet();
            JsonNode put=events("PUT_INTENT").get(events("PUT_INTENT").size()-1);
            assertThat(put.path("object").path("key").asText()).isEqualTo(args.object());
            assertThat(put.path("payload").path("transactionActive").asBoolean()).isEqualTo(TransactionSynchronizationManager.isActualTransactionActive());
            stored.put(args.object(),args.stream().readAllBytes());return null;
        });
        doAnswer(call->{delegateRemoves.incrementAndGet();stored.remove(call.<RemoveObjectArgs>getArgument(0).object());return null;}).when(minio).removeObject(any(RemoveObjectArgs.class));
        original=new ObjectStorageService(minio,properties);
        ledger=new SupportObjectEvidenceLedger(jdbc,json,transactions,properties,minio,environment);
        storage=ledger.wrap(original);adminCreator(100L);
        avatar();
    }

    @AfterEach void closeSpringOnly() {if(spring!=null)spring.close();org.springframework.security.core.context.SecurityContextHolder.clearContext();assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();}

    @ParameterizedTest @ValueSource(strings={"SupportAdminAvatarA2RuntimeTest","SupportEnhancementCoreRuntimeTest","SupportS4RuntimeTest",
            "SupportAdminAvatarReadRuntimeTest","SupportAvatarCompensationRuntimeTest","SupportBulkRuntimeTest"})
    void eachActualTargetImportsTheSameConfigurationAndActualPostProcessorWrapsOneConcreteBean(String name) throws Exception {
        Class<?> target=Class.forName("ffdd.opsconsole.content.application."+name,false,getClass().getClassLoader());
        assertThat(target.getAnnotation(Import.class).value()).contains(SupportObjectEvidenceLedger.Configuration.class);
        Class<?> imported=java.util.Arrays.stream(target.getAnnotation(Import.class).value())
                .filter(configuration->configuration==SupportObjectEvidenceLedger.Configuration.class).findFirst().orElseThrow();
        SupportObjectEvidenceLedger prebuilt=ledger;AtomicReference<ObjectStorageService> createdOriginal=new AtomicReference<>();AtomicInteger factoryCalls=new AtomicInteger();
        spring=new AnnotationConfigApplicationContext();
        spring.register(imported);
        spring.registerBean("jdbcTemplate",JdbcTemplate.class,()->jdbc);
        spring.registerBean("objectMapper",ObjectMapper.class,()->json);
        spring.registerBean("transactionManager",org.springframework.transaction.PlatformTransactionManager.class,()->transactions);
        spring.registerBean("storageProperties",StorageProperties.class,()->properties);
        spring.registerBean("minioClient",MinioClient.class,()->minio);
        spring.registerBean("isolatedObjectEvidenceEnvironment",SupportObjectEvidenceLedger.IsolatedEnvironment.class,
                ()->new SupportObjectEvidenceLedger.IsolatedEnvironment(environment));
        spring.registerBean("objectStorageService",ObjectStorageService.class,()->{
            factoryCalls.incrementAndGet();ObjectStorageService service=new ObjectStorageService(minio,properties);createdOriginal.set(service);return service;
        });spring.refresh();
        ledger=spring.getBean(SupportObjectEvidenceLedger.class);storage=spring.getBean(ObjectStorageService.class);
        assertThat(ledger).isNotSameAs(prebuilt);assertThat(spring.getBeansOfType(SupportObjectEvidenceLedger.class)).hasSize(1);
        assertThat(spring.getBeanFactory().getBeanDefinition("supportObjectEvidenceLedger").getFactoryMethodName()).isEqualTo("supportObjectEvidenceLedger");
        assertThat(spring.getBeansOfType(ObjectStorageService.class)).hasSize(1);
        assertThat(storage).isInstanceOf(SupportObjectEvidenceLedger.Boundary.class).isNotSameAs(createdOriginal.get());
        assertThat(factoryCalls).hasValue(1);
        ledger.direct(request(),()->new TransactionTemplate(transactions).execute(status->put()));
        assertThat(delegatePuts).hasValue(1);assertThat(stored.get(currentKey)).containsExactly(BYTES);
    }

    @Test void preparationAndRestartContextsDoNotImportTheObjectLedgerConfiguration() {
        for(Class<?> target:List.of(SupportEnhancementPreparationTest.class,SupportBulkRestartRuntimeTest.class)) {
            Import declaration=target.getAnnotation(Import.class);
            if(declaration!=null)assertThat(declaration.value()).doesNotContain(SupportObjectEvidenceLedger.Configuration.class);
        }
    }

    @Test void requestIntentOnCallerCorrelatesExactSqlRowOnAnotherThreadBeforeRealDelegate() throws Exception {
        var intent=request();var executor=Executors.newSingleThreadExecutor();
        try {
            StoredObject result=executor.submit(()->new TransactionTemplate(transactions).execute(status->put())).get();
            assertThat(result.getBucket()).isEqualTo(BUCKET);assertThat(result.getObjectKey()).isEqualTo(currentKey);
            assertThat(result.getSizeBytes()).isEqualTo(BYTES.length);assertThat(stored.get(currentKey)).containsExactly(BYTES);
        } finally {executor.shutdownNow();}
        ledger.httpOutcome(intent,200,json.valueToTree(map("code",0,"data",map("assetId",assetId))));
        assertThat(events("PUT_INTENT")).hasSize(1);assertThat(events("PUT_RETURNED")).hasSize(1);
        assertThat(events("TX_OUTCOME").get(0).path("payload").path("status").asText()).isEqualTo("COMMITTED");
    }

    @Test void directReplayReturnsExactOriginalResultAndAddsNoSecondCreation() throws Exception {
        var intent=request();var returned=ledger.direct(intent,()->new TransactionTemplate(transactions).execute(status->{put();return map("assetId",assetId);}));
        var retry=request();Object same=ledger.direct(retry,()->returned);
        assertThat(same).isSameAs(returned);assertThat(delegatePuts).hasValue(1);
        assertThat(events("DIRECT_OUTCOME").get(1).path("payload").path("classification").asText()).isEqualTo("REPLAY");
    }

    @Test void originalStorageExceptionIdentitySurvivesEvidenceRecording() throws Exception {
        BizException actual=new BizException("isolated original failure");when(minio.putObject(any(PutObjectArgs.class))).thenThrow(actual);
        var intent=request();assertThatThrownBy(()->ledger.direct(intent,()->new TransactionTemplate(transactions).execute(status->put()))).isSameAs(actual);
        assertThat(events("PUT_UNKNOWN").get(0).path("payload").path("delegated").asBoolean()).isTrue();
        assertThat(events("PUT_RETURNED")).isEmpty();
    }

    @Test void missingBucketReusesWarmOriginalCacheAndReachesTheRealFailingPut() throws Exception {
        ledger.direct(request(),()->new TransactionTemplate(transactions).execute(status->put()));
        currentKey="private/admin-avatar/"+UUID.randomUUID();assetId=UUID.randomUUID().toString();avatar();
        String bucket="missing-isolated-unit";
        var negative=ledger.request(new SupportObjectEvidenceLedger.Request("LedgerTest","case",SupportObjectEvidenceLedger.Kind.AVATAR,"ADMIN",100L,null,null,"client","command",null,bucket,true));
        properties.setBucket(bucket);BizException actual=new BizException("missing isolated bucket");
        when(minio.putObject(any(PutObjectArgs.class))).thenAnswer(call->{assertThat(call.<PutObjectArgs>getArgument(0).bucket()).isEqualTo(bucket);throw actual;});
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->put())).isSameAs(actual);
        properties.setBucket(BUCKET);ledger.httpOutcome(negative,200,json.valueToTree(map("code",500,"data",null)));
        assertThat(events("HTTP_OUTCOME").get(0).path("payload").path("classification").asText()).isEqualTo("EXPECTED_STORAGE_FAILURE");
        verify(minio,never()).makeBucket(any(MakeBucketArgs.class));
        verify(minio,times(1)).bucketExists(argThat(args->BUCKET.equals(args.bucket())));
    }

    @Test void noSqlRowBlocksPutAndRecordsGuardRejectionInsteadOfPretendingBusinessFailure() throws Exception {
        request();avatars.clear();assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->put())).hasMessageContaining("Exactly one actual inserting row");
        verify(minio,never()).putObject(any(PutObjectArgs.class));
        JsonNode event=events("PUT_UNKNOWN").get(0);assertThat(event.path("requestId").isNull()).isTrue();assertThat(event.path("payload").path("delegated").asBoolean()).isFalse();
    }

    @Test void multirowAndMultipleOpenRequestsFailClosedAtPutNotAtRegistration() throws Exception {
        request();request();assertThat(events("REQUEST_INTENT")).hasSize(2);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->put())).hasMessageContaining("multiple durable request");
        verify(minio,never()).putObject(any(PutObjectArgs.class));
    }

    @Test void sameKeyMultipleActualRowsNeverReachDelegate() throws Exception {
        request();avatars.add(new LinkedHashMap<>(avatars.get(0)));
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->put())).hasMessageContaining("Exactly one actual inserting row");
        verify(minio,never()).putObject(any(PutObjectArgs.class));
    }

    @Test void unknownCreatorRequestStillReachesOriginalHttpNegativePathWithoutWritePermission() throws Exception {
        var intent=ledger.request(new SupportObjectEvidenceLedger.Request("LedgerTest","negative",SupportObjectEvidenceLedger.Kind.AVATAR,"UNKNOWN",null,null,null,"bad","bad",null,BUCKET,false));
        ledger.httpOutcome(intent,403,json.valueToTree(map("code",403,"data",null)));
        assertThat(events("HTTP_OUTCOME").get(0).path("payload").path("classification").asText()).isEqualTo("REJECTED");
        verify(minio,never()).putObject(any(PutObjectArgs.class));
    }

    @Test void wrongCreatorWrongBucketAndOldActualKeyCannotBecomeOwned() throws Exception {
        avatars.get(0).put("uploader_id",777L);
        ledger.request(new SupportObjectEvidenceLedger.Request("LedgerTest","case",SupportObjectEvidenceLedger.Kind.AVATAR,"ADMIN",777L,null,null,"client","command",null,BUCKET,false));
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->put())).hasMessageContaining("independent admin creator");
        verify(minio,never()).putObject(any(PutObjectArgs.class));
    }

    @Test void bucketDriftBlocksBeforeDelegate() throws Exception {
        request();properties.setBucket("wrong-bucket");
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->put())).hasMessageContaining("Actual storage properties");
        verify(minio,never()).putObject(any(PutObjectArgs.class));
    }

    @Test void actualPreexistingKeyIsNotAdoptedEvenWithNewRow() throws Exception {
        request();stored.put(currentKey,BYTES);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->put())).hasMessageContaining("actually be absent");
        verify(minio,never()).putObject(any(PutObjectArgs.class));
    }

    @Test void unknownNaturalOutcomeNeverAuthorizesDeleteEvenWhenCurrentRowExists() throws Exception {
        var intent=request();TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
        try {put();for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);}
        finally {TransactionSynchronizationManager.clear();}
        assertThatThrownBy(()->ledger.httpOutcome(intent,200,json.valueToTree(map("code",0,"data",map("assetId",assetId))))).hasMessageContaining("committed creation");
        assertThatThrownBy(()->ledger.cleanup("LedgerTest","case")).isInstanceOf(AssertionError.class);
        verify(minio,never()).removeObject(any(RemoveObjectArgs.class));assertThat(stored).containsKey(currentKey);
    }

    @Test void manuallyReplayedUnknownCallbackDoesNotOverwriteActualCommittedOutcome() throws Exception {
        var intent=request();ledger.direct(intent,()->new TransactionTemplate(transactions).execute(status->put()));
        ledger.callbackObservation(intent,TransactionSynchronization.STATUS_UNKNOWN,"isolated explicit callback observation");
        assertThat(events("TX_OUTCOME")).hasSize(1);assertThat(events("TX_OUTCOME").get(0).path("payload").path("status").asText()).isEqualTo("COMMITTED");
        ledger.cleanup("LedgerTest","case");assertThat(stored).doesNotContainKey(currentKey);
    }

    @Test void knownRolledBackReturnedBytesCanBeCleanedOnlyWhenOriginRowReallyVanished() throws Exception {
        transactions.onRollback=avatars::clear;var intent=request();
        ledger.direct(intent,()->new TransactionTemplate(transactions).execute(status->{put();status.setRollbackOnly();return null;}));
        assertThat(stored).containsKey(currentKey);ledger.cleanup("LedgerTest","case");
        assertThat(stored).doesNotContainKey(currentKey);assertThat(events("REMOVE_INTENT").get(0).path("payload").path("ownership").asText()).isEqualTo("EXACT_PUT_ATTEMPT");
    }

    @Test void realServiceRollbackCompensationRunsAfterSpringClearsSynchronization() throws Exception {
        var mapper=mock(ffdd.opsconsole.content.mapper.SupportAdminAvatarMapper.class);
        when(mapper.prior(anyLong(),anyString(),anyString())).thenReturn(List.of());
        doAnswer(call->{
            ffdd.opsconsole.content.domain.SupportAvatarAsset row=call.getArgument(0);currentKey=row.objectKey();assetId=row.id();
            avatars=new ArrayList<>();avatars.add(map("id",row.id(),"uploader_id",row.uploaderId(),"client_upload_id",row.clientUploadId(),
                    "idempotency_key",row.idempotencyKey(),"request_hash",row.requestHash(),"mime",row.mime(),"byte_count",row.byteCount(),
                    "object_key",row.objectKey(),"state",row.state(),"attached_admin_id",null,"expires_at",row.expiresAt().toString(),"created_at",CREATED));return null;
        }).when(mapper).insertAsset(any(ffdd.opsconsole.content.domain.SupportAvatarAsset.class));
        var attachments=mock(SupportAttachmentService.class);when(attachments.actor("ADMIN")).thenReturn(100L);
        var policy=new SupportAttachmentPolicy();policy.setAllowedMimeTypes(List.of("image/png"));policy.setMaxBytes(1048576L);policy.setMaxPixels(1000L);policy.setTtlSeconds(3600L);
        var service=new SupportAdminAvatarService(mapper,mock(ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper.class),
                mock(SupportOwnershipService.class),attachments,policy,storage);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("100",null,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("platform_a1_write"))));
        var png=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",png);
        var intent=ledger.request(new SupportObjectEvidenceLedger.Request("LedgerTest","case",SupportObjectEvidenceLedger.Kind.AVATAR,"ADMIN",100L,null,null,"client-actual","command-actual",null,BUCKET,false));
        transactions.onRollback=avatars::clear;
        // The real service registers its own default-order compensation before the boundary registers its synchronization.
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();delegateRemoves.incrementAndGet();
            stored.remove(call.<RemoveObjectArgs>getArgument(0).object());return null;}).when(minio).removeObject(any(RemoveObjectArgs.class));
        ledger.direct(intent,()->new TransactionTemplate(transactions).execute(status->{
            service.upload("client-actual","command-actual",new org.springframework.mock.web.MockMultipartFile("file","avatar.png","image/png",png.toByteArray()));
            assertThat(TransactionSynchronizationManager.getSynchronizations().stream().filter(sync->sync.getClass().getEnclosingClass()==SupportAdminAvatarService.class).toList()).hasSize(1);
            transactions.onRollback=avatars::clear;status.setRollbackOnly();return null;
        }));
        assertThat(delegateRemoves).hasValue(1);assertThat(stored).doesNotContainKey(currentKey);
        assertThat(events("TX_OUTCOME").get(0).path("sequence").asInt()).isLessThan(events("REMOVE_INTENT").get(0).path("sequence").asInt());
        assertThat(events("REMOVE_UNKNOWN")).isEmpty();
    }

    @Test void businessRemoveAndRepeatedCleanupUseOnePhysicalRemovalAndExactAbsentReadbacks() throws Exception {
        ledger.direct(request(),()->new TransactionTemplate(transactions).execute(status->put()));
        storage.remove(currentKey);ledger.cleanup("LedgerTest","case");ledger.cleanup("LedgerTest","case");
        assertThat(delegateRemoves).hasValue(1);assertThat(events("EXACT_HEAD")).allSatisfy(event->assertThat(event.path("payload").path("exists").asBoolean()).isFalse());
    }

    @Test void changedContentBlocksCleanupButOtherDomainsStillExecute() throws Exception {
        ledger.direct(request(),()->new TransactionTemplate(transactions).execute(status->put()));stored.put(currentKey,new byte[]{9});
        AtomicInteger later=new AtomicInteger();assertThatThrownBy(()->SupportObjectEvidenceLedger.cleanupIndependently(
                ()->ledger.cleanup("LedgerTest","case"),later::incrementAndGet,()->{throw new IllegalStateException("another actual domain");},later::incrementAndGet))
                .isInstanceOf(AssertionError.class).satisfies(failure->assertThat(failure.getSuppressed()).hasSize(2));
        assertThat(later).hasValue(2);verify(minio,never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test void partialEvidenceArtifactStopsWritesAndDoesNotSkipIntoGreenDirectory() throws Exception {
        request();Files.writeString(temporary.resolve("evidence/object-ledger/partial.pending"),"incomplete");
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->put())).hasMessageContaining("Unknown or partial");
        verify(minio,never()).putObject(any(PutObjectArgs.class));
    }

    @Test void changedContextStopsBeforePutAndPreservesFirstFailureWithEvidenceSuppressed() throws Exception {
        request();Files.writeString(temporary.resolve("actor-context.json"),"{}");
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->put())).satisfies(failure->assertThat(failure.getSuppressed()).isNotEmpty());
        verify(minio,never()).putObject(any(PutObjectArgs.class));
    }

    @Test void durablePutIntentWriteFailurePreventsRealDelegate() throws Exception {
        request();Path lock=temporary.resolve("evidence/object-ledger/ledger.lock");
        Files.move(lock,temporary.resolve("preserved-empty-lock"));Files.createDirectory(lock);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->put())).isInstanceOf(RuntimeException.class);
        verify(minio,never()).putObject(any(PutObjectArgs.class));
    }

    @Test void customerCreationRequiresAffectedRowGeneratedKeyAndReferralLookupAgreement() throws Exception {
        assertThatThrownBy(()->ledger.createCustomer("LedgerTest","customer","referral",()->new SupportObjectEvidenceLedger.CustomerInsert(201L,0,201L)))
                .hasMessageContaining("Actual single INSERT");
        assertThat(events("CUSTOMER_CREATED")).isEmpty();assertThat(events("CUSTOMER_CREATE_UNKNOWN")).hasSize(1);
    }

    @Test void independentlyCommittedCustomerSupportsPutBeforeAvatarUrlUpdate() throws Exception {
        long id=ledger.createCustomer("LedgerTest","customer","referral",()->{
            users.add(map("id",201L,"referral_code","referral","created_at",CREATED,"avatar_url",null));return new SupportObjectEvidenceLedger.CustomerInsert(201L,1,201L);
        });assertThat(id).isEqualTo(201L);avatars.clear();currentKey="private/customer-avatar/"+UUID.randomUUID();
        var intent=ledger.request(new SupportObjectEvidenceLedger.Request("LedgerTest","customer",SupportObjectEvidenceLedger.Kind.CUSTOMER_AVATAR,"USER",id,id,null,null,null,currentKey,BUCKET,false));
        ledger.direct(intent,()->storage.put(currentKey,"image/png",new ByteArrayInputStream(BYTES),BYTES.length));
        assertThat(users.get(0).get("avatar_url")).isNull();assertThat(events("PUT_RETURNED")).hasSize(1);
    }

    @Test void anotherContextCanAppendToSamePhaseChainWithoutLifetimeLock() throws Exception {
        request();var second=new SupportObjectEvidenceLedger(jdbc,json,transactions,properties,minio,environment);
        second.request(new SupportObjectEvidenceLedger.Request("SecondContext","case",SupportObjectEvidenceLedger.Kind.AVATAR,"UNKNOWN",null,null,null,null,null,null,BUCKET,false));
        assertThat(events("REQUEST_INTENT")).hasSize(2);assertThat(events("REQUEST_INTENT").get(1).path("previous").path("sha256").asText()).hasSize(64);
    }

    @Test void persistedRequestSqlAndContentIntegersSurviveJacksonNumericNodeWidthRoundTrip() throws Exception {
        JsonNode longNode=json.valueToTree(100L);assertThat(longNode).isNotEqualTo(json.readTree("100"));
        var intent=request();ledger.direct(intent,()->new TransactionTemplate(transactions).execute(status->put()));
        // All three actual comparisons run here: Long actorId in Request, Long SQL byte_count and Long digest bytes.
        ledger.cleanup("LedgerTest","case");assertThat(stored).doesNotContainKey(currentKey);assertThat(delegateRemoves).hasValue(1);
        assertThat(events("REMOVE_UNKNOWN")).isEmpty();
    }

    @Test void adminAttachmentUsesActualSameTransactionRowCommandAndIndependentCustomerProof() throws Exception {
        long customer=createdCustomer(201L,"admin-attachment-customer");avatars.clear();
        currentKey="private/support/"+UUID.randomUUID();assetId=UUID.randomUUID().toString();
        attachments.add(attachment(assetId,currentKey,"ADMIN",100L,customer,301L,"READY"));
        attachmentCommands.add(map("actor_type","ADMIN","actor_id",100L,"operation","UPLOAD","command_key","attachment-command","attachment_id",assetId));
        var intent=ledger.request(new SupportObjectEvidenceLedger.Request("LedgerTest","admin-attachment",SupportObjectEvidenceLedger.Kind.ATTACHMENT,
                "ADMIN",100L,customer,301L,"attachment-client","attachment-command",null,BUCKET,false));
        ledger.direct(intent,()->new TransactionTemplate(transactions).execute(status->put()));
        JsonNode put=events("PUT_INTENT").get(0);
        assertThat(put.path("payload").path("rowTable").asText()).isEqualTo("nx_support_attachment");
        assertThat(put.path("payload").path("exactRow").path("uploadCommand").path("attachment_id").asText()).isEqualTo(assetId);
        assertThat(put.path("payload").path("before").path("customerCreatorRef").path("sha256").asText()).hasSize(64);
        assertThat(put.path("creatorRef").path("path").asText()).contains("fixture-actors");
        ledger.cleanup("LedgerTest","admin-attachment");assertThat(stored).doesNotContainKey(currentKey);assertThat(delegateRemoves).hasValue(1);
    }

    @Test void userAttachmentUsesIndependentUserCreatorAndSameClientNewCommandReplay() throws Exception {
        long customer=createdCustomer(202L,"user-attachment-customer");avatars.clear();
        currentKey="private/support/"+UUID.randomUUID();assetId=UUID.randomUUID().toString();
        attachments.add(attachment(assetId,currentKey,"USER",customer,customer,null,"READY"));
        attachmentCommands.add(map("actor_type","USER","actor_id",customer,"operation","UPLOAD","command_key","attachment-command","attachment_id",assetId));
        var intent=ledger.request(new SupportObjectEvidenceLedger.Request("LedgerTest","user-attachment",SupportObjectEvidenceLedger.Kind.ATTACHMENT,
                "USER",customer,customer,null,"attachment-client","attachment-command",null,BUCKET,false));
        Map<String,Object> returned=ledger.direct(intent,()->new TransactionTemplate(transactions).execute(status->{put();return map("attachmentId",assetId);}));
        JsonNode put=events("PUT_INTENT").get(0);
        assertThat(put.path("creatorRef")).isEqualTo(put.path("payload").path("before").path("customerCreatorRef"));
        var replay=ledger.request(new SupportObjectEvidenceLedger.Request("LedgerTest","user-attachment",SupportObjectEvidenceLedger.Kind.ATTACHMENT,
                "USER",customer,customer,null,"attachment-client","another-command",null,BUCKET,false));
        assertThat(ledger.direct(replay,()->returned)).isSameAs(returned);assertThat(delegatePuts).hasValue(1);
        assertThat(events("DIRECT_OUTCOME").get(1).path("payload").path("classification").asText()).isEqualTo("REPLAY");
        ledger.cleanup("LedgerTest","user-attachment");assertThat(stored).doesNotContainKey(currentKey);assertThat(delegateRemoves).hasValue(1);
    }

    @Test void attachmentWithoutItsActualUploadCommandNeverReachesStorage() throws Exception {
        long customer=createdCustomer(203L,"missing-command-customer");avatars.clear();
        currentKey="private/support/"+UUID.randomUUID();assetId=UUID.randomUUID().toString();
        attachments.add(attachment(assetId,currentKey,"ADMIN",100L,customer,301L,"READY"));
        ledger.request(new SupportObjectEvidenceLedger.Request("LedgerTest","missing-command",SupportObjectEvidenceLedger.Kind.ATTACHMENT,
                "ADMIN",100L,customer,301L,"attachment-client","attachment-command",null,BUCKET,false));
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->put())).hasMessageContaining("attachment command row");
        verify(minio,never()).putObject(any(PutObjectArgs.class));
    }

    @Test void attachmentWithAmbiguousUploadCommandsNeverReachesStorage() throws Exception {
        long customer=createdCustomer(204L,"duplicate-command-customer");avatars.clear();
        currentKey="private/support/"+UUID.randomUUID();assetId=UUID.randomUUID().toString();
        attachments.add(attachment(assetId,currentKey,"ADMIN",100L,customer,301L,"READY"));
        var command=map("actor_type","ADMIN","actor_id",100L,"operation","UPLOAD","command_key","attachment-command","attachment_id",assetId);
        attachmentCommands.add(command);attachmentCommands.add(new LinkedHashMap<>(command));
        ledger.request(new SupportObjectEvidenceLedger.Request("LedgerTest","duplicate-command",SupportObjectEvidenceLedger.Kind.ATTACHMENT,
                "ADMIN",100L,customer,301L,"attachment-client","attachment-command",null,BUCKET,false));
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->put())).hasMessageContaining("attachment command row");
        verify(minio,never()).putObject(any(PutObjectArgs.class));
    }

    @Test void bulkAssetUsesActualJsonRowAndDeduplicatesTwoAttachedReferencesDuringCleanup() throws Exception {
        long first=createdCustomer(205L,"first-bulk-customer"),second=createdCustomer(206L,"second-bulk-customer");
        prepareBulk();var intent=bulkRequest("bulk-shared-key");
        ledger.direct(intent,()->new TransactionTemplate(transactions).execute(status->put()));
        JsonNode put=events("PUT_INTENT").get(0);
        assertThat(put.path("object").path("kind").asText()).isEqualTo("BULK_ASSET");
        assertThat(json.readTree(put.path("payload").path("exactRow").path("asset_json").asText()).path("objectKey").asText()).isEqualTo(currentKey);
        attachments.add(attachment(UUID.randomUUID().toString(),currentKey,"ADMIN",100L,first,401L,"ATTACHED"));
        attachments.add(attachment(UUID.randomUUID().toString(),currentKey,"ADMIN",100L,second,402L,"ATTACHED"));
        ledger.cleanup("LedgerTest","bulk-shared-key");ledger.cleanup("LedgerTest","bulk-shared-key");
        assertThat(delegatePuts).hasValue(1);assertThat(delegateRemoves).hasValue(1);assertThat(stored).doesNotContainKey(currentKey);
        assertThat(events("REMOVE_INTENT").get(0).path("payload").path("current").path("references")).hasSize(2);
        assertThat(attachments).allSatisfy(row->assertThat(row.get("state")).isEqualTo("ATTACHED"));
    }

    @Test void bulkSharedKeyWithOneUnprovenCustomerRefIsNotDeleted() throws Exception {
        long first=createdCustomer(207L,"known-bulk-customer");prepareBulk();var intent=bulkRequest("bulk-unproven-reference");
        ledger.direct(intent,()->new TransactionTemplate(transactions).execute(status->put()));
        attachments.add(attachment(UUID.randomUUID().toString(),currentKey,"ADMIN",100L,first,401L,"ATTACHED"));
        attachments.add(attachment(UUID.randomUUID().toString(),currentKey,"ADMIN",100L,999L,402L,"ATTACHED"));
        assertThatThrownBy(()->ledger.cleanup("LedgerTest","bulk-unproven-reference")).isInstanceOf(AssertionError.class);
        assertThat(stored).containsKey(currentKey);verify(minio,never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test void bulkJsonContentMismatchCannotBeHiddenByMatchingClientAndCommand() throws Exception {
        prepareBulk();bulkAssets.get(0).put("asset_json",json.writeValueAsString(map("objectKey",currentKey,"mime","image/png","bytes",999,"width",2,"height",2)));
        bulkRequest("bulk-wrong-json-size");assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->put())).hasMessageContaining("Bulk SQL content");
        verify(minio,never()).putObject(any(PutObjectArgs.class));
    }

    @Test void contradictoryAdminBirthNameProofIsRejectedBeforePut() throws Exception {
        rewriteAdminCreator(body->((com.fasterxml.jackson.databind.node.ObjectNode)body.path("create").path("committedReadback")).put("username","different-origin"));
        request();assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->put())).hasMessageContaining("birth-name evidence");
        verify(minio,never()).putObject(any(PutObjectArgs.class));
    }

    @Test void contradictoryAdminOriginalRequestNameIsRejectedBeforePut() throws Exception {
        rewriteAdminCreator(body->((com.fasterxml.jackson.databind.node.ObjectNode)body.path("create").path("request")).put("username","different-request"));
        request();assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->put())).hasMessageContaining("birth-name evidence");
        verify(minio,never()).putObject(any(PutObjectArgs.class));
    }

    @Test void contradictoryAdminBirthTimeReadbackIsRejectedBeforePut() throws Exception {
        rewriteAdminCreator(body->((com.fasterxml.jackson.databind.node.ObjectNode)body.path("create").path("committedReadback")).put("created_at","2026-01-01 00:00:00.654321"));
        request();assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->put())).hasMessageContaining("birth-time evidence");
        verify(minio,never()).putObject(any(PutObjectArgs.class));
    }

    @Test void legitimateAdminCurrentUsernameChangeDoesNotChangeImmutableCreatorBirthIdentity() throws Exception {
        // The actual current-row query deliberately selects only immutable id and created_at.
        currentAdmin.put("username","renamed-after-the-proven-insert");
        ledger.direct(request(),()->new TransactionTemplate(transactions).execute(status->put()));
        ledger.cleanup("LedgerTest","case");assertThat(stored).doesNotContainKey(currentKey);
        verify(jdbc,never()).queryForList(contains("SELECT username FROM nx_admin"),any(Object[].class));
    }

    @ParameterizedTest @MethodSource("rawJdbcBirthRepresentations")
    void actualRawJdbcBirthRepresentationPassesSameMapperCreatorAndCurrentRowChecks(String jdbcType,String representation,String born) throws Exception {
        rewriteRawJdbcBirth(jdbcType,representation,born,0);
        ledger.direct(request(),()->new TransactionTemplate(transactions).execute(status->put()));
        ledger.cleanup("LedgerTest","case");
        assertThat(delegatePuts).hasValue(1);assertThat(delegateRemoves).hasValue(1);assertThat(stored).doesNotContainKey(currentKey);
        assertThat(events("PUT_UNKNOWN")).isEmpty();assertThat(events("REMOVE_UNKNOWN")).isEmpty();
    }

    @ParameterizedTest @MethodSource("rawJdbcBirthRepresentations")
    void differentActualRawJdbcBirthRepresentationCannotAcquireObjectOwnership(String jdbcType,String representation,String born) throws Exception {
        rewriteRawJdbcBirth(jdbcType,representation,born,1);
        request();assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->put())).hasMessageContaining("birth-time evidence");
        verify(minio,never()).putObject(any(PutObjectArgs.class));assertThat(events("PUT_RETURNED")).isEmpty();
    }

    private static java.util.stream.Stream<Arguments> rawJdbcBirthRepresentations() {
        String seconds="2026-01-01T00:00:00";
        return java.util.stream.Stream.of(
                Arguments.of("TIMESTAMP","NUMERIC",seconds),
                Arguments.of("TIMESTAMP","TEXT",seconds),
                Arguments.of("LOCAL_DATE_TIME","ARRAY",seconds),
                Arguments.of("LOCAL_DATE_TIME","TEXT",seconds),
                Arguments.of("TIMESTAMP","PRODUCTION_FORMAT",CREATED.replace(' ','T')),
                Arguments.of("LOCAL_DATE_TIME","PRODUCTION_FORMAT",CREATED.replace(' ','T')));
    }

    private void rewriteRawJdbcBirth(String jdbcType,String representation,String born,int readbackSecondDifference) throws Exception {
        if("PRODUCTION_FORMAT".equals(representation)) {
            var builder=org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json();
            new ffdd.opsconsole.shared.config.DateTimeFormatConfig().nexionDateTimeJacksonCustomizer().customize(builder);
            builder.configure(json);
        } else {
            json.configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                    "NUMERIC".equals(representation)||"ARRAY".equals(representation));
            json.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        }
        java.time.LocalDateTime local=java.time.LocalDateTime.parse(born),readbackLocal=local.plusSeconds(readbackSecondDifference);
        Object actualCurrent="TIMESTAMP".equals(jdbcType)?java.sql.Timestamp.valueOf(local):local;
        Object actualCommitted="TIMESTAMP".equals(jdbcType)?java.sql.Timestamp.valueOf(readbackLocal):readbackLocal;
        // This is the original creator's real writeValueAsBytes path, not the ledger's normalized helper.
        JsonNode committed=json.readTree(json.writeValueAsBytes(actualCommitted));
        if("NUMERIC".equals(representation))assertThat(committed.isIntegralNumber()).isTrue();
        else if("ARRAY".equals(representation))assertThat(committed.isArray()).isTrue();
        else assertThat(committed.isTextual()).isTrue();
        currentAdmin.put("created_at",actualCurrent);String actualBirth=String.valueOf(actualCurrent);
        rewriteAdminCreator(body->{
            ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("createdAt",actualBirth);
            ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("actor")).put("createdAt",actualBirth);
            ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("create").path("committedReadback")).set("created_at",committed);
        });
    }

    private SupportObjectEvidenceLedger.Intent request(){return ledger.request(new SupportObjectEvidenceLedger.Request("LedgerTest","case",SupportObjectEvidenceLedger.Kind.AVATAR,"ADMIN",100L,null,null,"client","command",null,BUCKET,false));}
    private StoredObject put(){return storage.put(currentKey,"image/png",new ByteArrayInputStream(BYTES),BYTES.length);}
    private long createdCustomer(long id,String referral){return ledger.createCustomer("LedgerTest","customer-creation",referral,()->{
        users.add(map("id",id,"referral_code",referral,"created_at",CREATED,"avatar_url",null));return new SupportObjectEvidenceLedger.CustomerInsert(id,1,id);
    });}
    private static Map<String,Object> attachment(String id,String key,String actorType,long actor,long customer,Long assignment,String state){
        return map("id",id,"customer_id",customer,"uploader_type",actorType,"uploader_id",actor,"assignment_id",assignment,
                "client_upload_id","attachment-client","request_hash","attachment-raw-hash","mime","image/png","bytes",(long)BYTES.length,
                "width",2,"height",2,"object_key",key,"state",state,"expires_at","2099-01-01 00:00:00","message_id",state.equals("ATTACHED")?501L:null);
    }
    private void prepareBulk() throws Exception {
        avatars.clear();currentKey="private/support-bulk/"+UUID.randomUUID();assetId=UUID.randomUUID().toString();
        bulkAssets.add(map("id",assetId,"record_type","ASSET","actor_id",100L,"command_key","bulk-command","client_upload_id","bulk-client",
                "request_hash","bulk-raw-hash","asset_json",json.writeValueAsString(map("objectKey",currentKey,"mime","image/png","bytes",BYTES.length,"width",2,"height",2)),
                "state","READY","expires_at","2099-01-01 00:00:00","created_at",CREATED,"updated_at",CREATED));
    }
    private SupportObjectEvidenceLedger.Intent bulkRequest(String testcase){return ledger.request(new SupportObjectEvidenceLedger.Request("LedgerTest",testcase,
            SupportObjectEvidenceLedger.Kind.BULK_ASSET,"ADMIN",100L,null,null,"bulk-client","bulk-command",null,BUCKET,false));}
    private void rewriteAdminCreator(java.util.function.Consumer<JsonNode> mutation) throws Exception {
        try(var entries=Files.list(temporary.resolve("evidence/fixture-actors"))){Path path=entries.findFirst().orElseThrow();JsonNode body=json.readTree(Files.readAllBytes(path));mutation.accept(body);Files.write(path,json.writeValueAsBytes(body));}
    }
    private void avatar(){avatars=new ArrayList<>();avatars.add(map("id",assetId,"uploader_id",100L,"client_upload_id","client","idempotency_key","command","request_hash","raw-hash","mime","image/png","byte_count",(long)BYTES.length,"object_key",currentKey,"state","READY","attached_admin_id",null,"expires_at","2099-01-01 00:00:00","created_at",CREATED));}
    private Object rows(String sql,Object[] arguments) throws Exception {
        if(sql.startsWith("SELECT DATABASE()"))return List.of(map("database_name","cs_enhance_20261001","database_port",33329,"server_uuid","isolated-server","connection_id",101L));
        if(sql.startsWith("SELECT id,created_at FROM nx_admin"))return same(currentAdmin.get("id"),argument(arguments,1))
                ?List.of(map("id",currentAdmin.get("id"),"created_at",currentAdmin.get("created_at"))):List.of();
        if(sql.startsWith("SELECT username FROM nx_admin"))return List.of(map("username",currentAdmin.get("username")));
        if(sql.contains(" FROM nx_support_admin_avatar_asset "))return matching(avatars,sql.contains("object_key=?")?"object_key":"id",argument(arguments,1));
        if(sql.contains(" FROM nx_user "))return matching(users,sql.contains("referral_code=?")?"referral_code":"id",argument(arguments,1));
        if(sql.contains(" FROM nx_support_attachment_command "))return attachmentCommands.stream()
                .filter(row->"UPLOAD".equals(row.get("operation"))&&same(row.get("actor_type"),argument(arguments,1))
                        &&same(row.get("actor_id"),argument(arguments,2))&&same(row.get("command_key"),argument(arguments,3)))
                .map(row->new LinkedHashMap<>(row)).toList();
        if(sql.contains(" FROM nx_support_attachment "))return matching(attachments,sql.contains("object_key=?")?"object_key":"id",argument(arguments,1));
        if(sql.contains(" FROM nx_support_bulk_job ")) {
            List<Map<String,Object>> found=new ArrayList<>();
            for(Map<String,Object> row:bulkAssets)if("ASSET".equals(row.get("record_type"))&&
                    (sql.contains("JSON_EXTRACT")?same(json.readTree(row.get("asset_json").toString()).path("objectKey").asText(),argument(arguments,1)):same(row.get("id"),argument(arguments,1))))found.add(new LinkedHashMap<>(row));
            return found;
        }
        return List.of();
    }
    private static List<Map<String,Object>> matching(List<Map<String,Object>> rows,String column,Object value){return rows.stream().filter(row->same(row.get(column),value)).<Map<String,Object>>map(row->new LinkedHashMap<>(row)).toList();}
    private static boolean same(Object left,Object right){return left==null?right==null:right!=null&&String.valueOf(left).equals(String.valueOf(right));}
    private static Object argument(Object[] arguments,int index){return arguments.length==2&&arguments[1] instanceof Object[] array?array[index-1]:arguments[index];}
    private void adminCreator(long id) throws Exception {
        String operation=UUID.randomUUID().toString();Map<String,Object> request=map("username","isolated-admin");
        JsonNode intent=save(temporary.resolve("evidence/fixture-actor-intents/"+operation+".json"),
                map("event","CREATE_INTENT","operationId",operation,"contextSha256",environment.get("CS_ENHANCE_ACTOR_CONTEXT_SHA256"),"request",request));
        Map<String,Object> body=map("schemaVersion",3,"event","CREATED","ownsActor",true,"operationId",operation,"adminId",id,"originalUsername","isolated-admin","createdAt",CREATED,
                "actor",map("id",id,"username","isolated-admin","createdAt",CREATED),"contextSha256",environment.get("CS_ENHANCE_ACTOR_CONTEXT_SHA256"),"intent",intent,
                "create",map("kind","SQL_INSERT_COMMIT","successfulExactResponse",true,"transactionOutcome","COMMIT_RETURNED","request",request,
                        "response",map("generatedKey",id,"affectedRows",1),"committedReadback",map("id",id,"username","isolated-admin","created_at",CREATED)));
        for(String field:List.of("identity","candidate","windowId","resourceIdentity","businessDeadline","hardDeadline","leaseSha256","businessReleaseSha256"))body.put(field,context.get(field));
        save(temporary.resolve("evidence/fixture-actors/"+operation+"-"+id+".json"),body);
    }
    private JsonNode save(Path path,Object value) throws Exception {Files.createDirectories(path.getParent());byte[] bytes=json.writeValueAsBytes(value);Files.write(path,bytes);return json.valueToTree(map("path",path.toAbsolutePath().normalize().toString(),"sha256",sha(bytes),"bytes",bytes.length));}
    private List<JsonNode> events(String type) throws Exception {List<JsonNode> result=new ArrayList<>();try(var files=Files.list(temporary.resolve("evidence/object-ledger"))){for(Path path:files.sorted().toList())if(path.getFileName().toString().endsWith(".json")){JsonNode body=json.readTree(Files.readAllBytes(path));if(type.equals(body.path("eventType").asText()))result.add(body);}}return result;}
    private static Map<String,Object> map(Object... fields){Map<String,Object> map=new LinkedHashMap<>();for(int i=0;i<fields.length;i+=2)map.put((String)fields[i],fields[i+1]);return map;}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static class TestTransactions extends AbstractPlatformTransactionManager {
        Runnable onRollback=()->{};
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected void doBegin(Object transaction,TransactionDefinition definition){}
        @Override protected void doCommit(DefaultTransactionStatus status){}
        @Override protected void doRollback(DefaultTransactionStatus status){onRollback.run();}
    }
}
