package cn.watsontech.snapagent.core.vcs;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class FileChangeTest {

    @Test
    void create_setsActionToCreate() {
        FileChange fc = FileChange.create("src/Main.java", "content");
        assertThat(fc.getFilePath()).isEqualTo("src/Main.java");
        assertThat(fc.getContent()).isEqualTo("content");
        assertThat(fc.getAction()).isEqualTo("CREATE");
    }

    @Test
    void update_setsActionToUpdate() {
        FileChange fc = FileChange.update("src/Main.java", "new content");
        assertThat(fc.getAction()).isEqualTo("UPDATE");
        assertThat(fc.getContent()).isEqualTo("new content");
    }

    @Test
    void delete_setsActionToDeleteAndNullContent() {
        FileChange fc = FileChange.delete("src/Main.java");
        assertThat(fc.getAction()).isEqualTo("DELETE");
        assertThat(fc.getContent()).isNull();
    }

    @Test
    void toString_containsFilePathAndAction() {
        FileChange fc = FileChange.create("src/Main.java", "content");
        assertThat(fc.toString()).contains("src/Main.java").contains("CREATE");
    }
}
