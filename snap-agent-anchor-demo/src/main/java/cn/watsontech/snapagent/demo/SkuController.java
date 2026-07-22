package cn.watsontech.snapagent.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * Controller that queries the {@code drp_sku_detail} table and renders
 * a Thymeleaf page with anchor-annotated sections.
 *
 * <p>Each SKU category (一级分类) is rendered as a separate section
 * with {@code data-snap-anchor}, allowing the anchor Q&A feature
 * to provide context-aware answers about each product group.</p>
 */
@Controller
public class SkuController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping("/")
    public String skuList(@RequestParam(value = "q", required = false) String keyword, Model model) {
        // Query SKU details — show top 50 with data
        String sql = "SELECT id, sku_code, sku_name, brand_code, brand_name, " +
                "lv1_clssf_code, lv1_clssf_name, lv2_clssf_code, lv2_clssf_name, " +
                "lifetime, sale_status, effect_dt, invld_dt, box_spec, weight, price " +
                "FROM drp_sku_detail " +
                (keyword != null && !keyword.isEmpty()
                        ? "WHERE sku_code LIKE ? OR sku_name LIKE ? "
                        : "") +
                "ORDER BY id LIMIT 50";

        List<Map<String, Object>> skus;
        if (keyword != null && !keyword.isEmpty()) {
            String pattern = "%" + keyword + "%";
            skus = jdbcTemplate.queryForList(sql, pattern, pattern);
        } else {
            skus = jdbcTemplate.queryForList(sql);
        }

        // Group by lv1_clssf_name for sectioned display
        Map<String, List<Map<String, Object>>> grouped = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : skus) {
            Object raw = row.get("lv1_clssf_name");
            String key = (raw != null && !"null".equals(raw.toString()) && !raw.toString().isEmpty())
                    ? raw.toString() : "未分类";
            grouped.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(row);
        }

        // Summary stats
        int totalCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM drp_sku_detail", Integer.class);
        Integer brandCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT brand_code) FROM drp_sku_detail WHERE brand_code IS NOT NULL", Integer.class);

        model.addAttribute("skus", skus);
        model.addAttribute("grouped", grouped);
        model.addAttribute("keyword", keyword);
        model.addAttribute("totalCount", totalCount);
        model.addAttribute("brandCount", brandCount != null ? brandCount : 0);
        model.addAttribute("displayedCount", skus.size());

        return "sku-list";
    }

    @GetMapping("/sku/detail")
    public String skuDetail(@RequestParam("code") String skuCode, Model model) {
        String sql = "SELECT id, sku_code, sku_name, brand_code, brand_name, " +
                "lv1_clssf_code, lv1_clssf_name, lv2_clssf_code, lv2_clssf_name, " +
                "lifetime, sale_status, effect_dt, invld_dt, box_spec, weight, box_vol, price, " +
                "forecast_flag, replenishment_flag, transfer_flag, " +
                "forecast_start_time, forecast_end_time, sku_group_code " +
                "FROM drp_sku_detail WHERE sku_code = ? LIMIT 1";

        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, skuCode);
        if (!results.isEmpty()) {
            model.addAttribute("sku", results.get(0));
        } else {
            model.addAttribute("error", "SKU not found: " + skuCode);
        }

        return "sku-detail";
    }

    @GetMapping("/inject-demo")
    public String injectDemo() {
        return "inject-demo";
    }
}
