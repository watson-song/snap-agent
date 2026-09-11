package cn.watsontech.snapagent.core.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for {@link Experiment} instances.
 *
 * <p>Backed by a {@link ConcurrentHashMap}. Thread-safe for concurrent reads
 * and writes. Not durable across restarts.</p>
 *
 * <p>Experiments are kept in memory with a maximum capacity. When the limit
 * is reached, completed/failed experiments are evicted oldest-first before
 * running ones.</p>
 */
public class ExperimentStore {

    private static final int DEFAULT_MAX_SIZE = 200;

    private final ConcurrentHashMap<String, Experiment> experiments =
            new ConcurrentHashMap<String, Experiment>();
    private final int maxSize;

    public ExperimentStore() {
        this(DEFAULT_MAX_SIZE);
    }

    public ExperimentStore(int maxSize) {
        this.maxSize = maxSize > 0 ? maxSize : DEFAULT_MAX_SIZE;
    }

    /**
     * Save or update an experiment.
     */
    public void save(Experiment experiment) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }
        experiments.put(experiment.getId(), experiment);
        evictIfNeeded();
    }

    /**
     * Get an experiment by ID.
     *
     * @return the experiment, or {@code null} if not found
     */
    public Experiment get(String experimentId) {
        if (experimentId == null) {
            return null;
        }
        return experiments.get(experimentId);
    }

    /**
     * Remove an experiment by ID.
     */
    public void remove(String experimentId) {
        if (experimentId != null) {
            experiments.remove(experimentId);
        }
    }

    /**
     * List all experiments sorted by createdAt descending (newest first).
     */
    public List<Experiment> list() {
        List<Experiment> result = new ArrayList<Experiment>(experiments.values());
        Collections.sort(result, new Comparator<Experiment>() {
            @Override
            public int compare(Experiment a, Experiment b) {
                return Long.compare(b.getCreatedAt(), a.getCreatedAt());
            }
        });
        return Collections.unmodifiableList(result);
    }

    /**
     * List experiments filtered by status.
     */
    public List<Experiment> listByStatus(Experiment.Status status) {
        List<Experiment> result = new ArrayList<Experiment>();
        for (Experiment exp : experiments.values()) {
            if (exp.getStatus() == status) {
                result.add(exp);
            }
        }
        Collections.sort(result, new Comparator<Experiment>() {
            @Override
            public int compare(Experiment a, Experiment b) {
                return Long.compare(b.getCreatedAt(), a.getCreatedAt());
            }
        });
        return Collections.unmodifiableList(result);
    }

    /**
     * Return the number of experiments in the store.
     */
    public int size() {
        return experiments.size();
    }

    /**
     * Remove all experiments.
     */
    public void clear() {
        experiments.clear();
    }

    private void evictIfNeeded() {
        if (experiments.size() <= maxSize) {
            return;
        }
        // Remove completed/failed first, oldest first
        List<Experiment> terminal = new ArrayList<Experiment>();
        for (Experiment exp : experiments.values()) {
            if (exp.getStatus() == Experiment.Status.COMPLETED
                    || exp.getStatus() == Experiment.Status.FAILED) {
                terminal.add(exp);
            }
        }
        Collections.sort(terminal, new Comparator<Experiment>() {
            @Override
            public int compare(Experiment a, Experiment b) {
                return Long.compare(a.getCreatedAt(), b.getCreatedAt());
            }
        });
        for (Experiment exp : terminal) {
            if (experiments.size() <= maxSize) break;
            experiments.remove(exp.getId());
        }

        // If still over limit, remove any experiment oldest first
        if (experiments.size() > maxSize) {
            List<Experiment> all = new ArrayList<Experiment>(experiments.values());
            Collections.sort(all, new Comparator<Experiment>() {
                @Override
                public int compare(Experiment a, Experiment b) {
                    return Long.compare(a.getCreatedAt(), b.getCreatedAt());
                }
            });
            for (Experiment exp : all) {
                if (experiments.size() <= maxSize) break;
                experiments.remove(exp.getId());
            }
        }
    }
}
