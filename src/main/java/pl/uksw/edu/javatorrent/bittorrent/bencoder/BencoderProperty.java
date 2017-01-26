package pl.uksw.edu.javatorrent.bittorrent.bencoder;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Created by mateusz on 2016-10-28.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface BencoderProperty {
    String value() default "";
}
