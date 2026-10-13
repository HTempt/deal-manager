package com.dealmanager.ai;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** AI 模型根配置及模型池配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiModelProperties {
    private String activeModel;
    private String baseUrl;
    @ToString.Exclude
    private String apiKey;
    private String model;
    private List<Model> pool = new ArrayList<>();

    @Data
    public static class Model {
        private String id;
        private String baseUrl;
        @ToString.Exclude
        private String apiKey;
        private String model;
        private boolean enabled = true;
    }
}
