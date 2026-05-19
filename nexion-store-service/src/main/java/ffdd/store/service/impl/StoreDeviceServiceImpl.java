package ffdd.store.service.impl;

import ffdd.store.domain.StoreDevice;
import ffdd.store.service.StoreDeviceService;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class StoreDeviceServiceImpl implements StoreDeviceService {
    @Override
    public List<StoreDevice> listOnSale() {
        StoreDevice s1 = new StoreDevice();
        s1.setId(1L);
        s1.setDeviceNo("NX-S1");
        s1.setName("NexionBox S1");
        s1.setType("NEXION_BOX");
        s1.setTier("S1");
        s1.setStatus("ON_SALE");
        s1.setPriceUsdt(new BigDecimal("299.00"));
        s1.setHashrate(new BigDecimal("4.20"));
        s1.setEstimatedDailyUsdt(new BigDecimal("38.56"));
        s1.setDailyUsdt(new BigDecimal("38.56"));
        s1.setDailyNex(new BigDecimal("720.00"));
        s1.setStock(280);
        s1.setCoverUrl("/img/products/nexionbox-s1-v4.png");
        return List.of(s1);
    }
}

