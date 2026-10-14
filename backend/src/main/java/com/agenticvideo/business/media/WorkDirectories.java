package com.agenticvideo.business.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Owns temporary directories in the backend's private work volume. */
@Component
class WorkDirectories {
    private final Path root;

    WorkDirectories(@org.springframework.beans.factory.annotation.Value("${media.work-directory:${java.io.tmpdir}/agentic-video-media}") String directory) { root = Path.of(directory); }

    @jakarta.annotation.PostConstruct
    void reclaimAfterRestart() throws IOException {
        Files.createDirectories(root);
        // No listener has started yet. Processes from the previous container have exited.
        try (var entries = Files.list(root)) {
            for (Path path : entries.toList()) {
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        && path.getFileName().toString().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[0-9]+")) {
                    delete(path);
                }
            }
        }
    }

    Path create(UUID attempt) throws IOException {
        return Files.createTempDirectory(root, attempt + "-");
    }

    void delete(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
