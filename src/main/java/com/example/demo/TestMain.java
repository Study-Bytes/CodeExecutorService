package com.example.demo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestMain {
    private static final String BASE_URL = "http://localhost:8095";

    private static final String FIBONACCI_CODE = """
            n = int(input())
            a, b = 0, 1
            for _ in range(n):
                a, b = b, a + b
            print(a)
            """;

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        String sessionId = null;
        try {
            sessionId = createSession(client);

            for (int i = 0; i < 30; i++) {
                runTest(client, sessionId, i);
            }

            printSessionSummary(client, sessionId);
        } finally {
            if (sessionId != null) {
                cancelSession(client, sessionId);
            }
        }
    }

    private static String createSession(HttpClient client) throws Exception {
        String requestBody = """
                {
                  "language": "python",
                  "code": "%s",
                  "metadata": { "taskId": "fibonacci-30-tests" }
                }
                """.formatted(jsonEscape(FIBONACCI_CODE));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/executions"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        ensureStatus(response, 201, "create session");

        String id = extractStringField(response.body(), "id");
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("Session id is missing in response: " + response.body());
        }

        System.out.println("Session created: " + id);
        return id;
    }

    private static void runTest(HttpClient client, String sessionId, int n) throws Exception {
        String requestBody = """
                {
                  "id": "fib-%d",
                  "input": "%s"
                }
                """.formatted(n, jsonEscape(n + "\n"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/executions/" + sessionId + "/tests"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        ensureStatus(response, 200, "run test fib-" + n);

        String outcome = extractStringField(response.body(), "outcome");
        if (outcome == null || outcome.isBlank()) {
            outcome = "UNKNOWN";
        }
        String stdout = extractStdoutData(response.body())
                .replace("\r", "\\r")
                .replace("\n", "\\n");
        System.out.println("Test fib-" + n + ": outcome=" + outcome + ", stdout=" + stdout);
    }

    private static void printSessionSummary(HttpClient client, String sessionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/executions/" + sessionId))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        ensureStatus(response, 200, "get session");

        int testsCount = countOccurrences(response.body(), "\"testId\"");
        String durationMs = extractRawField(response.body(), "durationMs");
        String peakMemoryMb = extractRawField(response.body(), "peakMemoryMb");

        System.out.println("Session summary: tests=" + testsCount
                + ", durationMs=" + durationMs
                + ", peakMemoryMb=" + peakMemoryMb);
    }

    private static void cancelSession(HttpClient client, String sessionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/executions/" + sessionId + "/cancel"))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 202) {
            System.out.println("Session cancelled: " + sessionId);
            return;
        }

        // If server was already stopped or session was removed, print details and continue.
        System.out.println("Cancel returned status " + response.statusCode() + ": " + response.body());
    }

    private static void ensureStatus(HttpResponse<String> response, int expected, String action) throws IOException {
        if (response.statusCode() != expected) {
            throw new IllegalStateException("Failed to " + action
                    + ". HTTP " + response.statusCode()
                    + ", body: " + response.body());
        }
    }

    private static String jsonEscape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static String extractStringField(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return jsonUnescape(matcher.group(1));
    }

    private static String extractRawField(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*([^,}\\r\\n]+)");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return "unknown";
        }
        return matcher.group(1).trim();
    }

    private static String extractStdoutData(String json) {
        int stdoutIndex = json.indexOf("\"stdout\"");
        if (stdoutIndex < 0) {
            return "";
        }
        String stdoutPart = json.substring(stdoutIndex);
        String data = extractStringField(stdoutPart, "data");
        return data == null ? "" : data;
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(token, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + token.length();
        }
    }

    private static String jsonUnescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    default -> {
                        out.append('\\');
                        out.append(next);
                    }
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
