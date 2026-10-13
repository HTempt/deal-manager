package com.dealmanager.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LoggingConfigurationTest {
    @TempDir Path directory;

    @Test
    void applicationConfigurationWritesConsoleAndRollingFileWithRequestContext() throws Exception {
        Path logfile = directory.resolve("logs/deal-manager.log");
        Path console = directory.resolve("console.txt");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "-cp", classpath, Probe.class.getName(), logfile.toString())
                .redirectErrorStream(true).redirectOutput(console.toFile()).start();
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) process.destroyForcibly();
        assertTrue(exited, "logging probe did not exit");
        assertEquals(0, process.exitValue(), Files.readString(console));
        String output = Files.readString(console);
        String file = Files.readString(logfile);
        for (String logs : new String[]{output, file}) {
            assertTrue(logs.contains("logging.probe.completed"));
            assertTrue(logs.contains("requestId=logging-probe userId=7"));
            assertFalse(logs.contains("logging.probe.debug_hidden"));
        }
        try (var files = Files.list(logfile.getParent())) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".gz")), "rolling archive not created");
        }
    }

    @Configuration(proxyBeanMethods = false)
    public static class Probe {
        public static void main(String[] args) {
            SpringApplication application = new SpringApplication(Probe.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            try (var context = application.run("--logging.file.name=" + args[0],
                    "--logging.logback.rollingpolicy.file-name-pattern=" + args[0] + ".%d{yyyy-MM-dd}.%i.gz",
                    "--logging.logback.rollingpolicy.max-file-size=2KB", "--spring.main.banner-mode=off")) {
                MDC.put("requestId", "logging-probe"); MDC.put("userId", "7");
                var logger = LoggerFactory.getLogger("com.dealmanager.logging.Probe");
                logger.debug("logging.probe.debug_hidden");
                for (int i = 0; i < 400; i++) logger.info("logging.probe.rotation sequence={} padding={}", i, "x".repeat(160));
                logger.info("logging.probe.completed");
                MDC.clear();
            }
        }
    }
}
