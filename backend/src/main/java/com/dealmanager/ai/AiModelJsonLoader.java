package com.dealmanager.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/** Loads the complete model pool from one external JSON file, never packaged with the app. */
@Component
@Slf4j
public class AiModelJsonLoader {
    public AiModelJsonLoader(AiModelProperties properties, ObjectMapper mapper, Environment environment) {
        this.properties = properties;
        this.mapper = mapper;
        this.environment = environment;
    }
    private final AiModelProperties properties;
    private final ObjectMapper mapper;
    private final Environment environment;

    @PostConstruct
    public void load() {
        String explicit = environment.getProperty("AI_MODELS_FILE");
        Path path = explicit != null ? Path.of(explicit) : Path.of("ai-models.json");
        if (explicit == null && !Files.exists(path)) path = Path.of("backend", "ai-models.json");
        if (!Files.isRegularFile(path)) {
            if (explicit != null) {
                log.error("ai.config.failed reason=CONFIG_FILE_NOT_FOUND");
                throw new IllegalStateException("AI_MODELS_FILE does not identify a readable JSON file");
            }
            log.info("ai.config.loaded source=environment credentialsConfigured={}", org.springframework.util.StringUtils.hasText(properties.getApiKey()));
            return; // Allows deployments using environment-only single-model configuration.
        }
        try {
            AiModelProperties loaded = mapper.readValue(path.toFile(), AiModelProperties.class);
            if (loaded.getPool() == null || loaded.getPool().isEmpty()) throw new IllegalArgumentException();
            properties.setPool(loaded.getPool());
            properties.setActiveModel(environment.getProperty("AI_ACTIVE_MODEL", loaded.getActiveModel()));
            log.info("ai.config.loaded source=json models={} enabledModels={}", loaded.getPool().size(),
                    loaded.getPool().stream().filter(m -> m != null && m.isEnabled()).count());
        } catch (Exception ignored) {
            // Do not expose Jackson errors, which may include secret values from the input.
            log.error("ai.config.failed reason=INVALID_MODEL_CONFIG");
            throw new IllegalStateException("Invalid AI model JSON configuration; check activeModel and pool");
        }
    }
}
