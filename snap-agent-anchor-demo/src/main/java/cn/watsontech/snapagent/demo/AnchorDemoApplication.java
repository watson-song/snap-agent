package cn.watsontech.snapagent.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Demo host application that uses SnapAgent's anchor Q&A feature.
 *
 * <p>Displays a list of SKU data from the {@code drp_sku_detail} table
 * and attaches anchor icons to page sections for in-context LLM Q&A.</p>
 */
@SpringBootApplication
public class AnchorDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnchorDemoApplication.class, args);
    }
}
