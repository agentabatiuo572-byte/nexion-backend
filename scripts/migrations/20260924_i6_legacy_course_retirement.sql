-- #67: the old getting-started course is a separate published help article.
-- The message-key retirement cannot take that article off the App course list.
-- Keep its identifier, progress, versions, and operator copy for a reviewed edit.
UPDATE nx_help_article AS course
   SET course.status = 2,
       course.revision = course.revision + 1,
       course.updated_at = NOW()
 WHERE course.article_code LIKE 'learn.%.nexion-getting-started'
   AND course.status = 1
   AND course.is_deleted = 0
   AND (
     REGEXP_LIKE(course.title, '(^|[^[:alnum:]])nexion', 'i')
     OR REGEXP_LIKE(COALESCE(course.content, ''), '(^|[^[:alnum:]])nexion', 'i')
     OR EXISTS (
       SELECT 1 FROM nx_i18n_message AS copy
        WHERE copy.message_key IN ('learn.nexion-getting-started.title',
                                   'learn.nexion-getting-started.body')
          AND copy.status = 1 AND copy.is_deleted = 0
          AND REGEXP_LIKE(copy.message_value, '(^|[^[:alnum:]])nexion', 'i')
     )
   );

-- A still-published legacy course with retired branding is a failed migration.
SET @i6_legacy_course_retired = (
  SELECT COUNT(*) = 0 FROM nx_help_article AS course
   WHERE course.article_code LIKE 'learn.%.nexion-getting-started'
     AND course.status = 1 AND course.is_deleted = 0
     AND (REGEXP_LIKE(course.title, '(^|[^[:alnum:]])nexion', 'i')
       OR REGEXP_LIKE(COALESCE(course.content, ''), '(^|[^[:alnum:]])nexion', 'i')
       OR EXISTS (
         SELECT 1 FROM nx_i18n_message AS copy
          WHERE copy.message_key IN ('learn.nexion-getting-started.title',
                                     'learn.nexion-getting-started.body')
            AND copy.status = 1 AND copy.is_deleted = 0
            AND REGEXP_LIKE(copy.message_value, '(^|[^[:alnum:]])nexion', 'i')
       ))
);
SET @sql = IF(@i6_legacy_course_retired, 'SELECT 1',
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''I6_LEGACY_COURSE_STILL_PUBLIC''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
