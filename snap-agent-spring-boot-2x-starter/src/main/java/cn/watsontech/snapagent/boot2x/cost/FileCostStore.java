package cn.watsontech.snapagent.boot2x.cost;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.watsontech.snapagent.core.cost.CostRecord;
import cn.watsontech.snapagent.core.cost.CostStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link CostStore} that persists cost records as JSON files
 * partitioned by date under a storage directory.
 *
 * <p>File layout: {@code {storageDir}/{yyyy-MM-dd}/{recordId}.json}</p>
 *
 * <p>Date-partitioned directories make it efficient to list records by time
 * range and to clean up old records by deleting entire day directories.</p>
 */
public class FileCostStore implements CostStore {

    private static final Logger log = LoggerFactory.getLogger(FileCostStore.class);
    private static final SimpleDateFormat DAY_FMT = new SimpleDateFormat("yyyy-MM-dd");

    private final Path storageDir;
    private final ObjectMapper mapper;

    public FileCostStore(String storageDirPath) {
        this(storageDirPath, new ObjectMapper());
    }

    public FileCostStore(String storageDirPath, ObjectMapper mapper) {
        String path = storageDirPath;
        if (path != null && path.startsWith("file:")) {
            path = path.substring(5);
        }
        this.storageDir = path != null ? Paths.get(path) : null;
        this.mapper = mapper;
        if (this.storageDir != null) {
            try {
                if (!Files.isDirectory(this.storageDir)) {
                    Files.createDirectories(this.storageDir);
                    log.info("Created cost storage directory: {}", this.storageDir);
                }
            } catch (IOException e) {
                log.warn("Failed to create cost storage directory {}: {}",
                        this.storageDir, e.getMessage());
            }
        }
    }

    @Override
    public void save(CostRecord record) {
        if (storageDir == null) {
            log.warn("Cost storage directory not configured; cannot save");
            return;
        }
        if (record == null || record.getId() == null) {
            log.warn("Cannot save cost record with null id");
            return;
        }

        String dayDir = formatDay(record.getTimestamp());
        Path dir = storageDir.resolve(dayDir);
        Path file = dir.resolve(record.getId() + ".json");
        try {
            Map<String, Object> data = toMap(record);
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            Files.createDirectories(dir);
            Files.write(file, json.getBytes(StandardCharsets.UTF_8));
            log.debug("Saved cost record {} to {}", record.getId(), file);
        } catch (IOException e) {
            log.error("Failed to save cost record {}: {}", record.getId(), e.getMessage());
        }
    }

    @Override
    public List<CostRecord> list(long fromTimestamp, long toTimestamp) {
        return loadRecords(null, null, fromTimestamp, toTimestamp);
    }

    @Override
    public List<CostRecord> listByUser(String userId, long from, long to) {
        return loadRecords(userId, null, from, to);
    }

    @Override
    public List<CostRecord> listBySkill(String skillName, long from, long to) {
        return loadRecords(null, skillName, from, to);
    }

    @Override
    public BigDecimal sumCostByUser(String userId, long from, long to) {
        BigDecimal sum = BigDecimal.ZERO;
        for (CostRecord record : loadRecords(userId, null, from, to)) {
            if (record.getCost() != null) {
                sum = sum.add(record.getCost());
            }
        }
        return sum;
    }

    @Override
    public BigDecimal sumCostBySkill(String skillName, long from, long to) {
        BigDecimal sum = BigDecimal.ZERO;
        for (CostRecord record : loadRecords(null, skillName, from, to)) {
            if (record.getCost() != null) {
                sum = sum.add(record.getCost());
            }
        }
        return sum;
    }

    @Override
    public BigDecimal sumCost(long from, long to) {
        BigDecimal sum = BigDecimal.ZERO;
        for (CostRecord record : loadRecords(null, null, from, to)) {
            if (record.getCost() != null) {
                sum = sum.add(record.getCost());
            }
        }
        return sum;
    }

    @Override
    public int countByUser(String userId, long from, long to) {
        return loadRecords(userId, null, from, to).size();
    }

    @Override
    public int countBySkill(String skillName, long from, long to) {
        return loadRecords(null, skillName, from, to).size();
    }

