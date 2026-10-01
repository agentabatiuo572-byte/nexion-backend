package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import ffdd.opsconsole.NexionOpsConsoleApplication;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.*;

@EnabledIfEnvironmentVariable(named="CS_ENHANCE_AVATAR_READ_ENABLED",matches="true")
@SpringBootTest(classes=NexionOpsConsoleApplication.class,webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(SupportEnhancementPreparationTest.IsolatedConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SupportAvatarCompensationRuntimeTest extends SupportBulkRuntimeFixture {
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry) {SupportEnhancementPreparationTest.isolatedBoundary(registry);}
    @Autowired SupportAdminAvatarService avatars;
    @BeforeEach void prepareFixture() {startFixture();}
    @AfterEach void restore() {restoreFixture();}

    @Test void committedAvatarSurvivesUnknownCallbackAndRollbackStillCompensates() throws Exception {
        as(boss);
        int color=0x3355cc;byte[] image=png(color);
        String client=key(),command=key();var callback=new AtomicReference<TransactionSynchronization>();
        var uploaded=new TransactionTemplate(transactions).execute(status->{
            var before=TransactionSynchronizationManager.getSynchronizations();
            var result=avatars.upload(client,command,new MockMultipartFile("file","avatar.png","image/png",image));
            var registered=TransactionSynchronizationManager.getSynchronizations().stream()
                .filter(sync->!before.contains(sync) && sync.getClass().getEnclosingClass()==SupportAdminAvatarService.class).toList();
            assertThat(registered).as("actual synchronization registered by avatar upload").hasSize(1);
            callback.set(registered.get(0));return result;
        });
        assertThat(uploaded).isNotNull();assertThat(callback.get()).isNotNull();
        String assetId=uploaded.get("assetId").toString();
        String objectKey=jdbc.queryForObject("SELECT object_key FROM nx_support_admin_avatar_asset WHERE id=?",String.class,assetId);
        assertThat(jdbc.queryForObject("SELECT state FROM nx_support_admin_avatar_asset WHERE id=?",String.class,assetId)).isEqualTo("READY");
        boolean existedBefore=storage.exists(objectKey);assertThat(existedBefore).as("real object exists after actual READY database commit").isTrue();
        writeUnknownObservation(assetId,"BEFORE_CALLBACK",existedBefore,null);

        // Fault injection replays the actual upload callback; it does not simulate a physical JDBC outage.
        callback.get().afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
        boolean existsAfter=storage.exists(objectKey);
        writeUnknownObservation(assetId,"AFTER_CALLBACK",existedBefore,existsAfter);
        assertThat(jdbc.queryForObject("SELECT state FROM nx_support_admin_avatar_asset WHERE id=?",String.class,assetId)).isEqualTo("READY");
        assertThat(existsAfter).as("STATUS_UNKNOWN must preserve an actually committed avatar object").isTrue();

        var retried=avatars.upload(client,command,new MockMultipartFile("file","avatar.png","image/png",image));
        assertThat(retried.get("assetId")).isEqualTo(assetId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_admin_avatar_asset WHERE uploader_id=? AND client_upload_id=?",Long.class,boss,client)).isEqualTo(1L);
        String superToken=token(boss);
        assertImage("/api/admin/platform/accounts/avatar-assets/"+assetId,superToken,color);
        var overview=http("GET","/api/admin/platform/accounts/overview",superToken,null,null);
        assertThat(overview.path("code").asInt()).isZero();JsonNode account=null;
        for(var candidate:overview.path("data").path("operators")) if(candidate.path("id").asLong()==first) {account=candidate;break;}
        assertThat(account).as("actual original platform account identity").isNotNull();
        var edit=new LinkedHashMap<String,Object>();
        edit.put("username",account.path("username").asText());edit.put("displayName",account.path("name").asText());
        edit.put("email",account.path("email").asText(""));edit.put("expectedVersion",account.path("version").asText());
        edit.put("reason","Attach committed avatar after unknown callback proof");edit.put("operator",run);edit.put("avatarAssetId",assetId);
        var attached=http("PATCH","/api/admin/platform/accounts/"+first+"/profile",superToken,edit,key());
        assertThat(attached.path("code").asInt()).as("original full identity/CAS attachment result %s",attached.path("message")).isZero();
        assertThat(attached.path("data").path("avatarAssetId").asText()).isEqualTo(assetId);
        assertThat(jdbc.queryForObject("SELECT state FROM nx_support_admin_avatar_asset WHERE id=?",String.class,assetId)).isEqualTo("ATTACHED");
        assertThat(jdbc.queryForObject("SELECT attached_admin_id FROM nx_support_admin_avatar_asset WHERE id=?",Long.class,assetId)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT avatar_asset_id FROM nx_admin_account_state WHERE admin_id=?",String.class,first)).isEqualTo(assetId);
        assertImage("/api/admin/platform/accounts/"+first+"/avatar",superToken,color);

        var rolledBackAsset=new AtomicReference<String>();var rolledBackObject=new AtomicReference<String>();
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            var staged=avatars.upload(key(),key(),new MockMultipartFile("file","avatar.png","image/png",image));
            String id=staged.get("assetId").toString();rolledBackAsset.set(id);
            String location=jdbc.queryForObject("SELECT object_key FROM nx_support_admin_avatar_asset WHERE id=?",String.class,id);
            rolledBackObject.set(location);
            assertThat(jdbc.queryForObject("SELECT state FROM nx_support_admin_avatar_asset WHERE id=?",String.class,id)).isEqualTo("READY");
            assertThat(storage.exists(location)).as("real rollback candidate object exists before rollback").isTrue();
            status.setRollbackOnly();
        });
        assertThat(rolledBackAsset.get()).isNotNull();assertThat(rolledBackObject.get()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_admin_avatar_asset WHERE id=?",Long.class,rolledBackAsset.get())).isZero();
        assertThat(storage.exists(rolledBackObject.get())).as("explicit real transaction rollback compensates its staged object").isFalse();
        assertThat(storage.exists(objectKey)).as("rollback leaves the separately committed and attached object intact").isTrue();

        proofs.put("avatar-compensation",Map.of("status","pass","suite",getClass().getSimpleName(),
            "method","committedAvatarSurvivesUnknownCallbackAndRollbackStillCompensates",
            "testcase","committedAvatarSurvivesUnknownCallbackAndRollbackStillCompensates",
            "evidence","Real proxy upload joins a real MySQL transaction that commits READY and a real private object. The actual registered avatar afterCompletion callback receives injected STATUS_UNKNOWN after commit (callback fault injection, not physical JDBC disconnection); object and sole original asset survive, same client/key retry resolves that asset, authenticated original A1 HTTP preview and full identity/CAS attachment both return the original PNG pixel. A separate actual setRollbackOnly transaction rolls back its asset row and removes only its staged object."));
        writeProof("avatar-compensation-runtime.json");
    }

    private void assertImage(String path,String token,int color) throws Exception {
        var response=download(path,token);assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("image/png");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        var decoded=javax.imageio.ImageIO.read(new ByteArrayInputStream(response.body()));assertThat(decoded).isNotNull();
        assertThat(decoded.getRGB(1,1)&0xffffff).isEqualTo(color);
    }

    private void writeUnknownObservation(String assetId,String phase,boolean before,Boolean after) throws Exception {
        var observation=new LinkedHashMap<String,Object>();
        observation.put("phase",phase);observation.put("suite",getClass().getSimpleName());
        observation.put("testcase","committedAvatarSurvivesUnknownCallbackAndRollbackStillCompensates");
        observation.put("method","committedAvatarSurvivesUnknownCallbackAndRollbackStillCompensates");
        observation.put("run",run);observation.put("checkedAt",Instant.now().toString());
        observation.put("database","cs_enhance_20261001");observation.put("port",18141);
        observation.put("workflowRunId",Objects.requireNonNull(System.getenv("WORKFLOW_RUN_ID")));
        observation.put("snapshotHash",Objects.requireNonNull(System.getenv("WORKFLOW_SNAPSHOT_HASH")));
        observation.put("assetId",assetId);observation.put("persistedState","READY");
        observation.put("objectExistsBeforeUnknown",before);observation.put("objectExistsAfterUnknown",after);
        observation.put("injection","STATUS_UNKNOWN on actual registered avatar callback after actual database commit; not physical JDBC disconnection");
        Path directory=Path.of(Objects.requireNonNull(System.getenv("CS_ENHANCE_EVIDENCE_DIR")));Files.createDirectories(directory);
        Files.writeString(directory.resolve("avatar-compensation-before-unknown.json"),json.writeValueAsString(observation));
    }
}
