package com.lecturenote.storage;

import com.lecturenote.config.AppProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 오디오 파일 저장소. DB와 워커에는 루트 기준 파일명만 오가고, 실제 경로는 각 서비스가 자기 설정의 루트로 해석한다.
 */
@Component
public class AudioStorage {

    private static final Logger log = LoggerFactory.getLogger(AudioStorage.class);
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("mp3", "wav", "m4a", "flac", "ogg", "webm", "aac", "mp4");

    private final Path root;

    public AudioStorage(AppProperties props) throws IOException {
        this.root = Path.of(props.storage().audioDir()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        log.info("Audio storage root: {}", root);
    }

    public String store(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file");
        }
        String ext = StringUtils.getFilenameExtension(file.getOriginalFilename());
        ext = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported audio type. Allowed: " + ALLOWED_EXTENSIONS);
        }
        String name = UUID.randomUUID() + "." + ext;
        try {
            file.transferTo(root.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store audio file", e);
        }
        return name;
    }

    public Path resolve(String name) {
        Path path = root.resolve(name).normalize();
        if (!root.equals(path.getParent())) {
            throw new IllegalArgumentException("Invalid audio file name: " + name);
        }
        return path;
    }

    public void delete(String name) {
        try {
            Files.deleteIfExists(resolve(name));
        } catch (IOException e) {
            log.warn("Failed to delete audio file {}", name, e);
        }
    }
}
