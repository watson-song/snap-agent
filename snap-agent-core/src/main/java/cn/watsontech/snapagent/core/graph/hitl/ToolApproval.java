package cn.watsontech.snapagent.core.graph.hitl;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a @Tool method as requiring human approval before execution.
 * When required=true, ToolsNode throws InterruptException to pause execution.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolApproval {
    boolean required() default false;
    String prompt() default "";
}
