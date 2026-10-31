package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Reconstructs bytes/digest from the historical NEX writer, before applying the code migration. */
final class LegacyUvtProofFixture {
    static void makeLegacy(Map<String,Object> row,List<Long> parties,List<Map<String,Object>> legs,
            SupportFundJournalBindingService codec) {
        try {
            for(String key:List.of("orderedEventsJson","sourceSnapshotJson","sourceProofJson")) {
                JsonNode original=codec.read((String)row.get(key));legacy(original);row.put(key,original.toString());
            }
            var digest=new LinkedHashMap<String,Object>();
            for(String key:List.of("operationKey","businessType","phase","sourceTable","sourceKey","sourceEnvironment",
                    "orderedEventsJson","sourceSnapshotJson","sourceProofJson","protocolVersion"))
                digest.put(key,key.endsWith("Json")?codec.canonical(codec.read((String)row.get(key))).toString():row.get(key));
            digest.put("customerIds",parties);
            digest.put("legs",legs.stream().map(l->Map.of("incarnation",l.get("incarnation"),"revision",l.get("revision"))).toList());
            row.put("receiptSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(codec.canonical(new ObjectMapper().valueToTree(digest)).toString().getBytes(StandardCharsets.UTF_8))));
        }catch(Exception failure){throw new AssertionError(failure);}
    }
    private static void legacy(JsonNode node) {
        if(node instanceof ObjectNode object) object.fields().forEachRemaining(f->{
            if(Set.of("asset","currency").contains(f.getKey()) && f.getValue().isTextual() && "UVT".equals(f.getValue().textValue()))
                object.put(f.getKey(),"NEX");
            else legacy(f.getValue());
        }); else if(node.isArray())node.forEach(LegacyUvtProofFixture::legacy);
    }
}