    @Override
    public void deleteBefore(long timestamp) {
        if (storageDir == null || !Files.isDirectory(storageDir)) {
            return;
        }
        try {
            // Walk day directories; delete individual files whose timestamp < threshold,
            // then remove empty day directories.
            for (Path dayDir : Files.list(storageDir).toArray(Path[]::new)) {
                if (!Files.isDirectory(dayDir)) continue;
                for (Path file : Files.list(dayDir).toArray(Path[]::new)) {
                    if (!file.toString().endsWith(".json")) continue;
                    try {
                        CostRecord record = loadRecord(file);
                        if (record != null && record.getTimestamp() < timestamp) {
                            Files.deleteIfExists(file);
                            log.debug("Deleted old cost record {}", file);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to process cost file {}: {}", file, e.getMessage());
                    }
                }
                // Remove empty day directories
                if (Files.list(dayDir).count() == 0) {
                    Files.deleteIfExists(dayDir);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to delete old cost records: {}", e.getMessage());
        }
    }

    // ---- helpers ----

    /**
     * Loads records matching the given filters. If userId is non-null, filters
     * by userId. If skillName is non-null, filters by skillName. Always filters
     * by timestamp range [from, to] (inclusive).
     */
    private List<CostRecord> loadRecords(String userId, String skillName, long from, long to) {
        if (storageDir == null || !Files.isDirectory(storageDir)) {
            return Collections.<CostRecord>emptyList();
        }
        List<CostRecord> records = new ArrayList<CostRecord>();
        try {
            for (Path dayDir : Files.list(storageDir).toArray(Path[]::new)) {
                if (!Files.isDirectory(dayDir)) continue;
                for (Path file : Files.list(dayDir).toArray(Path[]::new)) {
                    if (!file.toString().endsWith(".json")) continue;
                    try {
                        CostRecord record = loadRecord(file);
                        if (record == null) continue;
                        if (record.getTimestamp() < from || record.getTimestamp() > to) continue;
                        if (userId != null && !userId.equals(record.getUserId())) continue;
                        if (skillName != null && !skillName.equals(record.getSkillName())) continue;
                        records.add(record);
                    } catch (Exception e) {
                        log.warn("Failed to read cost file {}: {}", file, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan cost storage: {}", e.getMessage());
            return Collections.<CostRecord>emptyList();
        }
        return records;
    }

    private CostRecord loadRecord(Path file) {
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Map<String, Object> data = mapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            return fromMap(data);
        } catch (Exception e) {
            log.warn("Failed to parse cost record {}: {}", file, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> toMap(CostRecord record) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", record.getId());
        map.put("userId", record.getUserId());
        map.put("skillName", record.getSkillName());
        map.put("taskId", record.getTaskId());
        map.put("model", record.getModel());
        map.put("inputTokens", record.getInputTokens());
        map.put("outputTokens", record.getOutputTokens());
        map.put("cacheReadTokens", record.getCacheReadTokens());
        map.put("cost", record.getCost() != null ? record.getCost().toString() : null);
        map.put("timestamp", record.getTimestamp());
        return map;
    }

    private CostRecord fromMap(Map<String, Object> data) {
        BigDecimal cost = null;
        Object costObj = data.get("cost");
        if (costObj != null) {
            try {
                cost = new BigDecimal(costObj.toString());
            } catch (NumberFormatException e) {
                log.warn("Invalid cost value: {}", costObj);
            }
        }
        return new CostRecord(
                str(data.get("id")),
                str(data.get("userId")),
                str(data.get("skillName")),
                nullableStr(data.get("taskId")),
                str(data.get("model")),
                longVal(data.get("inputTokens")),
                longVal(data.get("outputTokens")),
                longVal(data.get("cacheReadTokens")),
                cost,
                longVal(data.get("timestamp"))
        );
    }

    private String formatDay(long timestamp) {
        synchronized (DAY_FMT) {
            return DAY_FMT.format(new Date(timestamp));
        }
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    private static String nullableStr(Object obj) {
        return obj != null ? obj.toString() : null;
    }

    private static long longVal(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try {
            return Long.parseLong(obj.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
