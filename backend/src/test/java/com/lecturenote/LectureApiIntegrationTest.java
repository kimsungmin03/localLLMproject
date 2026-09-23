package com.lecturenote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.lecturenote.lecture.LectureResponse;
import com.lecturenote.lecture.LectureStatus;
import com.lecturenote.lecture.SegmentResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.stt.shared-secret=" + LectureApiIntegrationTest.TOKEN,
        "app.stt.inactivity-timeout=3s",
        "app.stt.max-duration=30s",
})
class LectureApiIntegrationTest {

    static final String TOKEN = "test-secret";

    static final FakeSttWorker worker = new FakeSttWorker();
    static final Path storageDir;

    static {
        try {
            storageDir = Files.createTempDirectory("lecturenote-audio");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        registry.add("app.stt.worker-url", worker::url);
        registry.add("app.storage.audio-dir", storageDir::toString);
    }

    @AfterAll
    static void stopWorker() {
        worker.close();
    }

    @Autowired
    TestRestTemplate rest;

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        worker.reset();
    }

    @Test
    void uploadTranscribeAndFinish() throws Exception {
        LectureResponse uploaded = upload("lecture.mp3", "hello-audio".getBytes());
        assertThat(uploaded.title()).isEqualTo("lecture");

        JsonNode req = worker.awaitRequest();
        long id = req.get("lectureId").asLong();
        assertThat(id).isEqualTo(uploaded.id());
        // 워커에는 경로가 아닌 파일명만 전달되고, 그 파일은 storage 루트에 있다.
        String audioFile = req.get("audioPath").asText();
        assertThat(audioFile).doesNotContain("/", "\\").endsWith(".mp3");
        assertThat(storageDir.resolve(audioFile)).exists();
        assertThat(req.get("language").asText()).isEqualTo("ko");

        assertThat(callback(Map.of("lectureId", id, "status", "PROGRESS", "progress", 40, "durationMs", 600_000))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        LectureResponse mid = get(id);
        assertThat(mid.status()).isEqualTo(LectureStatus.TRANSCRIBING);
        assertThat(mid.progress()).isEqualTo(40);
        assertThat(mid.durationMs()).isEqualTo(600_000);

        callback(Map.of("lectureId", id, "status", "COMPLETED", "progress", 100, "durationMs", 601_000,
                "segments", List.of(
                        Map.of("seq", 1, "startMs", 3000, "endMs", 6000, "text", "두 번째"),
                        Map.of("seq", 0, "startMs", 0, "endMs", 3000, "text", "첫 번째"))));

        await().atMost(Duration.ofSeconds(10)).until(() -> get(id).status() == LectureStatus.DONE);
        LectureResponse done = get(id);
        assertThat(done.progress()).isEqualTo(100);
        assertThat(done.durationMs()).isEqualTo(601_000);

        List<SegmentResponse> transcript = rest.exchange("/api/lectures/{id}/transcript", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<SegmentResponse>>() {}, id).getBody();
        assertThat(transcript).extracting(SegmentResponse::text).containsExactly("첫 번째", "두 번째");

        // 종료 후 늦게 도착한 PROGRESS 는 무시된다.
        assertThat(callback(Map.of("lectureId", id, "status", "PROGRESS", "progress", 95)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(get(id).status()).isEqualTo(LectureStatus.DONE);
        assertThat(get(id).progress()).isEqualTo(100);

        // 요약은 4단계 전이므로 없다.
        assertThat(rest.getForEntity("/api/lectures/{id}/summary", String.class, id).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void callbackRequiresToken() {
        var body = Map.of("lectureId", 1, "status", "PROGRESS", "progress", 10);
        assertThat(rest.postForEntity("/internal/stt/callback", body, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-STT-Token", "wrong");
        assertThat(rest.postForEntity("/internal/stt/callback", new HttpEntity<>(body, headers), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void sttFailureMarksLectureFailed() throws Exception {
        long id = upload("fail.wav", new byte[] {1, 2, 3}).id();
        worker.awaitRequest();
        callback(Map.of("lectureId", id, "status", "FAILED", "progress", 0, "errorMessage", "CUDA out of memory"));

        await().atMost(Duration.ofSeconds(10)).until(() -> get(id).status() == LectureStatus.FAILED);
        assertThat(get(id).errorMessage()).isEqualTo("CUDA out of memory");
    }

    @Test
    void workerUnavailableMarksLectureFailed() throws Exception {
        worker.respondWith(503);
        long id = upload("busy.wav", new byte[] {1}).id();
        worker.awaitRequest();

        await().atMost(Duration.ofSeconds(10)).until(() -> get(id).status() == LectureStatus.FAILED);
        assertThat(get(id).errorMessage()).startsWith("STT worker request failed");
    }

    @Test
    void noCallbackTimesOut() throws Exception {
        long id = upload("silent.wav", new byte[] {1}).id();
        worker.awaitRequest();

        await().atMost(Duration.ofSeconds(10)).until(() -> get(id).status() == LectureStatus.FAILED);
        assertThat(get(id).errorMessage()).contains("No STT callback");
        assertThat(callback(Map.of("lectureId", id, "status", "COMPLETED", "progress", 100, "segments", List.of()))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void jobsRunOneAtATime() throws Exception {
        long first = upload("a.mp3", new byte[] {1}).id();
        long second = upload("b.mp3", new byte[] {2}).id();

        assertThat(worker.awaitRequest().get("lectureId").asLong()).isEqualTo(first);
        // 첫 작업이 워커 202를 받았어도 콜백이 끝나기 전에는 두 번째를 보내지 않는다.
        callback(Map.of("lectureId", first, "status", "PROGRESS", "progress", 50));
        assertThat(worker.pollRequest(1500)).isNull();
        assertThat(get(second).status()).isEqualTo(LectureStatus.UPLOADED);

        callback(Map.of("lectureId", first, "status", "COMPLETED", "progress", 100, "segments", List.of()));
        assertThat(worker.awaitRequest().get("lectureId").asLong()).isEqualTo(second);
        callback(Map.of("lectureId", second, "status", "COMPLETED", "progress", 100, "segments", List.of()));
        await().atMost(Duration.ofSeconds(10)).until(() -> get(second).status() == LectureStatus.DONE);
        assertThat(get(first).status()).isEqualTo(LectureStatus.DONE);
    }

    @Test
    void audioSupportsRangeRequests() throws Exception {
        byte[] content = "0123456789abcdefghij".getBytes();
        long id = completeLecture(upload("range.mp3", content).id());

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=5-9");
        ResponseEntity<byte[]> partial = rest.exchange("/api/lectures/{id}/audio", HttpMethod.GET,
                new HttpEntity<>(headers), byte[].class, id);
        assertThat(partial.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(partial.getBody()).isEqualTo("56789".getBytes());
        assertThat(partial.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 5-9/20");
        assertThat(partial.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("audio/mpeg"));

        ResponseEntity<byte[]> full = rest.getForEntity("/api/lectures/{id}/audio", byte[].class, id);
        assertThat(full.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(full.getBody()).isEqualTo(content);
    }

    @Test
    void deleteRemovesLectureAndAudio() throws Exception {
        long id = upload("del.mp3", new byte[] {1}).id();
        String audioFile = worker.awaitRequest().get("audioPath").asText();

        // 처리 중에는 삭제할 수 없다.
        assertThat(rest.exchange("/api/lectures/{id}", HttpMethod.DELETE, null, String.class, id).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        callback(Map.of("lectureId", id, "status", "COMPLETED", "progress", 100,
                "segments", List.of(Map.of("seq", 0, "startMs", 0, "endMs", 1000, "text", "x"))));
        await().atMost(Duration.ofSeconds(10)).until(() -> get(id).status() == LectureStatus.DONE);

        assertThat(rest.exchange("/api/lectures/{id}", HttpMethod.DELETE, null, String.class, id).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.getForEntity("/api/lectures/{id}", String.class, id).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(storageDir.resolve(audioFile)).doesNotExist();
    }

    @Test
    void rejectsUnsupportedFileType() {
        ResponseEntity<String> res = rest.postForEntity("/api/lectures", multipart("notes.txt", new byte[] {1}), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sseStreamsStatusUntilDone() throws Exception {
        long id = upload("sse.mp3", new byte[] {1}).id();
        worker.awaitRequest();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/lectures/" + id + "/events"))
                .header("Accept", "text/event-stream").build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);

        List<String> events = new ArrayList<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var in = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        synchronized (events) { events.add(line); }
                    }
                }
            } catch (IOException ignored) {
            }
        });

        await().atMost(Duration.ofSeconds(5)).until(() -> { synchronized (events) { return !events.isEmpty(); } });
        callback(Map.of("lectureId", id, "status", "PROGRESS", "progress", 30));
        callback(Map.of("lectureId", id, "status", "COMPLETED", "progress", 100, "segments", List.of()));

        // DONE 이벤트 후 서버가 스트림을 닫는다.
        reader.join(Duration.ofSeconds(10));
        assertThat(reader.isAlive()).isFalse();
        assertThat(events.getFirst()).contains("\"status\":\"TRANSCRIBING\"");
        assertThat(events).anyMatch(e -> e.contains("\"progress\":30"));
        assertThat(events.getLast()).contains("\"status\":\"DONE\"");
    }

    private long completeLecture(long id) throws InterruptedException {
        worker.awaitRequest();
        callback(Map.of("lectureId", id, "status", "COMPLETED", "progress", 100, "segments", List.of()));
        await().atMost(Duration.ofSeconds(10)).until(() -> get(id).status() == LectureStatus.DONE);
        return id;
    }

    private LectureResponse upload(String filename, byte[] content) {
        ResponseEntity<LectureResponse> res = rest.postForEntity("/api/lectures", multipart(filename, content), LectureResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(res.getHeaders().getLocation()).hasPath("/api/lectures/" + res.getBody().id());
        return res.getBody();
    }

    private HttpEntity<LinkedMultiValueMap<String, Object>> multipart(String filename, byte[] content) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
    }

    private LectureResponse get(long id) {
        return rest.getForObject("/api/lectures/{id}", LectureResponse.class, id);
    }

    private ResponseEntity<String> callback(Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-STT-Token", TOKEN);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/internal/stt/callback", new HttpEntity<>(body, headers), String.class);
    }
}
