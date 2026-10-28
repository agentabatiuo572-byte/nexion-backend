package ffdd.opsconsole.finance.application;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.risk.dto.EarningsManualReleaseRequest;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real central service/adapter receipts with later actual source tails; tests the r37 missing predicate. */
class SupportFundEarningsTailTest {
    final EarningsCentralFundEvidenceTest f=new EarningsCentralFundEvidenceTest();
    @BeforeEach void storage(){f.storage();}
    @ParameterizedTest @ValueSource(strings={"USDT","UVT"})
    void canonicalIdentifiersRemainExactAcrossCreditRecoveryAndRelease(String asset)throws Exception {
        String no=f.protectedCredit(asset);f.recoveryStorage(EarningsCentralFundEvidenceTest.n("0"));f.recover(no,asset,"8");
        f.service.manualReleaseOnce(no,new EarningsManualReleaseRequest("approved investigation","superadmin"));
        var codec=new SupportFundJournalBindingService(f.raw,org.mockito.Mockito.mock(javax.sql.DataSource.class),f.json);
        for(int index=0;index<f.receipts.size();index++) {
            var r=f.receipts.get(index);
            var source=codec.read(codec.canonical(f.json.readTree(r.sourceSnapshotJson())).toString());
            var proof=codec.read(codec.canonical(f.json.readTree(r.sourceProofJson())).toString());
            int operation=index;
            assertDoesNotThrow(()->f.evidence.verify(f.operation(operation),source,proof,f.events(operation),f.inputs()));
        }
    }
    @ParameterizedTest @ValueSource(strings={"USDT","UVT"})
    void malformedIdentifiersCannotUseTheNumericCanonicalizationPath(String asset)throws Exception {
        f.protectedCredit(asset);var r=f.receipts.get(0);
        var codec=new SupportFundJournalBindingService(f.raw,org.mockito.Mockito.mock(javax.sql.DataSource.class),f.json);
        for(String invalid:List.of("0.5","true","\"100\"","9223372036854775808")) {
            var source=(ObjectNode)codec.read(codec.canonical(f.json.readTree(r.sourceSnapshotJson())).toString());
            ((ObjectNode)source.path("entry")).set("id",f.json.readTree(invalid));
            assertThrows(IllegalArgumentException.class,()->f.evidence.verify(f.operation(0),source,f.json.readTree(r.sourceProofJson()),f.events(0),f.inputs()));
        }
    }
    @ParameterizedTest @ValueSource(strings={"USDT","UVT"})
    void protectedBirthCannotBecomeWithdrawableWithoutActualRelease(String asset)throws Exception {
        String no=f.protectedCredit(asset);f.entry.put("bucket","withdrawable");
        assertThrows(IllegalArgumentException.class,()->f.verifyReceipt(0));
    }
    @ParameterizedTest @ValueSource(strings={"USDT","UVT"})
    void releaseAuthorityWithoutReleaseTimeCannotBeAProvenTail(String asset)throws Exception {
        String no=f.protectedCredit(asset);f.entry.put("releaseSource","manual");
        assertThrows(IllegalArgumentException.class,()->f.verifyReceipt(0));
        assertThrows(IllegalArgumentException.class,()->f.evidence.verifyCoverage(Set.of(7L),f.inputs(),Set.of(no+":CREDIT")));
    }
    @ParameterizedTest @ValueSource(strings={"USDT","UVT"})
    void unreleasedOriginalProtectedBucketCannotChangeToAnotherProtectedBucket(String asset)throws Exception {
        f.protectedCredit(asset);f.entry.put("bucket","pending_review");
        assertThrows(IllegalArgumentException.class,()->f.verifyReceipt(0));
    }
    @ParameterizedTest @ValueSource(strings={"USDT","UVT"})
    void unsupportedAndMalformedCurrentStateFailsClosed(String asset)throws Exception {
        f.protectedCredit(asset);var original=new LinkedHashMap<>(f.entry);
        var mutations=new ArrayList<Map<String,Object>>();
        for(Object value:List.of("unsupported",false,0))mutations.add(Map.of("bucket",value));
        for(Object value:List.of("manual","attest",false,0))mutations.add(Map.of("releaseSource",value));
        for(Object value:List.of("2026-10-11T12:00",false,0))mutations.add(Map.of("releasedAt",value));
        for(Object value:List.of(EarningsCentralFundEvidenceTest.n("-1"),EarningsCentralFundEvidenceTest.n("21"),false,"0"))mutations.add(Map.of("recoveredAmount",value));
        var assertions=new ArrayList<org.junit.jupiter.api.function.Executable>();
        for(var mutation:mutations)assertions.add(()->{f.entry.clear();f.entry.putAll(original);f.entry.putAll(mutation);
            assertThrows(IllegalArgumentException.class,()->f.verifyReceipt(0));});
        assertAll(assertions);
    }
    @ParameterizedTest @ValueSource(strings={"USDT","UVT"})
    void realLaterReleaseAfterActualRecoveryRetainsValidBirthAndRecoveryHistory(String asset)throws Exception {
        String no=f.protectedCredit(asset);f.recoveryStorage(EarningsCentralFundEvidenceTest.n("0"));var recovery=f.recover(no,asset,"8");
        f.service.manualReleaseOnce(no,new EarningsManualReleaseRequest("approved investigation","superadmin"));
        assertDoesNotThrow(()->f.verifyReceipt(0));assertDoesNotThrow(()->f.verifyReceipt(1));assertDoesNotThrow(()->f.verifyReceipt(2));
        assertDoesNotThrow(()->f.evidence.verifyCoverage(Set.of(7L),f.inputs(),Set.of(no+":CREDIT",recovery.recoveryNo()+":RECOVER",no+":RELEASE")));
        assertThrows(IllegalArgumentException.class,()->f.evidence.verifyCoverage(Set.of(7L),f.inputs(),Set.of(no+":CREDIT",recovery.recoveryNo()+":RECOVER")));
    }
    @ParameterizedTest @ValueSource(strings={"USDT","UVT"})
    void actualRecoveryAfterReleaseDoesNotInvalidateOriginalReleaseSnapshot(String asset)throws Exception {
        String no=f.protectedCredit(asset);f.service.manualReleaseOnce(no,new EarningsManualReleaseRequest("approved investigation","superadmin"));
        f.recoveryStorage(EarningsCentralFundEvidenceTest.n("0"));var recovery=f.recover(no,asset,"8");
        assertDoesNotThrow(()->f.verifyReceipt(0));assertDoesNotThrow(()->f.verifyReceipt(1));assertDoesNotThrow(()->f.verifyReceipt(2));
        assertDoesNotThrow(()->f.evidence.verifyCoverage(Set.of(7L),f.inputs(),Set.of(no+":CREDIT",no+":RELEASE",recovery.recoveryNo()+":RECOVER")));
    }
    @ParameterizedTest @ValueSource(strings={"USDT","UVT"})
    void validLookingCurrentReleaseCannotInventAReleaseForWithdrawableBirth(String asset)throws Exception {
        f.service.creditReward(7L,"PROMOTION_REWARD","reward-7",asset,EarningsCentralFundEvidenceTest.n("20"),"idem-7");
        f.entry.put("releasedAt",f.at);f.entry.put("releaseSource","manual");
        assertThrows(IllegalArgumentException.class,()->f.verifyReceipt(0));
    }
    @ParameterizedTest @ValueSource(strings={"USDT","UVT"})
    void realReleaseCannotRegressToProtectedOrLoseItsAuthority(String asset)throws Exception {
        String no=f.protectedCredit(asset);f.service.manualReleaseOnce(no,new EarningsManualReleaseRequest("approved investigation","superadmin"));
        assertDoesNotThrow(()->f.verifyReceipt(0));f.entry.put("bucket","bonus_locked");
        assertThrows(IllegalArgumentException.class,()->f.verifyReceipt(0));f.entry.put("bucket","withdrawable");f.entry.put("releaseSource",null);
        assertThrows(IllegalArgumentException.class,()->f.verifyReceipt(0));
    }
    @ParameterizedTest @ValueSource(strings={"USDT","UVT"})
    void recoveryPhaseCannotInventAMutableReleaseWithinItsSnapshot(String asset)throws Exception {
        f.protectedCredit(asset);f.recoveryStorage(EarningsCentralFundEvidenceTest.n("0"));f.recover((String)f.entry.get("entryNo"),asset,"8");
        var r=f.receipts.get(1);var source=(ObjectNode)f.json.readTree(r.sourceSnapshotJson());
        ((ObjectNode)source.path("entry")).put("releaseSource","manual");
        assertThrows(IllegalArgumentException.class,()->f.evidence.verify(f.operation(1),source,f.json.readTree(r.sourceProofJson()),f.events(1),f.inputs()));
    }
}
