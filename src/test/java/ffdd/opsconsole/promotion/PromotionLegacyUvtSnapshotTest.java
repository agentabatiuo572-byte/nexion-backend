package ffdd.opsconsole.promotion;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.promotion.application.*;
import ffdd.opsconsole.promotion.domain.PromotionLegacyAssetView;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PromotionLegacyUvtSnapshotTest {
    @Test void currentViewChangesOnlyKnownAssetFieldsAndLeavesOriginalHashAndIdentifiersAlone() {
        var original=values("award",values("reward",values("type","NEX","amount","5")),
                "sourceRef","NEX:original-business","metadata",values("type","NEX"),"asset","NEX");
        String raw=json(original),before=hash(original);
        var view=PromotionLegacyAssetView.validationView(original);
        assertEquals("UVT",view.path("award").path("reward").path("type").asText());
        assertEquals("UVT",view.path("asset").asText());
        assertEquals("NEX",view.path("metadata").path("type").asText());
        assertEquals("NEX:original-business",view.path("sourceRef").asText());
        assertEquals(raw,json(original));assertEquals(before,hash(original));
    }

    @Test void oldAwardsUseOnlyTheMigratedBudgetAndPublicProjectionUsesUvt() {
        var db=mock(PromotionMapper.class);
        when(db.activity("old",false)).thenReturn(values("deleted_at",null,"reserved_orders",0,"used_orders",0));
        when(db.one(anyString(),eq("old"),eq("UVT"),eq("")))
                .thenReturn(values("total",100,"reserved",0,"committed",0,"issued",0,"reversed",0));
        var award=values("productNo","sku","ruleId","rule","rewardRuleId","reward","beneficiaryRole","BUYER",
                "reward",values("type","NEX","amount","5"));
        String frozen=hash(award);
        assertTrue(new PromotionQuoteService(db,null,null,null,null,null,null).available("old",
                values("activityLimit",values("mode","UNLIMITED")),List.of(award)));
        verify(db).one(anyString(),eq("old"),eq("UVT"),eq(""));
        verify(db,never()).one(anyString(),eq("old"),eq("NEX"),eq(""));
        assertEquals("UVT",map(PromotionQuoteService.expected(List.of(award),"old",1L).get(0).get("reward")).get("type"));
        assertEquals(frozen,hash(award));
    }

    @Test void readSchemaCompatibilityDoesNotAcceptNexInNewWriteCommands() {
        var validator=new PromotionContractValidator(new ObjectMapper());
        var raw=values("rewardRuleId","reward-1","beneficiaryRole","BUYER","type","NEX","calculation","FIXED","amount","5.000000",
                "assetPolicy",values("policyId","asset-1","version",1,"contentHash","a".repeat(64)));
        assertThrows(BizException.class,()->validator.validate("RewardSpec",raw));
        assertDoesNotThrow(()->validator.validate("RewardSpec",PromotionLegacyAssetView.validationView(values("reward",raw)).get("reward")));
        assertEquals("NEX",raw.get("type"));
    }

    @Test void issuedLegacyRewardReadsUvtOriginalLedgerWithoutChangingFrozenProof() {
        var db=mock(PromotionMapper.class);var row=rewardRow();
        when(db.requiredRow(startsWith("SELECT * FROM nx_promotion_reward"),eq("old-reward"))).thenReturn(row);
        when(db.requiredRow(contains("SELECT created_at FROM nx_wallet_ledger"),eq("old-ledger"),eq(7),eq("UVT")))
                .thenReturn(values("created_at",Instant.parse("2026-10-01T00:00:00Z")));
        String raw=text(row.get("snapshot_json")),digest=text(row.get("snapshot_hash"));
        var result=new PromotionRewardService(db,null,null,null,null,null).get(7L,"old-reward",false);
        assertEquals("UVT",map(result.get("reward")).get("type"));
        assertEquals(raw,row.get("snapshot_json"));assertEquals(digest,row.get("snapshot_hash"));
        verify(db,never()).requiredRow(contains("SELECT created_at FROM nx_wallet_ledger"),eq("old-ledger"),eq(7),eq("NEX"));
    }

    @Test void tamperedFrozenRewardFailsBeforeUsingTheLedger() {
        var db=mock(PromotionMapper.class);var row=rewardRow();
        row.put("snapshot_json",text(row.get("snapshot_json")).replace("5.000000","9.000000"));
        when(db.requiredRow(startsWith("SELECT * FROM nx_promotion_reward"),eq("old-reward"))).thenReturn(row);
        assertEquals("PROMOTION_REWARD_SNAPSHOT_INVALID",assertThrows(BizException.class,
                ()->new PromotionRewardService(db,null,null,null,null,null).get(7L,"old-reward",false)).getMessage());
        verify(db,never()).requiredRow(contains("nx_wallet_ledger"),any(),any(),any());
    }

    @Test void reconciliationFindsTheMigratedOriginalPostingInsteadOfClassifyingItAbsent() {
        var db=mock(PromotionMapper.class);var row=rewardRow();
        var orders=mock(PromotionOrderService.class);
        when(db.requiredRow(contains("nx_promotion_reward"),eq("old-reward"))).thenReturn(row);
        when(db.one(contains("nx_earnings_release_entry"),eq("old-reward"),eq(7),eq("UVT")))
                .thenReturn(values("amount",new BigDecimal("5")));
        when(db.one(contains("nx_wallet_ledger"),eq("PROMOTION-ISSUE-old-reward"),eq(7),eq("UVT")))
                .thenReturn(values("amount",new BigDecimal("5")));
        when(db.requiredRow(contains("SELECT created_at FROM nx_wallet_ledger"),eq("old-ledger"),eq(7),eq("UVT")))
                .thenReturn(values("created_at",Instant.parse("2026-10-01T00:00:00Z")));
        when(db.write(anyString(),any(Object[].class))).thenReturn(1);
        var audit=mock(ffdd.opsconsole.shared.audit.AuditLogService.class);
        var result=new PromotionRewardService(db,orders,null,null,audit,null).reconcile("old-reward","reconcile-original");
        assertEquals("ISSUED",result.get("state"));
        verify(db).one(contains("nx_earnings_release_entry"),eq("old-reward"),eq(7),eq("UVT"));
        verify(db,never()).one(contains("nx_earnings_release_entry"),eq("old-reward"),eq(7),eq("NEX"));
    }

    @Test void oldOrderReadbackProjectsRewardsOnlyAfterVerifyingItsFrozenReservation() {
        var db=mock(PromotionMapper.class);var original=rewardRow();
        var receipt=values("promotionQuoteId","unchanged-quote","items",List.of(values("productName","original")),
                "rewards",List.of(values("reward",values("type","NEX","amount","5.000000"),"disclosure",values("deviceName",null))));
        String raw=json(receipt),frozen=text(original.get("snapshot_json"));
        when(db.order("old-order",false)).thenReturn(values("user_id",7,"amount_usdt",10));
        when(db.one(contains("projection_json"),eq("old-order"))).thenReturn(values("buyer_id",7,"projection_json",raw));
        when(db.reservations("old-order",false)).thenReturn(List.of(original));
        var service=new PromotionOrderService(db,null,null,null,null,null);
        var projected=service.orderProjection(7L,"old-order");
        assertEquals("unchanged-quote",projected.get("promotionQuoteId"));
        assertEquals("UVT",map(maps(projected.get("rewards")).get(0).get("reward")).get("type"));
        assertEquals(frozen,original.get("snapshot_json"));
        original.put("snapshot_hash","f".repeat(64));
        assertEquals("PROMOTION_REWARD_SNAPSHOT_INVALID",assertThrows(BizException.class,
                ()->service.orderProjection(7L,"old-order")).getMessage());
        verify(db,never()).write(anyString(),any(Object[].class));
    }

    private static Map<String,Object> rewardRow() {
        var snapshot=values("award",values("reward",values("type","NEX","amount","5.000000",
                        "assetPolicy",values("policyId","asset-1","version",1))),
                "contract",values("title",values(),"terms",values()),
                "policies",values("asset-1:1",values("content",values("kind","ASSET","availabilityDescription",values())),
                        "$refund",values("content",values("terms",values()))));
        return values("obligation_id","old-reward","beneficiary_id",7,"snapshot_json",json(snapshot),"snapshot_hash",hash(snapshot),
                "status","ISSUED","version",1,"unit_seq",0,"revision",1,"activity_id","old","order_no","old-order",
                "original_earnings_entry_no","old-entry","original_ledger_no","old-ledger",
                "updated_at",Instant.parse("2026-10-01T00:00:00Z"));
    }
}
