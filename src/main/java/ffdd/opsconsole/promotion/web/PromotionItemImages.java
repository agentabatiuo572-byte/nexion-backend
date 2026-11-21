package ffdd.opsconsole.promotion.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Attach current product photos after quote hashing and idempotent command storage. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PromotionItemImages {
    String value() default "";
}
