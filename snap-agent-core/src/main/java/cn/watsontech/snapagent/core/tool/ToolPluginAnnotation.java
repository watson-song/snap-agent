package cn.watsontech.snapagent.core.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a tool plugin provider.
 * The annotated class must implement {@link ToolProvider}.
 * Plugin metadata (id, toolType, version, etc.) is read from this annotation
 * at scan time by {@code PluginMetadataScanner}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolPluginAnnotation {
    String id();

    String toolType();

    String displayName() default "";

    String description() default "";

    String version() default "1.0.0";

    boolean isDefault() default false;
}
