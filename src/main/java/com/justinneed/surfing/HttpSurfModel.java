package com.justinneed.surfing;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
@Component
public class HttpSurfModel implements SurfModel {
    private final String endpoint;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    public HttpSurfModel(@Value("${app.surfing.analysis-url:}") String endpoint) { this.endpoint=endpoint; }
    public Result analyze(List<SurfEvent> events) {
        if(endpoint.isBlank()) throw new IllegalStateException("Analysis service unavailable");
        try {
            var request=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(20))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(events))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200 || response.body().length()>500000) throw new IllegalStateException("Invalid analysis response");
            return json.readValue(response.body(),Result.class);
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Analysis interrupted",e);
        } catch(Exception e) { throw new IllegalStateException("Analysis service failed",e); }
    }
}
