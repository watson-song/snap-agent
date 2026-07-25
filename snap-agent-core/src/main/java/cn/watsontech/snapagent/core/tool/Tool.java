package cn.watsontech.snapagent.core.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a method as an LLM-callable tool.
 * ToolCallbacks.from() scans for this annotation and builds ToolCallback instances.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {
    /** Tool name. Empty string = use method name. */
    String name() default "";
    /** Human-readable description for the LLM. Required. */
    String description();
    /** If true, result goes directly to user (not back to LLM). */
    boolean returnDirect() default false;
}
