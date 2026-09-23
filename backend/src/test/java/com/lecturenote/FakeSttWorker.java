package com.lecturenote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** POST /transcribe 요청을 기록하고 설정된 상태코드를 돌려주는 목 워커. 콜백은 테스트가 직접 보낸다. */
public class FakeSttWorker implements AutoCloseable {

    private final HttpServer server;
    private final BlockingQueue<JsonNode> requests = new LinkedBlockingQueue<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile int responseStatus = 202;

    public FakeSttWorker() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/transcribe", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public JsonNode awaitRequest() throws InterruptedException {
        JsonNode req = requests.poll(10, TimeUnit.SECONDS);
        if (req == null) {
            throw new AssertionError("STT worker was not called");
        }
        return req;
    }

    public JsonNode pollRequest(long millis) throws InterruptedException {
        return requests.poll(millis, TimeUnit.MILLISECONDS);
    }

    public void reset() {
        requests.clear();
        responseStatus = 202;
    }

    public void respondWith(int status) {
        this.responseStatus = status;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
