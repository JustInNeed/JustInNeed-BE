package com.justinneed.session.analysis.client;

import com.justinneed.session.analysis.AiInternalToken;
import com.justinneed.session.analysis.dto.AiAnalyzeRequest;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * AI 컨테이너 호출. AI 는 요청을 접수만 하고 바로 202 를 돌려준다 (분석 결과는 콜백으로 온다).
 * 연결 실패 · 4xx · 5xx 는 RestClientException 으로 던진다.
 */
@Component
public class AiAnalysisClient {

    private final RestClient restClient;

    public AiAnalysisClient(
            @Value("${app.ai.base-url}") String baseUrl,
            @Value("${app.ai.connect-timeout:3s}") Duration connectTimeout,
            @Value("${app.ai.read-timeout:10s}") Duration readTimeout,
            AiInternalToken internalToken
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(AiInternalToken.HEADER, internalToken.value())
                .build();
    }

    public void requestAnalysis(AiAnalyzeRequest request) {
        restClient.post()
                .uri("/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }
}
