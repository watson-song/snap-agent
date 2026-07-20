package cn.watsontech.snapagent.boot2x.autoconfig;

/**
 * Placeholder bean returned by autoconfig {@code @Bean} methods when an optional
 * dependency is missing (e.g. {@code EmailAlertPushChannel} when JavaMailSender
 * is not on the classpath).
 *
 * <p>Returning a non-null marker (rather than null) keeps Spring happy; the
 * marker is not an instance of the SPI interface, so consumers using
 * {@code ObjectProvider<MyInterface>} or {@code @Autowired List<MyInterface>}
 * simply skip it.</p>
 */
public class NoopMarkerBean {

    private final String reason;

    public NoopMarkerBean(String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "NoopMarkerBean{" + reason + "}";
    }
}
