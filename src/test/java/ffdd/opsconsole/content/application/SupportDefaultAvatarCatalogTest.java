package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.dto.SupportAvatarRequests;
import ffdd.opsconsole.platform.dto.*;
import java.io.InputStream;
import java.util.*;
import org.junit.jupiter.api.Test;

class SupportDefaultAvatarCatalogTest {
    private final SupportAttachmentPolicy.View limits=new SupportAttachmentPolicy.View(true,List.of("image/png","image/jpeg"),1_048_576L,1_000_000L,300L,null);
    @Test void allEightFrozenPortraitsDecodeAtConfiguredBoundary()throws Exception {
        var resources=getClass().getClassLoader();var ids=new HashSet<String>();var hashes=new HashSet<String>();
        try(InputStream in=resources.getResourceAsStream("support/default-avatars/manifest.json")) {
            var entries=new ObjectMapper().readTree(in).path("entries");assertThat(entries).hasSize(8);
            for(var entry:entries){String name=entry.path("file").asText();
                try(InputStream portrait=resources.getResourceAsStream("support/default-avatars/"+name)){
                    var bytes=portrait.readAllBytes();assertThat(bytes.length).isEqualTo(entry.path("bytes").asInt());
                    assertThat(SupportDefaultAvatarCatalog.sha256(bytes)).isEqualTo(entry.path("sha256").asText());
                    var image=SupportAttachmentService.decode(new SupportDefaultAvatarCatalog.ImageFile(name,bytes),limits);
                    assertThat(image.width()).isEqualTo(entry.path("width").asInt());assertThat(image.height()).isEqualTo(entry.path("height").asInt());
                    assertThat((long)image.width()*image.height()).isLessThanOrEqualTo(limits.maxPixels());
                    assertThat(image.content().length).isLessThanOrEqualTo(limits.maxBytes().intValue());
                    ids.add(entry.path("poolEntryId").asText());hashes.add(image.rawHash());
                }
            }
        }assertThat(ids).hasSize(8);assertThat(hashes).hasSize(8);
    }
    @Test void explicitPoolsAndMixedPoolDoNotGuessFromNames() {
        var catalog=new SupportDefaultAvatarCatalog();
        assertThat(catalog.select("MALE",limits)).get().extracting(SupportDefaultAvatarCatalog.Selection::poolEntryId).asString().startsWith("male-");
        assertThat(catalog.select("FEMALE",limits)).get().extracting(SupportDefaultAvatarCatalog.Selection::poolEntryId).asString().startsWith("female-");
        assertThat(catalog.select("UNSPECIFIED",limits)).isPresent();
        assertThatThrownBy(()->catalog.select("female",limits)).hasMessage("AVATAR_GENDER_INVALID");
    }
    @Test void missingOrUntrustedResourcesPreserveMissingState() {
        var missing=new SupportDefaultAvatarCatalog(new ClassLoader(null){});
        assertThat(missing.select("UNSPECIFIED",limits)).isEmpty();
        var tooSmall=new SupportAttachmentPolicy.View(true,List.of("image/png"),1024L,16_000_000L,3600L,null);
        assertThat(new SupportDefaultAvatarCatalog().select("MALE",tooSmall)).isEmpty();
    }
    @Test void forgedManifestHashOrMissingReviewNeverBecomesAUsablePool()throws Exception {
        var original=getClass().getClassLoader();byte[] manifest;
        try(var stream=original.getResourceAsStream("support/default-avatars/manifest.json")){manifest=stream.readAllBytes();}
        for(String field:List.of("sha256","reviewed")) {
            var json=new ObjectMapper();var root=json.readTree(manifest);var entry=(com.fasterxml.jackson.databind.node.ObjectNode)root.path("entries").get(0);
            if("sha256".equals(field))entry.put(field,"0".repeat(64));else entry.put(field,false);
            byte[] forged=json.writeValueAsBytes(root);
            var loader=new ClassLoader(original){@Override public InputStream getResourceAsStream(String name){return name.equals("support/default-avatars/manifest.json")?new java.io.ByteArrayInputStream(forged):super.getResourceAsStream(name);}};
            assertThat(new SupportDefaultAvatarCatalog(loader).select("MALE",limits)).isEmpty();
        }
    }
    @Test void legacyConstructorsAndToStringHashesStayUnchanged() {
        var create=new AdminAccountCreateRequest("actor","Name","a@b.c","support",null,"reason","owner","password");
        assertThat(create.toString()).isEqualTo("AdminAccountCreateRequest[username=actor, displayName=Name, email=a@b.c, role=support, ignoredCredentialDelivery=null, reason=reason, operator=owner, initialPassword=password]");
        assertThat(new AdminAccountCreateRequest("actor","Name","a@b.c","support",null,"reason","owner","password",null,"MALE",true).toString()).contains("avatarGender","useDefaultAvatar").isNotEqualTo(create.toString());
        assertThat(new AdminAccountProfileUpdateRequest("actor","Name",null,"reason","owner","2").toString()).isEqualTo("AdminAccountProfileUpdateRequest[username=actor, displayName=Name, email=null, reason=reason, operator=owner, expectedVersion=2]");
    }
    @Test void selfSaveRejectsOtherAccountOrProfileFieldsEvenWhenMapperIgnoresUnknowns()throws Exception {
        var json=new ObjectMapper().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        assertThatThrownBy(()->json.readValue("{\"assetId\":\"id\",\"expectedVersion\":\"1\",\"reason\":\"r\",\"accountId\":\"2\"}",SupportAvatarRequests.SelfSave.class)).hasMessageContaining("AVATAR_COMMAND_INVALID");
        assertThat(json.readValue("{\"assetId\":\"id\",\"expectedVersion\":\"1\",\"reason\":\"r\"}",SupportAvatarRequests.SelfSave.class).assetId()).isEqualTo("id");
    }
}
