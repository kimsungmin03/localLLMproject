package com.lecturenote.stt;

import com.lecturenote.config.AppProperties;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SttWorkerClient {

    private final RestClient client;
    private final AppProperties.Stt stt;

    public SttWorkerClient(RestClient.Builder builder, AppProperties props) {
        this.stt = props.stt();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.client = builder.baseUrl(stt.workerUrl()).requestFactory(factory).build();
    }

    /** 워커는 202를 즉시 반환하고 결과는 콜백으로 보낸다. 2xx가 아니면 RestClientException. */
    public void requestTranscription(long lectureId, String audioFile) {
        client.post()
                .uri("/transcribe")
                .body(new TranscribeRequest(lectureId, audioFile, stt.callbackUrl(), stt.language()))
                .retrieve()
                .toBodilessEntity();
    }

    /** audioPath 는 storage 루트 기준 파일명이다. */
    record TranscribeRequest(long lectureId, String audioPath, String callbackUrl, String language) {}
}
