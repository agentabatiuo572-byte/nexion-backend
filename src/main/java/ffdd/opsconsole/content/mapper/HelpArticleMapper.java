package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.domain.SessionReplyTemplateView;
import ffdd.opsconsole.content.domain.SessionScriptView;
import ffdd.opsconsole.content.domain.SupportFaqView;
import ffdd.opsconsole.content.infrastructure.HelpArticleEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface HelpArticleMapper extends BaseMapper<HelpArticleEntity> {
    @Select("""
            SELECT
              article_code AS id,
              category,
              title AS question,
              content AS answer,
              CASE status WHEN 1 THEN 'PUBLISHED' ELSE 'DRAFT' END AS status,
              COALESCE(surface, 'Help Center') AS surface,
              updated_at AS updatedAt
            FROM nx_help_article
            WHERE is_deleted=0 AND format='faq'
            ORDER BY sort_order ASC, updated_at DESC, id DESC
            """)
    List<SupportFaqView> listFaqs();

    @Select("""
            SELECT
              article_code AS id,
              category,
              title AS question,
              content AS answer,
              CASE status WHEN 1 THEN 'PUBLISHED' ELSE 'DRAFT' END AS status,
              COALESCE(surface, 'Help Center') AS surface,
              updated_at AS updatedAt
            FROM nx_help_article
            WHERE is_deleted=0 AND format='faq' AND article_code=#{faqId}
            LIMIT 1
            """)
    SupportFaqView findFaq(@Param("faqId") String faqId);

    @Select("SELECT COALESCE(MAX(sort_order),0) FROM nx_help_article WHERE is_deleted=0 AND format='faq'")
    int maxFaqSortOrder();

    @Update("""
            UPDATE nx_help_article
               SET title=#{question},
                   content=#{answer},
                   category=#{category},
                   surface=#{surface},
                   status=#{status},
                   updated_at=#{now}
             WHERE article_code=#{faqId} AND format='faq' AND is_deleted=0
            """)
    int updateFaq(
            @Param("faqId") String faqId,
            @Param("category") String category,
            @Param("surface") String surface,
            @Param("question") String question,
            @Param("answer") String answer,
            @Param("status") int status,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_help_article
               SET status=#{status}, updated_at=#{now}
             WHERE article_code=#{faqId} AND format='faq' AND is_deleted=0
            """)
    int updateFaqStatus(@Param("faqId") String faqId, @Param("status") int status, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_help_article
               SET is_deleted=1, updated_at=#{now}
             WHERE article_code=#{faqId} AND format='faq' AND is_deleted=0
            """)
    int deleteFaq(@Param("faqId") String faqId, @Param("now") LocalDateTime now);

    @Select("""
            SELECT
              article_code AS id,
              title AS scriptGroup,
              content AS text,
              COALESCE(surface, '—') AS ctaPath,
              CASE status WHEN 1 THEN 'published' ELSE 'draft' END AS status,
              COALESCE(level, '全量') AS audience,
              updated_at AS updatedAt
            FROM nx_help_article
            WHERE is_deleted=0 AND format='session_script'
            ORDER BY sort_order ASC, updated_at DESC, id DESC
            """)
    List<SessionScriptView> listSessionScripts();

    @Select("""
            SELECT
              article_code AS id,
              title AS scriptGroup,
              content AS text,
              COALESCE(surface, '—') AS ctaPath,
              CASE status WHEN 1 THEN 'published' ELSE 'draft' END AS status,
              COALESCE(level, '全量') AS audience,
              updated_at AS updatedAt
            FROM nx_help_article
            WHERE is_deleted=0 AND format='session_script' AND article_code=#{scriptId}
            LIMIT 1
            """)
    SessionScriptView findSessionScript(@Param("scriptId") String scriptId);

    @Select("SELECT COALESCE(MAX(sort_order),0) FROM nx_help_article WHERE is_deleted=0 AND format=#{format}")
    int maxSortOrderByFormat(@Param("format") String format);

    @Update("""
            UPDATE nx_help_article
               SET status=#{status}, updated_at=#{now}
             WHERE article_code=#{scriptId} AND format='session_script' AND is_deleted=0
            """)
    int updateSessionScriptStatus(@Param("scriptId") String scriptId, @Param("status") int status, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_help_article
               SET level=#{audience}, updated_at=#{now}
             WHERE article_code=#{scriptId} AND format='session_script' AND is_deleted=0
            """)
    int updateSessionScriptAudience(@Param("scriptId") String scriptId, @Param("audience") String audience, @Param("now") LocalDateTime now);

    @Select("""
            SELECT
              article_code AS id,
              COALESCE(level, title, 'support') AS type,
              content AS text,
              CASE status WHEN 1 THEN 'published' ELSE 'draft' END AS status,
              updated_at AS updatedAt
            FROM nx_help_article
            WHERE is_deleted=0 AND format='session_reply_template'
            ORDER BY sort_order ASC, updated_at DESC, id DESC
            """)
    List<SessionReplyTemplateView> listSessionReplyTemplates();

    @Select("""
            SELECT
              article_code AS id,
              COALESCE(level, title, 'support') AS type,
              content AS text,
              CASE status WHEN 1 THEN 'published' ELSE 'draft' END AS status,
              updated_at AS updatedAt
            FROM nx_help_article
            WHERE is_deleted=0 AND format='session_reply_template' AND article_code=#{templateId}
            LIMIT 1
            """)
    SessionReplyTemplateView findSessionReplyTemplate(@Param("templateId") String templateId);

    @Update("""
            UPDATE nx_help_article
               SET status=#{status}, updated_at=#{now}
             WHERE article_code=#{templateId} AND format='session_reply_template' AND is_deleted=0
            """)
    int updateSessionReplyTemplateStatus(@Param("templateId") String templateId, @Param("status") int status, @Param("now") LocalDateTime now);
}
