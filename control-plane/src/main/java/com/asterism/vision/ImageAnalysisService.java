package com.asterism.vision;

import com.asterism.attachment.Attachment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class ImageAnalysisService {
    private final RestClient client;
    private final String endpoint;
    private final String workerToken;

    public ImageAnalysisService(RestClient.Builder builder,
                                @Value("${asterism.image-analysis.url:http://127.0.0.1:8090/analyze-image}") String endpoint,
                                @Value("${asterism.worker-callback.token:dev-worker-token}") String workerToken) {
        this.client = builder.build();
        this.endpoint = endpoint;
        this.workerToken = workerToken;
    }

    public UiObservation analyze(String systemId, Attachment attachment, byte[] content) {
        // 图片字节仅在本次控制面到 agent-service 的请求中存在，不写日志和持久化 JSON。
        var uri = UriComponentsBuilder.fromUriString(endpoint)
                .queryParam("system_id", systemId)
                .build().toUri();
        return client.post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + workerToken)
                .contentType(MediaType.parseMediaType(attachment.contentType()))
                .body(content)
                .retrieve()
                .body(UiObservation.class);
    }
}
