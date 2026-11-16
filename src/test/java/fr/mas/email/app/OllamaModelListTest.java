package fr.mas.email.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class OllamaModelListTest {
    @Test void readsCompleteModelTags() throws IOException {
        assertEquals(List.of("qwen3.5:4b", "org/model:latest"), OllamaModelList.names("""
                NAME                 ID            SIZE      MODIFIED
                qwen3.5:4b           0123          3.0 GB    2 days ago
                org/model:latest     4567          2.0 GB    1 day ago
                """));
        assertEquals(List.of(), OllamaModelList.names("NAME ID SIZE MODIFIED\n"));
        assertThrows(IOException.class, () -> OllamaModelList.names("Error: connection refused"));
    }
}
