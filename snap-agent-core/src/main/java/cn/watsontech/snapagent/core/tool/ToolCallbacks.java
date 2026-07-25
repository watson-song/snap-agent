package cn.watsontech.snapagent.core.tool;

import cn.watsontech.snapagent.core.graph.hitl.ToolApproval;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reflection factory that scans a target object for @Tool methods
 * and produces ToolCallback[] with auto-generated JSON Schema.
 */
public class ToolCallbacks {

    public static ToolCallback[] from(Object target) {
        if (target == null) {
            return new ToolCallback[0];
        }
        return from(target.getClass(), target);
    }

    public static ToolCallback[] from(Class<?> clazz) {
        return from(clazz, null);
    }

    private static ToolCallback[] from(Class<?> clazz, Object instance) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            Tool toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation == null) {
                continue;
            }
            callbacks.add(buildCallback(method, toolAnnotation, instance));
        }
        return callbacks.toArray(new ToolCallback[0]);
    }

    private static ToolCallback buildCallback(Method method, Tool toolAnno, Object instance) {
        // Validate parameters have @ToolParam
        Parameter[] params = method.getParameters();
        String[] paramNames = new String[params.length];
        ToolParam[] paramAnnotations = new ToolParam[params.length];

        for (int i = 0; i < params.length; i++) {
            ToolParam tp = params[i].getAnnotation(ToolParam.class);
            if (tp == null) {
                throw new IllegalStateException(
                    "parameter missing @ToolParam: parameter '" + params[i].getName()
                    + "' in method '" + method.getName() + "'");
            }
            paramAnnotations[i] = tp;
            paramNames[i] = params[i].getName();
        }

        // Determine tool name
        String name = toolAnno.name();
        if (name == null || name.isEmpty()) {
            name = method.getName();
        }

        // Check @ToolApproval
        ToolApproval approval = method.getAnnotation(ToolApproval.class);
        boolean approvalRequired = approval != null && approval.required();

        // Build JSON Schema
        String jsonSchema = buildJsonSchema(paramNames, paramAnnotations, params);
        String description = toolAnno.description();
        boolean returnDirect = toolAnno.returnDirect();

        final String toolName = name;
        final Method toolMethod = method;
        final Object toolInstance = instance;
        final boolean isApprovalRequired = approvalRequired;

        return new ToolCallback() {
            @Override
            public String getName() { return toolName; }
            @Override
            public String getDescription() { return description; }
            @Override
            public String getJsonSchema() { return jsonSchema; }
            @Override
            public boolean isReturnDirect() { return returnDirect; }
            @Override
            public boolean isSystem() { return false; }
            @Override
            public boolean isApprovalRequired() { return isApprovalRequired; }

            @Override
            public ToolResult execute(Map<String, Object> args, Object context) {
                try {
                    toolMethod.setAccessible(true);
                    Object[] invokeArgs = new Object[params.length];
                    for (int i = 0; i < params.length; i++) {
                        Object val = args != null ? args.get(paramNames[i]) : null;
                        invokeArgs[i] = convertType(val, params[i].getType());
                    }
                    Object result = toolMethod.invoke(toolInstance, invokeArgs);
                    String content = result == null ? "void" : result.toString();
                    return ToolResult.success(content, 0, 0, null);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    return ToolResult.error("tool execution failed: " + cause.getMessage(), 0);
                }
            }
        };
    }

    private static String buildJsonSchema(String[] paramNames, ToolParam[] paramAnnotations, Parameter[] params) {
        StringBuilder sb = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        List<String> required = new ArrayList<>();
        for (int i = 0; i < paramNames.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(paramNames[i]).append("\":{");
            sb.append("\"type\":\"").append(schemaType(params[i].getType())).append("\"");
            sb.append(",\"description\":\"").append(escapeJson(paramAnnotations[i].description())).append("\"");
            sb.append("}");
            if (paramAnnotations[i].required()) {
                required.add(paramNames[i]);
            }
        }
        sb.append("},\"required\":[");
        for (int i = 0; i < required.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(required.get(i)).append("\"");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String schemaType(Class<?> type) {
        if (type == String.class) return "string";
        if (type == int.class || type == Integer.class) return "integer";
        if (type == long.class || type == Long.class) return "integer";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (type == float.class || type == Float.class) return "number";
        if (type == double.class || type == Double.class) return "number";
        if (List.class.isAssignableFrom(type)) return "array";
        return "object";
    }

    private static Object convertType(Object value, Class<?> targetType) {
        if (value == null) {
            return defaultValue(targetType);
        }
        if (targetType == String.class) return value.toString();
        if (targetType == int.class || targetType == Integer.class) return Integer.valueOf(value.toString());
        if (targetType == long.class || targetType == Long.class) return Long.valueOf(value.toString());
        if (targetType == boolean.class || targetType == Boolean.class) return Boolean.valueOf(value.toString());
        if (targetType == float.class || targetType == Float.class) return Float.valueOf(value.toString());
        if (targetType == double.class || targetType == Double.class) return Double.valueOf(value.toString());
        return value;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        return null;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
