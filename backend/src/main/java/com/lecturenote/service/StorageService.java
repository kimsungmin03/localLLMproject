package com.lecturenote.service;

import com.lecturenote.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final AppProperties appProperties;
    private Path storageDirectory;

    @PostConstruct
    public void init() {
        this.storageDirectory = Paths.get(appProperties.getStorage().getAudioDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storageDirectory);
            log.info("Storage directory initialized at: {}", this.storageDirectory);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize storage directory", e);
        }
    }

    public String storeAudioFile(MultipartFile file, Long lectureId) {
        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "audio.mp3");
        String extension = "";
        int dotIdx = originalFilename.lastIndexOf('.');
        if (dotIdx > 0) {
            extension = originalFilename.substring(dotIdx);
        }

        String storedFileName = "lecture_" + lectureId + "_" + UUID.randomUUID().toString().substring(0, 8) + extension;
        Path targetPath = this.storageDirectory.resolve(storedFileName);

        try {
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Stored audio file: {}", targetPath);
            return targetPath.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to store audio file " + originalFilename, e);
        }
    }

    public Resource loadAsResource(String audioPath) {
        try {
            Path filePath = Paths.get(audioPath);
            Resource resource = new UrlResource(filePath.toUri());
            if (resource.exists() && resource.isReadable()) {
                return resource;
            } else {
                throw new RuntimeException("Audio file not found or not readable: " + audioPath);
            }
        } catch (MalformedURLException e) {
            throw new RuntimeException("Malformed path for audio: " + audioPath, e);
        }
    }

    public void deleteAudioFile(String audioPath) {
        if (audioPath == null || audioPath.isBlank()) {
            return;
        }
        try {
            Path filePath = Paths.get(audioPath);
            Files.deleteIfExists(filePath);
            log.info("Deleted audio file: {}", filePath);
        } catch (IOException e) {
            log.warn("Failed to delete audio file {}: {}", audioPath, e.getMessage());
        }
    }
}
