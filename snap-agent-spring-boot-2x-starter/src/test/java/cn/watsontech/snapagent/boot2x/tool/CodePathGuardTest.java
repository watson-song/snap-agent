package cn.watsontech.snapagent.boot2x.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CodePathGuard}.
 */
class CodePathGuardTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private CodePathGuard guard;

    @BeforeEach
    void setUp() throws IOException {
        projectRoot = tempDir.resolve("myproject");
        Files.createDirectories(projectRoot.resolve("src/main/java/com/example"));

        Path javaFile = projectRoot.resolve("src/main/java/com/example/Order.java");
        Files.write(javaFile, Collections.singletonList("public class Order {}"));

        Path xmlFile = projectRoot.resolve("pom.xml");
        Files.write(xmlFile, Collections.singletonList("<project/>"));

        // File outside project root
        Path outsideFile = tempDir.resolve("secret.env");
        Files.write(outsideFile, Collections.singletonList("SECRET=key"));

        guard = new CodePathGuard(projectRoot.toString(),
                Arrays.asList(".java", ".xml", ".yml", ".properties"),
                500, 512L * 1024);
    }

    @Test
    void shouldAllowAbsolutePathUnderProjectRoot() {
        Path file = projectRoot.resolve("src/main/java/com/example/Order.java");
        CodePathGuard.Result result = guard.validate(file.toString());

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getPath()).isEqualTo(file);
    }

    @Test
    void shouldAllowRelativePathResolvedAgainstProjectRoot() {
        CodePathGuard.Result result = guard.validate("src/main/java/com/example/Order.java");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getPath()).isAbsolute();
        assertThat(result.getPath().toString()).contains("Order.java");
    }

    @Test
    void shouldRejectNullPath() {
        CodePathGuard.Result result = guard.validate(null);

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("空");
    }

    @Test
    void shouldRejectEmptyPath() {
        CodePathGuard.Result result = guard.validate("   ");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("空");
    }

    @Test
    void shouldRejectDirectoryTraversal() {
        CodePathGuard.Result result = guard.validate(
                "src/main/java/com/example/../../../../../secret.env");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("..");
    }

    @Test
    void shouldRejectPathOutsideProjectRoot() {
        Path outsideFile = tempDir.resolve("secret.env");
        CodePathGuard.Result result = guard.validate(outsideFile.toString());

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("不在项目根目录");
    }

    @Test
    void shouldRejectNonExistentFile() {
        CodePathGuard.Result result = guard.validate("src/main/java/com/example/NonExistent.java");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("不存在");
    }

    @Test
    void shouldRejectDirectoryInsteadOfFile() {
        CodePathGuard.Result result = guard.validate("src");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("普通文件");
    }

    @Test
    void shouldRejectDisallowedExtension() throws IOException {
        // Create a .env file inside project root
        Path envFile = projectRoot.resolve(".env");
        Files.write(envFile, Collections.singletonList("SECRET=key"));

        CodePathGuard.Result result = guard.validate(envFile.toString());

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("白名单");
    }

    @Test
    void shouldRejectFileWithNoExtension() throws IOException {
        Path noExt = projectRoot.resolve("README");
        Files.write(noExt, Collections.singletonList("readme"));

        CodePathGuard.Result result = guard.validate(noExt.toString());

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("扩展名");
    }

    @Test
    void shouldRejectOversizedFile() throws IOException {
        Path bigFile = projectRoot.resolve("big.java");
        byte[] data = new byte[600]; // > 512 byte limit
        Arrays.fill(data, (byte) 'x');
        Files.write(bigFile, data);

        CodePathGuard smallGuard = new CodePathGuard(projectRoot.toString(),
                Arrays.asList(".java"), 500, 512);

        CodePathGuard.Result result = smallGuard.validate(bigFile.toString());

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("过大");
    }

    @Test
    void shouldRejectEmptyProjectRootInConstructor() {
        try {
            new CodePathGuard("", Arrays.asList(".java"), 500, 1024);
            org.assertj.core.api.Assertions.fail("Should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("projectRoot");
        }
    }

    @Test
    void shouldNormalizeExtensionsToLowerCaseWithDot() {
        CodePathGuard g = new CodePathGuard(projectRoot.toString(),
                Arrays.asList("JAVA", ".XML", "yml"), 500, 1024);

        assertThat(g.getAllowedExtensions()).contains(".java", ".xml", ".yml");
    }

    @Test
    void shouldResolveWithinProjectForValidRelativePath() {
        Path resolved = guard.resolveWithinProject("src/main/java");

        assertThat(resolved).isNotNull();
        assertThat(resolved.startsWith(projectRoot)).isTrue();
    }

    @Test
    void shouldReturnProjectRootWhenPathEmpty() {
        Path resolved = guard.resolveWithinProject("");

        assertThat(resolved).isEqualTo(projectRoot);
    }

    @Test
    void shouldReturnNullWhenResolvePathContainsTraversal() {
        Path resolved = guard.resolveWithinProject("../other");

        assertThat(resolved).isNull();
    }

    @Test
    void shouldReturnNullWhenResolvePathOutsideRoot() {
        Path resolved = guard.resolveWithinProject("/etc/passwd");

        assertThat(resolved).isNull();
    }

    @Test
    void shouldExposeProjectRoot() {
        assertThat(guard.getProjectRoot()).isEqualTo(projectRoot.toAbsolutePath().normalize());
    }
}
