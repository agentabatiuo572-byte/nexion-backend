package ffdd.opsconsole.finance.application;

import static ffdd.opsconsole.finance.application.B140TestWalletResetEvidence.*;

import ffdd.opsconsole.finance.domain.SupportFundReplay.Cash;
import ffdd.opsconsole.finance.domain.SupportFundReplay.Point;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.finance.mapper.B140TestWalletResetMapper;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;

/** Loaded only by the B140 maintenance process. The caller owns the write transaction. */
@RequiredArgsConstructor
public class B140TestWalletResetService {
    private final B140TestWalletResetMapper reset;
    private final EarningsReleaseMapper earnings;
    private final SupportFundJournalMapper raw;
    private final SupportFundJournalBindingService journal;

    public SupportFundJournalFacade.BoundReceipt execute(String serverUuid, long customerId, String entryNo, String digest) {
        var identity = reset.serverIdentity();
        require(serverUuid.equals(identity.get("serverUuid")) && "nexion".equals(identity.get("databaseName")), "database identity");
        require(earnings.lockRecoveryUser(customerId, 0) != null, "test account missing");
        var before = journal.begin(List.of(customerId));
        var wallet = raw.lockWallet(customerId).get(0);
        long walletId = number(wallet, "id");
        String incarnation = (String) wallet.get("incarnation");

        var credit = raw.operation("EARNINGS:CREDIT:" + entryNo);
        require(credit != null && digest.equals(credit.get("receiptSha256")), "selected original receipt digest");
        long creditId = number(credit, "id");
        var creditParties = raw.parties(creditId).stream().map(party -> number(party, "customerId")).toList();
        var creditLegs = raw.legs(creditId);
        verifyLeg(creditLegs, incarnation, 1);
        var creditEvents = journal.decodeReceipt(credit, creditParties, creditLegs);
        var entry = earnings.fundEntry(entryNo);
        verifyOriginal(journal, credit, creditEvents, entry, entryNo, customerId, walletId);
        require(creditParties.equals(List.of(customerId)), "original credit party");

        var source = reset.lockSource(entryNo);
        var existing = raw.operation(key(entryNo));
        require((source == null) == (existing == null), "reset source and receipt do not match");
        if (existing != null) {
            require(serverUuid.equals(source.get("serverUuid")), "original reset server");
        } else {
            var birth = raw.operation("AUTH_FUND_BIRTH:" + customerId);
            require(birth != null && "AUTH_FUND_BIRTH".equals(birth.get("businessType"))
                    && number(wallet, "revision") == 1, "test wallet must have only birth and credit");
            long birthId = number(birth, "id");
            var birthParties = raw.parties(birthId).stream().map(party -> number(party, "customerId")).toList();
            var birthLegs = raw.legs(birthId);
            verifyLeg(birthLegs, incarnation, 0);
            require(birthParties.equals(List.of(customerId)) && journal.decodeReceipt(birth, birthParties, birthLegs).isEmpty()
                    && raw.customerOperations(customerId).equals(List.of(birthId, creditId))
                    && raw.birthCandidates(walletId, customerId).size() == 2, "test wallet history");
            require(reset.resetAvailableReward(walletId, customerId) == 1, "pristine 60 UVT wallet required");
            require(reset.insertSource(entryNo, customerId, walletId, creditId, digest, serverUuid) == 1, "reset source insert");
            source = reset.lockSource(entryNo);
        }

        var rows = raw.journal(incarnation, 1, 2);
        require(rows.size() == 1, "wallet reset revision missing");
        var row = rows.get(0);
        var event = event(entryNo, customerId);
        var proof = tree(journal, Map.of("cashBefore", SupportFundReadService.walletSnapshot(row, "old", 1),
                "cashAfter", SupportFundReadService.walletSnapshot(row, "new", 2), "cashTrace", List.of(Map.of(
                        "sourceId", SOURCE_TABLE + ":" + entryNo, "eventIds", List.of(event.id()), "journal", row))));
        if (existing == null) {
            var receipt = journal.bind(before, new SupportFundJournalFacade.ReceiptInput(key(entryNo), BUSINESS_TYPE,
                    PHASE, SOURCE_TABLE, entryNo, "PRODUCTION", tree(journal, List.of(event)).toString(),
                    tree(journal, source).toString(), proof.toString()));
            existing = raw.operation(receipt.operationKey());
        }

        long operationId = number(existing, "id");
        var parties = raw.parties(operationId).stream().map(party -> number(party, "customerId")).toList();
        var legs = raw.legs(operationId);
        verifyLeg(legs, incarnation, 2);
        var events = journal.decodeReceipt(existing, parties, legs);
        var actualProof = journal.read((String) existing.get("sourceProofJson"));
        require(proof.equals(journal.canonical(actualProof)), "reset receipt cash proof");
        verify(journal, existing, journal.read((String) existing.get("sourceSnapshotJson")), actualProof, events,
                List.of(new Point(incarnation, 2, customerId, cash(row, "old"), cash(row, "new"), List.of(event.id()))),
                source, credit, creditEvents, entry, parties, creditParties, creditLegs);
        return new SupportFundJournalFacade.BoundReceipt(operationId, key(entryNo), (String) existing.get("receiptSha256"), 1);
    }

    private static Cash cash(Map<String, Object> row, String side) {
        var wallet = SupportFundJournalBindingService.tuple(row, side);
        return new Cash(wallet.usdt(), wallet.nex(), wallet.pending(), wallet.risk());
    }

    private static void verifyLeg(List<Map<String, Object>> legs, String incarnation, long revision) {
        require(legs.size() == 1 && incarnation.equals(legs.get(0).get("incarnation"))
                && number(legs.get(0), "revision") == revision, "wallet journal binding at revision " + revision);
    }
}
