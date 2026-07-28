package cn.watsontech.snapagent.core.graph;

/**
 * Type-safe key for {@link GraphState}. Eliminates string-typed key typos
 * by providing compile-time checked constants with type parameters.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * public static final StateKey<String> SYSTEM_PROMPT = StateKey.of("system.prompt", String.class);
 *
 * // Compile-time checked — no unchecked cast needed
 * String prompt = state.get(SYSTEM_PROMPT);
 * state = state.with(SYSTEM_PROMPT, "new value");
 * }</pre>
 *
 * @param <T> the value type stored under this key
 */
public final class StateKey<T> {

    private final String name;
    private final Class<T> type;

    private StateKey(String name, Class<T> type) {
        this.name = name;
        this.type = type;
    }

    /**
     * Create a typed state key.
     *
     * @param name the string key name used in the state map
     * @param type the value type
     * @param <T>  the value type parameter
     * @return a new StateKey
     */
    public static <T> StateKey<T> of(String name, Class<T> type) {
        return new StateKey<>(name, type);
    }

    /**
     * @return the string name used in the state map
     */
    public String name() {
        return name;
    }

    /**
     * @return the value type
     */
    public Class<T> type() {
        return type;
    }

    @Override
    public String toString() {
        return "StateKey{" + name + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StateKey<?> other = (StateKey<?>) o;
        return name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }
}
