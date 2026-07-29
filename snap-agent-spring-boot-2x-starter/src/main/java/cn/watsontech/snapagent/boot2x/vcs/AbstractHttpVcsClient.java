package cn.watsontech.snapagent.boot2x.vcs;

import cn.watsontech.snapagent.core.issue.HttpExecutor;
import cn.watsontech.snapagent.core.issue.HttpResponse;
import cn.watsontech.snapagent.core.vcs.VcsClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP base class for VcsClient implementations.
 * Provides JSON HTTP request helper using {@link HttpExecutor} + Jackson.
 *
 * <p>The HTTP execution is delegated to {@link #httpExecutor}, which defaults
 * to {@code DirectHttpExecutor} (using {@link java.net.HttpURLConnection}).
 * When the browser bridge is enabled, a {@code BridgeHttpExecutor} is injected
 * via {@code BeanPostProcessor} to route requests through the user's browser.</p>
 */
public abstract class AbstractHttpVcsClient implements VcsClient {

    protected static final Logger log = LoggerFactory.getLogger(AbstractHttpVcsClient.class);
    protected final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * HTTP executor used for all outbound requests. Defaults to
     * {@code DirectHttpExecutor} (direct {@link java.net.HttpURLConnection}).
     * Replaced by {@code BridgeHttpExecutor} when the bridge is enabled.
     */
    public HttpExecutor httpExecutor = new cn.watsontech.snapagent.boot2x.issue.DirectHttpExecutor();

    protected JsonNode jsonRequest(String urlStr, String method,
                                   Map<String, String> headers, Object body) {
        try {
            HttpResponse resp = httpExecutor.execute(urlStr, method, headers, body);
            return resp.getJsonBody(objectMapper);
        } catch (RuntimeException e) {
            throw new RuntimeException(e.getMessage(), e.getCause());
        }
    }

    protected static Map<String, String> authHeader(String header, String value) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put(header, value);
        return h;
    }

    protected static String trimSlash(String s) {
        if (s == null) return "";
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
