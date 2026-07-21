package cn.watsontech.snapagent.demo;

import cn.watsontech.snapagent.core.security.SecurityGateway;
import org.springframework.context.annotation.Configuration;

/**
 * Demo security gateway that always returns a fixed demo user.
 *
 * <p>In a real application this would bridge to Spring Security or Shiro.
 * For the demo, we simply authenticate everyone as "demo-user" so that
 * the anchor Q&amp;A feature (which requires an authenticated user) works
 * out of the box without setting up a security framework.</p>
 */
@Configuration
public class DemoSecurityGateway implements SecurityGateway {

    @Override
    public String currentUserId() {
        return "demo-user";
    }

    @Override
    public boolean hasPermission(String code) {
        return true;
    }
}
