package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.device.dto.LegacyUnknownZeroCreditProof;
import ffdd.opsconsole.device.dto.LegacyUnknownZeroCreditProof.*;
import ffdd.opsconsole.device.infrastructure.LegacyUnknownQuiescenceVerifier;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.mock.env.MockEnvironment;

public final class LegacyUnknownZeroCreditFixtures {
    public static final String SECRET="ab".repeat(32),BOOT="f4082d61-ec53-4bc0-82f8-67ab7a837f59",EPOCH="d89de4b1-dd77-47a3-ad67-3e83de97b120";
    public static final Instant NOW=Instant.parse("2026-11-01T15:00:00Z");
    public static final Clock CLOCK=Clock.fixed(NOW,ZoneId.of("Asia/Shanghai"));
    public static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public static MockEnvironment environment() { return new MockEnvironment().withProperty("nexion.compute-v2.deployment-scope","TEST")
            .withProperty("nexion.compute-v2.proof-secret",SECRET); }
    public static LegacyUnknownQuiescenceVerifier verifier() {
        return new LegacyUnknownQuiescenceVerifier(environment(),CLOCK,()->BOOT,EPOCH);
    }
    public static LegacyUnknownZeroCreditProof proof() {
        try {
            var models=new ArrayList<ModelExit>();
            for (int i=1;i<=7;i++) {
                var previous=new ProcessIdentity(String.format("%064x",i),100+i,1000+i);
                var current=new ProcessIdentity(previous.containerId(),200+i,2000+i);
                models.add(new ModelExit(i==7?"validator":"runner-"+i,previous,current,true,"c".repeat(64)));
            }
            var workers=List.of("uvel-cloud-bge-fleet.service","uvel-cloud-bge-worker.service","uvel-compute-v2-worker.service")
                    .stream().map(s -> new WorkerExit(s,"inactive",0,true)).toList();
            var observation=new Observation(1,"LEGACY_UNKNOWN_ZERO_CREDIT","TEST",LegacyUnknownZeroCreditCases.MANIFEST_SHA,EPOCH,BOOT,
                    NOW.minusSeconds(60),NOW,NOW.plusSeconds(3600),"d".repeat(64),LegacyUnknownZeroCreditCases.BUDGET_SHA,
                    new BackendExit(new ProcessIdentity("systemd:nexgrid-backend.service",7,100),
                        new ProcessIdentity("systemd:nexgrid-backend.service",8,200),true),workers,models,
                    LegacyUnknownZeroCreditCases.ALL.stream().map(LegacyUnknownZeroCreditCases.Case::journal).toList());
            return signed(JSON.writeValueAsString(observation));
        } catch (Exception invalid) { throw new IllegalStateException(invalid); }
    }
    public static ObjectNode observation() { try { return (ObjectNode)JSON.readTree(proof().observation()); }
        catch (Exception e) { throw new IllegalStateException(e); } }
    public static LegacyUnknownZeroCreditProof signed(ObjectNode node) { try { return signed(JSON.writeValueAsString(node)); }
        catch (Exception e) { throw new IllegalStateException(e); } }
    public static LegacyUnknownZeroCreditProof signed(String raw) {
        try { var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(HexFormat.of().parseHex(SECRET),"HmacSHA256"));
            return new LegacyUnknownZeroCreditProof(raw,HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private LegacyUnknownZeroCreditFixtures() { }
}
