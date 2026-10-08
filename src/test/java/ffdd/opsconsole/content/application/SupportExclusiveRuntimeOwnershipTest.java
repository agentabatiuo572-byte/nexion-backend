package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class SupportExclusiveRuntimeOwnershipTest {
    @TempDir Path directory;
    @Test void actualIdentityAndPermissionsMustMatchAnUnchangedProof() throws Exception {
        var json = new ObjectMapper();
        var target = SupportRuntimeTarget.select(Map.of("SUPPORT_RUNTIME_TARGET", "analytics-20261007"));
        var proof = json.createObjectNode();
        proof.put("schemaVersion", 1).put("mode", "EXCLUSIVE_ANALYTICS").put("owner", target.owner());
        proof.putObject("resourceIdentity").put("database", target.database());
        proof.putObject("databaseIdentity").put("database", target.database()).put("port", target.databasePort())
                .put("serverUuid", "3556ddae-c1a1-11f1-8853-a40c6626953d").put("currentUser", target.username()+"@127.0.0.1").put("dataDirectory", "D:/isolated/data/");
        proof.putArray("permissions").add("exact test grant");
        Path file=directory.resolve("ownership.json"); Files.write(file,json.writeValueAsBytes(proof));
        var context=json.createObjectNode(); context.put("schemaVersion",2).put("ownershipMode","EXCLUSIVE_ANALYTICS");
        context.set("resourceIdentity",proof.path("resourceIdentity"));
        context.putObject("resourceOwnership").put("path",file.toString()).put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        var jdbc=mock(JdbcTemplate.class);
        var actual=new java.util.HashMap<String,Object>(Map.of("db",target.database(),"port",target.databasePort(),"uuid","3556ddae-c1a1-11f1-8853-a40c6626953d","account",target.username()+"@127.0.0.1","directory","D:\\isolated\\data\\"));
        when(jdbc.queryForMap(anyString())).thenReturn(actual); when(jdbc.queryForList("SHOW GRANTS",String.class)).thenReturn(List.of("exact test grant"));
        assertThatCode(()->SupportExclusiveRuntimeOwnership.requireActual(context,target,jdbc)).doesNotThrowAnyException();
        for (String field:List.of("db","port","uuid","account","directory")) {
            Object saved=actual.get(field); actual.put(field,field.equals("port")?33329:"different");
            assertThatThrownBy(()->SupportExclusiveRuntimeOwnership.requireActual(context,target,jdbc)).isInstanceOf(IllegalStateException.class);
            actual.put(field,saved);
        }
        when(jdbc.queryForList("SHOW GRANTS",String.class)).thenReturn(List.of("expanded test grant"));
        assertThatThrownBy(()->SupportExclusiveRuntimeOwnership.requireActual(context,target,jdbc)).isInstanceOf(IllegalStateException.class).hasMessageContaining("grants changed");
        Files.writeString(file,"{}");
        assertThatThrownBy(()->SupportExclusiveRuntimeOwnership.validate(context,target)).isInstanceOf(IllegalStateException.class).hasMessageContaining("hash mismatch");
    }
}
