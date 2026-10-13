package com.dealmanager.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AiModelJsonLoaderTest {
    @TempDir Path dir;
    @Test void loadsPoolAndPriorityFromJson() throws Exception {
        Path file = dir.resolve("models.json");
        Files.writeString(file, """
                {"activeModel":"backup","pool":[
                {"id":"primary","enabled":true,"baseUrl":"https://a.test/v1","model":"qwen","apiKey":"secret"},
                {"id":"backup","enabled":true,"baseUrl":"https://b.test/v1","model":"gpt","apiKey":"other"}]}
                """);
        AiModelProperties p = new AiModelProperties();
        MockEnvironment env = new MockEnvironment().withProperty("AI_MODELS_FILE", file.toString());
        new AiModelJsonLoader(p, new ObjectMapper(), env).load();
        assertEquals("backup", p.getActiveModel());
        assertEquals(2, p.getPool().size());
        assertEquals("gpt", p.getPool().get(1).getModel());
        env.setProperty("AI_ACTIVE_MODEL", "primary");
        new AiModelJsonLoader(p, new ObjectMapper(), env).load();
        assertEquals("primary", p.getActiveModel());
    }
    @Test void invalidJsonDoesNotLeakSecrets() throws Exception {
        Path file = dir.resolve("bad.json");
        Files.writeString(file, "{ secret-key-invalid }");
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            new AiModelJsonLoader(new AiModelProperties(), new ObjectMapper(),
                new MockEnvironment().withProperty("AI_MODELS_FILE", file.toString())).load());
        assertFalse(error.getMessage().contains("secret-key"));
        assertNull(error.getCause());
    }
}
