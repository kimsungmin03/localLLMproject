package com.lecturenote.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app")
public record AppProperties(
        @Valid @NotNull Storage storage,
        @Valid @NotNull Cors cors,
        @Valid @NotNull Stt stt,
        @Valid @NotNull Queue queue,
        @Valid @NotNull Sse sse) {

    public record Storage(@NotBlank String audioDir) {}

    public record Cors(List<String> allowedOrigins) {}

    public record Stt(
            @NotBlank String workerUrl,
            @NotBlank String callbackUrl,
            @NotBlank(message = "STT_SHARED_SECRET must be set") String sharedSecret,
            @NotBlank String language,
            @NotNull Duration inactivityTimeout,
            @NotNull Duration maxDuration) {}

    public record Queue(@Min(1) int capacity) {}

    public record Sse(@NotNull Duration timeout) {}
}
