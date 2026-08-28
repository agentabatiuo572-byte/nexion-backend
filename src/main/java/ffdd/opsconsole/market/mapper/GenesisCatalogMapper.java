package ffdd.opsconsole.market.mapper;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface GenesisCatalogMapper {
    @Select("SELECT id,tiers_version tiersVersion,market_open_state marketOpenState,market_open_state_version marketOpenStateVersion,closed_notice_key closedNoticeKey,last_change lastChange,next_tier_seq nextTierSeq FROM nx_genesis_catalog_state WHERE id=1 FOR UPDATE")
    CatalogState lockState();

    @Select("SELECT id,tiers_version tiersVersion,market_open_state marketOpenState,market_open_state_version marketOpenStateVersion,closed_notice_key closedNoticeKey,last_change lastChange,next_tier_seq nextTierSeq FROM nx_genesis_catalog_state WHERE id=1")
    CatalogState state();

    @Select("SELECT tier_id tierId,range_from rangeFrom,range_to rangeTo,price_usdt priceUsdt FROM nx_genesis_tier WHERE status='ACTIVE' AND is_deleted=0 ORDER BY range_from,id")
    List<TierRow> activeTiers();

    @Select("SELECT tier_id tierId,range_from rangeFrom,range_to rangeTo,price_usdt priceUsdt FROM nx_genesis_tier WHERE tier_id=#{tierId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    TierRow lockTier(@Param("tierId") String tierId);

    @Insert("INSERT INTO nx_genesis_tier(tier_id,range_from,range_to,price_usdt,status,is_deleted) VALUES(#{tierId},#{rangeFrom},#{rangeTo},#{priceUsdt},'ACTIVE',0)")
    int insertTier(TierRow row);

    @Update("UPDATE nx_genesis_tier SET range_to=#{rangeTo},price_usdt=#{priceUsdt},updated_at=NOW() WHERE tier_id=#{tierId} AND status='ACTIVE' AND is_deleted=0")
    int updateTier(@Param("tierId") String tierId, @Param("rangeTo") int rangeTo, @Param("priceUsdt") BigDecimal priceUsdt);

    @Update("UPDATE nx_genesis_tier SET range_from=#{rangeFrom},updated_at=NOW() WHERE tier_id=#{tierId} AND status='ACTIVE' AND is_deleted=0")
    int updateTierFrom(@Param("tierId") String tierId, @Param("rangeFrom") int rangeFrom);

    @Update("UPDATE nx_genesis_tier SET range_to=#{rangeTo},updated_at=NOW() WHERE tier_id=#{tierId} AND status='ACTIVE' AND is_deleted=0")
    int updateTierTo(@Param("tierId") String tierId, @Param("rangeTo") int rangeTo);

    @Update("UPDATE nx_genesis_tier SET status='DELETED',is_deleted=1,updated_at=NOW() WHERE tier_id=#{tierId} AND status='ACTIVE' AND is_deleted=0")
    int softDeleteTier(@Param("tierId") String tierId);

    @Update("UPDATE nx_genesis_catalog_state SET tiers_version=tiers_version+1,next_tier_seq=#{nextTierSeq},updated_at=NOW() WHERE id=1 AND tiers_version=#{expectedVersion}")
    int advanceTierVersion(@Param("expectedVersion") long expectedVersion, @Param("nextTierSeq") long nextTierSeq);

    @Update("UPDATE nx_genesis_catalog_state SET market_open_state=#{state},closed_notice_key=#{noticeKey},market_open_state_version=market_open_state_version+1,last_change=#{lastChange},updated_at=NOW() WHERE id=1 AND market_open_state_version=#{expectedVersion}")
    int updateMarketState(@Param("state") String state, @Param("noticeKey") String noticeKey,
                          @Param("lastChange") String lastChange, @Param("expectedVersion") long expectedVersion);

    @Select("SELECT COALESCE(COUNT(*),0) FROM nx_genesis_holding WHERE is_deleted=0")
    long soldCount();

    record CatalogState(Long id,Long tiersVersion,String marketOpenState,Long marketOpenStateVersion,
                        String closedNoticeKey,String lastChange,Long nextTierSeq) { }
    record TierRow(String tierId,Integer rangeFrom,Integer rangeTo,BigDecimal priceUsdt) { }
}
