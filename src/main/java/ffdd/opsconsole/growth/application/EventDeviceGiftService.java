package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.promotion.application.PromotionNativeContractResolver;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

/** One device delivery contract shared by H4 claims and Genesis raffle prizes. */
@Service
@RequiredArgsConstructor
public class EventDeviceGiftService {
    private final PromotionMapper db;

    /** Validate the combined quantity for one product before opening a draw; no stock is reserved. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void validatePrize(String productNo, int totalQty) {
        require(totalQty > 0 && totalQty <= 100, "H4_EVENT_GIFT_QUANTITY_INVALID");
        Map<String, Object> product = db.product(productNo, true);
        Map<String, Object> sku = db.requiredRow("SELECT * FROM nx_admin_device_sku WHERE sku_id=? AND is_deleted=0 FOR UPDATE", productNo);
        String failure=readinessFailure(product,sku,totalQty);
        require(failure==null,failure);
    }

    public static String readinessFailure(Map<String,Object> product,Map<String,Object> sku,int quantity){
        if(quantity<=0||quantity>100)return "H4_EVENT_GIFT_QUANTITY_INVALID";
        if(PromotionNativeContractResolver.giftFailure(product,sku)!=null)return "H4_EVENT_GIFT_PRODUCT_UNAVAILABLE";
        return "FINITE".equals(product.get("inventory_mode"))&&number(product.get("stock"))>=quantity?null:"H4_EVENT_GIFT_STOCK_UNAVAILABLE";
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Map<String, Object> award(Long userId, String sourceId, String productNo, int quantity) {
        require(sourceId != null && !sourceId.isBlank(), "H4_EVENT_GIFT_SOURCE_REQUIRED");
        Map<String, Object> user = db.user(userId, true);
        require("ACTIVE".equals(user.get("status")) && number(user.get("sandbox")) == 0, "H4_EVENT_ACCOUNT_UNAVAILABLE");
        require(quantity > 0 && quantity <= 100, "H4_EVENT_GIFT_QUANTITY_INVALID");
        require(db.occupiedDeviceSlots(userId) + quantity <= db.deviceSlotCap(), "H4_EVENT_DEVICE_CAPACITY_UNAVAILABLE");
        Map<String, Object> product = db.product(productNo, true);
        Map<String, Object> sku = db.requiredRow("SELECT * FROM nx_admin_device_sku WHERE sku_id=? AND is_deleted=0 FOR UPDATE", productNo);
        require(PromotionNativeContractResolver.giftFailure(product, sku) == null, "H4_EVENT_GIFT_PRODUCT_UNAVAILABLE");
        require(db.write("UPDATE nx_product SET stock=stock-?,updated_at=NOW(6) WHERE id=? AND inventory_mode='FINITE' AND stock>=? AND is_deleted=0",
                quantity, product.get("id"), quantity) == 1, "H4_EVENT_GIFT_STOCK_UNAVAILABLE");
        String receipt = "EVG-" + sha256(sourceId + ":" + userId).substring(0, 48);
        List<String> instances = new ArrayList<>();
        for (int i = 0; i < quantity; i++) {
            String instance = "EVD-" + sha256(receipt + ":" + i).substring(0, 48);
            changed(db.write("""
                    INSERT INTO nx_user_device(user_id,source_order_no,product_id,product_code,product_tier,instance_no,name,device_type,
                      generation,gpu_model,vram_total_gb,base_power_w,dc_location,price_usdt_snapshot,ownership_status,source_channel,
                      source_environment,run_id,status,hashrate,daily_usdt,daily_nex,last_seen_at,purchased_at,activated_at,pending_deactivate,row_version,is_deleted)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,0,'OWNED','PROMOTION_GIFT','PRODUCTION','','ACTIVE',?,?,?,NOW(6),NOW(6),NOW(6),0,0,0)
                    """, userId, null, product.get("id"), productNo, product.get("tier"), instance, product.get("name"), product.get("product_type"),
                    product.get("generation"), product.get("gpu_model"), product.get("vram_total_gb"), PromotionNativeContractResolver.powerWatts(sku.get("power_text")),
                    sku.get("datacenter"), product.get("hashrate"), product.get("estimated_daily_usdt"), product.get("daily_nex")));
            instances.add(instance);
        }
        return values("rewardType", "DEVICE", "quantity", quantity, "receiptId", receipt, "productNo", productNo, "instances", instances);
    }

}
