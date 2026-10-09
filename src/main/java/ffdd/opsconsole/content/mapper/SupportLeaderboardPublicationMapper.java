package ffdd.opsconsole.content.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** Immutable publication SQL only; generic entity CRUD would bypass publication validation. */
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportLeaderboardPublicationMapper {
    String COLUMNS = "id,stream_key streamKey,board,rank_month rankMonth,reference_month referenceMonth,"
        + "currency,rank_currency rankCurrency,scope,groups_key groupsKey,definition_version definitionVersion,"
        + "source_version sourceVersion,view_version viewVersion,comparison_key comparisonKey,"
        + "evaluated_at evaluatedAt,published_at publishedAt,state,payload,payload_hash payloadHash";

    @Insert("INSERT INTO nx_support_leaderboard_latest(stream_key,publication_id) VALUES(#{streamKey},NULL) "
        + "ON DUPLICATE KEY UPDATE stream_key=VALUES(stream_key)")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    int ensurePointer(@Param("streamKey") String streamKey);

    @Select("SELECT publication_id FROM nx_support_leaderboard_latest WHERE stream_key=#{streamKey} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long lockPointer(@Param("streamKey") String streamKey);

    @Select("SELECT " + COLUMNS + " FROM nx_support_leaderboard_publication WHERE stream_key=#{streamKey} AND view_version=#{viewVersion} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Stored byVersionForUpdate(@Param("streamKey") String streamKey, @Param("viewVersion") String viewVersion);

    @Select("SELECT " + COLUMNS + " FROM nx_support_leaderboard_publication WHERE id=#{id}")
    @Options(useCache = false)
    Stored byId(@Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM nx_support_leaderboard_publication WHERE id="
        + "(SELECT publication_id FROM nx_support_leaderboard_latest WHERE stream_key=#{streamKey})")
    @Options(useCache = false)
    Stored latest(@Param("streamKey") String streamKey);

    @Insert("""
        INSERT INTO nx_support_leaderboard_publication
        (stream_key,board,rank_month,reference_month,currency,rank_currency,scope,groups_key,
         definition_version,source_version,view_version,comparison_key,evaluated_at,published_at,state,payload,payload_hash)
        VALUES(#{streamKey},#{board},#{rankMonth},#{referenceMonth},#{currency},#{rankCurrency},#{scope},#{groupsKey},
         #{definitionVersion},#{sourceVersion},#{viewVersion},#{comparisonKey},#{evaluatedAt},UTC_TIMESTAMP(6),#{state},#{payload},#{payloadHash})
        """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    int insert(Map<String,Object> values);

    @Update("UPDATE nx_support_leaderboard_latest SET publication_id=#{nextId} "
        + "WHERE stream_key=#{streamKey} AND publication_id <=> #{previousId}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    int advance(@Param("streamKey") String streamKey, @Param("previousId") Long previousId, @Param("nextId") long nextId);

    @Select("SELECT " + COLUMNS + " FROM nx_support_leaderboard_publication "
        + "WHERE board=#{board} AND rank_month <=> #{rankMonth} AND rank_currency <=> #{rankCurrency} "
        + "AND scope=#{scope} AND groups_key=#{groupsKey} AND state='COMPLETE' "
        + "AND published_at >= #{from} AND published_at < #{to} ORDER BY published_at,id")
    @Options(useCache = false)
    List<Stored> previousDay(Map<String,Object> identity);

    record Stored(long id, String streamKey, String board, String rankMonth, String referenceMonth,
                  String currency, String rankCurrency, String scope, String groupsKey, String definitionVersion,
                  String sourceVersion, String viewVersion, String comparisonKey, LocalDateTime evaluatedAt,
                  LocalDateTime publishedAt, String state, String payload, String payloadHash) { }
}
