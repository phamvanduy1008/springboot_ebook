package com.example.epub.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class ProcessSmokeIT {
    @Test
    void executableApiAndHeadlessWorkerRunAsSeparateProcessesAndStop() throws Exception {
        Path apiDir = Path.of("").toAbsolutePath();
        Path root = apiDir.getParent();
        String version = System.getProperty("reactor.version");
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        Path logs = apiDir.resolve("target/process-smoke");
        Files.createDirectories(logs);
        Path apiLog = logs.resolve("api.log");
        Path workerLog = logs.resolve("worker.log");
        Process api = null;
        Process worker = null;
        try {
            api = new ProcessBuilder(java, "-jar", apiDir.resolve("target/api-" + version + "-exec.jar").toString(),
                    "--server.port=0", "--debug=false").redirectErrorStream(true).redirectOutput(apiLog.toFile()).start();
            worker = new ProcessBuilder(java, "-jar", root.resolve("worker/target/worker-" + version + "-exec.jar").toString(), "--debug=false")
                    .redirectErrorStream(true).redirectOutput(workerLog.toFile()).start();
            awaitStarted(api, apiLog, "Started ApiApplication");
            awaitStarted(worker, workerLog, "Started WorkerApplication");
            assertNotEquals(api.pid(), worker.pid());
            assertFalse(worker.waitFor(750, TimeUnit.MILLISECONDS), "Headless worker must stay alive until stopped");
            String workerOutput = Files.readString(workerLog);
            assertFalse(workerOutput.contains("Tomcat"), workerOutput);
            assertFalse(workerOutput.contains("Netty"), workerOutput);
            var port = Pattern.compile("Tomcat started on port (\\d+)").matcher(Files.readString(apiLog));
            assertTrue(port.find(), Files.readString(apiLog));
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port.group(1) + "/actuator/health"))
                        .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertEquals("UP", JsonMapper.builder().build().readTree(response.body()).get("status").asText());
            }
        } finally {
            try {
                stop(worker);
            } finally {
                stop(api);
            }
        }
        assertFalse(api.isAlive());
        assertFalse(worker.isAlive());
    }

    private static void awaitStarted(Process process, Path log, String marker) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline) {
            String output = Files.exists(log) ? Files.readString(log) : "";
            assertTrue(process.isAlive(), "Process exited during startup:\n" + output);
            if (output.contains(marker)) return;
            Thread.sleep(100);
        }
        fail("Startup timeout:\n" + Files.readString(log));
    }

    private static void stop(Process process) throws InterruptedException {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Child process could not be stopped");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
