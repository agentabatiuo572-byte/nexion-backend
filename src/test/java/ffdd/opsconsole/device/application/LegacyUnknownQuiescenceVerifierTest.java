package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static ffdd.opsconsole.device.application.LegacyUnknownZeroCreditFixtures.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.device.dto.LegacyUnknownZeroCreditProof;
import ffdd.opsconsole.device.infrastructure.LegacyUnknownQuiescenceVerifier;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LegacyUnknownQuiescenceVerifierTest {
    @Test void exactFourCaseProofUsesTheOriginalBytesAndRootKey() {
        assertThat(verifier().verify(proof()).caseSetSha256()).isEqualTo(LegacyUnknownZeroCreditCases.MANIFEST_SHA);
        var pretty=signed(" \n"+proof().observation()+"\n");
        assertThat(verifier().verify(pretty).backendInstanceId()).isEqualTo(EPOCH);
        assertThatThrownBy(()->verifier().verify(new LegacyUnknownZeroCreditProof(pretty.observation(),proof().signature())))
                .hasMessage("LEGACY_UNKNOWN_QUIESCENCE_INVALID");
    }
    @Test void unsignedDuplicateUnknownAndCoercedFieldsAreRejected() {
        var raw=proof().observation();
        for(String invalid:new String[]{raw.replaceFirst("\\{","{\"version\":1,"),raw.replaceFirst("\\{","{\"foreign\":1,"),
                raw.replace("\"version\":1","\"version\":\"1\""),raw.replace("\"previousExited\":true","\"previousExited\":1"),
                raw.replace("\"capturedAt\":\"2026-11-01T14:59:00Z\"","\"capturedAt\":1")})
            assertThatThrownBy(()->verifier().verify(signed(invalid))).hasMessage("LEGACY_UNKNOWN_QUIESCENCE_INVALID");
    }
    @Test void foreignScopeCaseEpochBootBudgetAndExpiredProofAreRejected() {
        for(var entry:Map.of("scope","PRODUCTION","caseSetSha256","a".repeat(64),"backendInstanceId",BOOT,
                "bootId",EPOCH,"budgetSha256","a".repeat(64),"expiresAt",NOW.minusSeconds(1).toString()).entrySet()) {
            var node=observation();node.put(entry.getKey(),entry.getValue());
            assertThatThrownBy(()->verifier().verify(signed(node))).hasMessage("LEGACY_UNKNOWN_QUIESCENCE_INVALID");
        }
        var future=observation();future.put("observedAt",NOW.plusSeconds(301).toString());
        assertThatThrownBy(()->verifier().verify(signed(future))).hasMessage("LEGACY_UNKNOWN_QUIESCENCE_INVALID");
        var longProof=observation();longProof.put("expiresAt",NOW.plusSeconds(86401).toString());
        assertThatThrownBy(()->verifier().verify(signed(longProof))).hasMessage("LEGACY_UNKNOWN_QUIESCENCE_INVALID");
    }
    @Test void currentWorkerAndUnchangedBackendGenerationCannotAuthorizeCancellation() {
        var worker=observation();((ObjectNode)worker.path("workers").get(0)).put("mainPid",42);
        assertThatThrownBy(()->verifier().verify(signed(worker))).hasMessage("LEGACY_UNKNOWN_QUIESCENCE_INVALID");
        var node=observation();((ObjectNode)node.get("backend")).set("current",node.path("backend").get("previous"));
        var sameBackend=signed(node);
        assertThatThrownBy(()->verifier().verify(sameBackend)).hasMessage("LEGACY_UNKNOWN_QUIESCENCE_INVALID");
    }
    @Test void runningValidatorNeedsItsRealOldIdentityAndEveryModelKeepsItsContainer() {
        for (String field:new String[]{"pid","startTicks"}) {
            var node=observation();((ObjectNode)node.path("models").get(6).get("previous")).put(field,0);
            assertThatThrownBy(()->verifier().verify(signed(node))).hasMessage("LEGACY_UNKNOWN_QUIESCENCE_INVALID");
        }
        var node=observation();((ObjectNode)node.path("models").get(6).get("current")).put("containerId","f".repeat(64));
        assertThatThrownBy(()->verifier().verify(signed(node))).hasMessage("LEGACY_UNKNOWN_QUIESCENCE_INVALID");
    }
    @Test void alreadyStoppedRunnerCanTruthfullyHaveZeroOldPidAndTicksTogether() {
        var node=observation();var previous=(ObjectNode)node.path("models").get(0).get("previous");
        previous.put("pid",0).put("startTicks",0);
        assertThat(verifier().verify(signed(node)).models().get(0).previous().pid()).isZero();
        previous.put("startTicks",1);
        assertThatThrownBy(()->verifier().verify(signed(node))).hasMessage("LEGACY_UNKNOWN_QUIESCENCE_INVALID");
    }
    @Test void allFourOriginalJournalFingerprintsAndSevenUniqueRolesAreRequired() {
        var node=observation();((ObjectNode)node.path("journals").get(0)).put("callsSha256","a".repeat(64));
        assertThatThrownBy(()->verifier().verify(signed(node))).hasMessage("LEGACY_UNKNOWN_QUIESCENCE_INVALID");
        var duplicate=observation();((ObjectNode)duplicate.path("models").get(0)).put("role","validator");
        assertThatThrownBy(()->verifier().verify(signed(duplicate))).hasMessage("LEGACY_UNKNOWN_QUIESCENCE_INVALID");
    }
    @Test void enabledFalseDoesNotWeakenRootProofRequirements() {
        var env=environment().withProperty("nexion.compute-v2.enabled","false");
        assertThat(new LegacyUnknownQuiescenceVerifier(env,CLOCK,()->BOOT,EPOCH).verify(proof())).isNotNull();
        env.setProperty("nexion.compute-v2.proof-secret","");
        assertThatThrownBy(()->new LegacyUnknownQuiescenceVerifier(env,CLOCK,()->BOOT,EPOCH).verify(proof()))
                .hasMessage("LEGACY_UNKNOWN_QUIESCENCE_INVALID");
    }
}
