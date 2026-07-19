package cn.watsontech.snapagent.boot2x.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DataSourceRegistry}.
 *
 * <p>Covers env resolution, default-env fallback, unknown env rejection,
 * empty-registry rejection, and envNames listing.</p>
 */
class DataSourceRegistryTest {

    private DataSource sitDs;
    private DataSource uatDs;

    @BeforeEach
    void setUp() {
        org.h2.Driver driver = new org.h2.Driver();
        sitDs = new SimpleDriverDataSource(driver, "jdbc:h2:mem:sit;DB_CLOSE_DELAY=-1", "sa", "");
        uatDs = new SimpleDriverDataSource(driver, "jdbc:h2:mem:uat;DB_CLOSE_DELAY=-1", "sa", "");
    }

    @Test
    void shouldResolveByEnvName() {
        Map<String, DataSource> map = new LinkedHashMap<String, DataSource>();
        map.put("sit", sitDs);
        map.put("uat", uatDs);
        DataSourceRegistry registry = new DataSourceRegistry(map, "sit");

        assertThat(registry.resolve("uat")).isSameAs(uatDs);
        assertThat(registry.resolve("sit")).isSameAs(sitDs);
    }

    @Test
    void shouldReturnDefaultEnvWhenEnvIsBlank() {
        Map<String, DataSource> map = new LinkedHashMap<String, DataSource>();
        map.put("sit", sitDs);
        map.put("uat", uatDs);
        DataSourceRegistry registry = new DataSourceRegistry(map, "uat");

        assertThat(registry.resolve(null)).isSameAs(uatDs);
        assertThat(registry.resolve("")).isSameAs(uatDs);
        assertThat(registry.resolve("   ")).isSameAs(uatDs);
    }

    @Test
    void shouldReturnFirstEntryWhenDefaultEnvIsBlank() {
        Map<String, DataSource> map = new LinkedHashMap<String, DataSource>();
        map.put("sit", sitDs);
        map.put("uat", uatDs);
        DataSourceRegistry registry = new DataSourceRegistry(map, "");

        assertThat(registry.resolve(null)).isSameAs(sitDs);
        assertThat(registry.resolve("")).isSameAs(sitDs);
    }

    @Test
    void shouldThrowWhenEnvNotFound() {
        Map<String, DataSource> map = new LinkedHashMap<String, DataSource>();
        map.put("sit", sitDs);
        DataSourceRegistry registry = new DataSourceRegistry(map, "");

        assertThatThrownBy(() -> registry.resolve("prod"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prod");
    }

    @Test
    void shouldThrowWhenRegistryIsEmpty() {
        assertThatThrownBy(() -> new DataSourceRegistry(null, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DataSourceRegistry(new LinkedHashMap<String, DataSource>(), ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReturnSizeAndEnvNames() {
        Map<String, DataSource> map = new LinkedHashMap<String, DataSource>();
        map.put("sit", sitDs);
        map.put("uat", uatDs);
        DataSourceRegistry registry = new DataSourceRegistry(map, "sit");

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.envNames()).containsExactly("sit", "uat");
    }

    @Test
    void shouldHandleNullDefaultEnv() {
        Map<String, DataSource> map = new LinkedHashMap<String, DataSource>();
        map.put("sit", sitDs);
        DataSourceRegistry registry = new DataSourceRegistry(map, null);

        assertThat(registry.resolve("")).isSameAs(sitDs);
    }

    @Test
    void shouldBeImmutable() {
        Map<String, DataSource> map = new LinkedHashMap<String, DataSource>();
        map.put("sit", sitDs);
        DataSourceRegistry registry = new DataSourceRegistry(map, "sit");

        assertThatThrownBy(() -> registry.envNames().add("prod"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
