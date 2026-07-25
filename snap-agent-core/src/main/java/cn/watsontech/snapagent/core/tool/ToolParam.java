package cn.watsontech.snapagent.core.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares parameter metadata for a @Tool method.
 * Used by ToolCallbacks.from() to generate JSON Schema.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {
    /** Human-readable description of the parameter. Required. */
    String description();
    /** Whether the parameter is required. Default true. */
    boolean required() default true;
}
