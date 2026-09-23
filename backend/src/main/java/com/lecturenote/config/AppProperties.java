package com.lecturenote.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private Storage storage = new Storage();
    private Stt stt = new Stt();
    private Ollama ollama = new Ollama();

    @Getter
    @Setter
    public static class Storage {
        private String audioDir = "./storage/audio";
    }

    @Getter
    @Setter
    public static class Stt {
        private String workerUrl = "http://localhost:8000";
        private String callbackUrl = "http://localhost:8080/internal/stt/callback";
        private String sharedSecret = "lecturenote-secret-token-change-in-prod";
    }

    @Getter
    @Setter
    public static class Ollama {
        private String baseUrl = "http://localhost:11434";
        private String model = "exaone3.5:2.4b";
        private int numCtx = 8192;
    }
}
