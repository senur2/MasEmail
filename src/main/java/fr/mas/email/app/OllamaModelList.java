package fr.mas.email.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

final class OllamaModelList {
    private OllamaModelList() {}

    static List<String> load(String server) throws Exception {
        var builder = new ProcessBuilder("ollama", "list").redirectErrorStream(true);
        builder.environment().put("OLLAMA_HOST", server);
        var process = builder.start();
        var read = new FutureTask<>(() -> {
            try (var output = process.getInputStream()) {
                return new String(output.readAllBytes(), StandardCharsets.UTF_8);
            }
        });
        Thread.startVirtualThread(read);
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("Impossible de lister les modèles Ollama");
            }
            return names(read.get(1, TimeUnit.SECONDS));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            read.cancel(true);
        }
    }

    static List<String> names(String output) throws IOException {
        var rows = output.lines().map(String::strip).filter(row -> !row.isEmpty()).toList();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).matches("NAME\\s+ID\\s+SIZE\\s+MODIFIED")) {
                return rows.subList(i + 1, rows.size()).stream()
                        .map(row -> row.split("\\s+", 2)[0]).distinct().toList();
            }
        }
        throw new IOException("Format inattendu pour ollama list");
    }
}
