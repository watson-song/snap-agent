package cn.watsontech.snapagent.core.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation placed on a plugin class to declare its metadata.
 *
 * <p>When a JAR is uploaded, {@code PluginMetadataScanner} looks for this
 * annotation first. If not found, it falls back to
 * {@code META-INF/snap-agent/plugin-info.yml}.</p>
 *
 * <p>Example:
 * <pre>
 *   {@literal @}ToolPlugin(id = "remote-log", toolType = "log_read", version = "1.0.0")
 *   public class RemoteLogPlugin {
 *       {@literal @}Tool(name = "log_read", description = "read remote log")
 *       public String readLog({@literal @}ToolParam(description = "file path") String path) {
 *           ...
 *       }
 *   }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolPlugin {

    /** Unique plugin identifier (regex {@code ^[a-zA-Z0-9_-]+$}). */
    String id();

    /** Tool type — the LLM-visible tool name used for routing. */
    String toolType() default "";

    /** Human-readable display name. */
    String displayName() default "";

    /** Plugin version (semantic versioning recommended). */
    String version() default "1.0.0";

    /** Plugin description. */
    String description() default "";

    /** Whether this plugin is the default for its toolType. */
    boolean isDefault() default false;
}
