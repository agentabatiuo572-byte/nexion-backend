package ffdd.opsconsole.device.infrastructure;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ffdd.opsconsole.device.application.LegacyUnknownZeroCreditCases;
import ffdd.opsconsole.device.dto.LegacyUnknownZeroCreditProof;
import ffdd.opsconsole.device.dto.LegacyUnknownZeroCreditProof.*;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class LegacyUnknownQuiescenceVerifier {
    private final Environment environment;
    private final Clock clock;
    private final Supplier<String> bootId;
    private final String backendInstanceId;
    private final JsonMapper json = JsonMapper.builder().addModule(new JavaTimeModule())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();

    @Autowired
    public LegacyUnknownQuiescenceVerifier(Environment environment, Clock clock) {
        this(environment,clock,() -> {
            try { return Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).trim(); }
            catch (java.io.IOException missing) { throw invalid(); }
        },UUID.randomUUID().toString());
    }
    public LegacyUnknownQuiescenceVerifier(Environment environment, Clock clock, Supplier<String> bootId,
            String backendInstanceId) {
        this.environment=environment; this.clock=clock; this.bootId=bootId; this.backendInstanceId=backendInstanceId;
    }
    public String backendInstanceId() { return backendInstanceId; }
    public Observation verify(LegacyUnknownZeroCreditProof proof) {
        if (!"TEST".equals(environment.getProperty("nexion.compute-v2.deployment-scope")) || proof==null
                || proof.observation()==null || proof.observation().length()>65536
                || proof.observation().chars().anyMatch(c -> c>127) || !hash(proof.signature())) throw invalid();
        String secret=environment.getProperty("nexion.compute-v2.proof-secret","");
        if (!hash(secret)) throw invalid();
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(secret),"HmacSHA256"));
            if (!MessageDigest.isEqual(mac.doFinal(proof.observation().getBytes(StandardCharsets.UTF_8)),
                    HexFormat.of().parseHex(proof.signature()))) throw invalid();
            var tree=json.readTree(proof.observation());
            for (String field:Set.of("capturedAt","observedAt","expiresAt"))
                if (!tree.path(field).isTextual()) throw invalid();
            Observation o=json.treeToValue(tree,Observation.class);
            if (o.version()!=1 || !"LEGACY_UNKNOWN_ZERO_CREDIT".equals(o.purpose()) || !"TEST".equals(o.scope())
                    || !LegacyUnknownZeroCreditCases.MANIFEST_SHA.equals(o.caseSetSha256())
                    || !backendInstanceId.equals(o.backendInstanceId()) || !bootId.get().equals(o.bootId())
                    || o.capturedAt()==null || o.observedAt()==null || o.expiresAt()==null
                    || o.capturedAt().isAfter(o.observedAt()) || o.observedAt().isAfter(clock.instant().plusSeconds(300))
                    || !o.expiresAt().isAfter(clock.instant()) || !o.expiresAt().isAfter(o.observedAt())
                    || Duration.between(o.observedAt(),o.expiresAt()).compareTo(Duration.ofHours(24))>0
                    || !hash(o.evidenceSha256()) || !LegacyUnknownZeroCreditCases.BUDGET_SHA.equals(o.budgetSha256())) throw invalid();
            UUID.fromString(o.bootId());
            if (o.backend()==null || !o.backend().previousExited()) throw invalid();
            exited(o.backend().previous(),o.backend().current(),false);
            Set<String> workers=new HashSet<>();
            if (o.workers()==null || o.workers().size()!=3) throw invalid();
            for (WorkerExit w:o.workers()) {
                if (w==null || !workers.add(w.service()) || !"inactive".equals(w.activeState()) || w.mainPid()!=0
                        || !w.previousProcessesExited()) throw invalid();
            }
            if (!workers.equals(Set.of("uvel-cloud-bge-fleet.service","uvel-cloud-bge-worker.service",
                    "uvel-compute-v2-worker.service"))) throw invalid();
            Set<String> roles=new HashSet<>(),containers=new HashSet<>();
            if (o.models()==null || o.models().size()!=7) throw invalid();
            for (ModelExit m:o.models()) {
                if (m==null || !roles.add(m.role()) || !m.previousProcessesExited() || !hash(m.evidenceSha256())) throw invalid();
                if (!"validator".equals(m.role()) && m.previous()!=null && m.previous().pid()==0 && m.previous().startTicks()==0) {
                    if (!hash(m.previous().containerId()) || m.current()==null || !hash(m.current().containerId())
                            || !m.previous().containerId().equals(m.current().containerId())
                            || m.current().pid()<=0 || m.current().startTicks()<=0) throw invalid();
                } else exited(m.previous(),m.current(),true);
                if (!containers.add(m.current().containerId())) throw invalid();
            }
            if (!roles.equals(Set.of("runner-1","runner-2","runner-3","runner-4","runner-5","runner-6","validator"))) throw invalid();
            if (o.journals()==null || o.journals().size()!=4 || new HashSet<>(o.journals()).size()!=4
                    || !new HashSet<>(o.journals()).equals(new HashSet<>(LegacyUnknownZeroCreditCases.ALL.stream()
                        .map(LegacyUnknownZeroCreditCases.Case::journal).toList()))) throw invalid();
            return o;
        } catch (BizException denied) { throw denied; }
        catch (Exception invalid) { throw invalid(); }
    }
    private static void exited(ProcessIdentity old, ProcessIdentity current, boolean model) {
        if (old==null || current==null || (model ? !hash(old.containerId()) || !hash(current.containerId())
                    || !old.containerId().equals(current.containerId())
                : !"systemd:nexgrid-backend.service".equals(old.containerId())
                    || !"systemd:nexgrid-backend.service".equals(current.containerId()))
                || old.pid()<=0 || old.startTicks()<=0 || current.pid()<=0 || current.startTicks()<=0
                || (old.pid()==current.pid() && old.startTicks()==current.startTicks())) throw invalid();
    }
    private static boolean hash(String value) { return value!=null && value.matches("[a-f0-9]{64}"); }
    private static BizException invalid() { return new BizException(409,"LEGACY_UNKNOWN_QUIESCENCE_INVALID"); }
}
