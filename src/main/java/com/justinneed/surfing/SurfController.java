package com.justinneed.surfing;
import com.justinneed.global.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.List;
@RestController @RequestMapping("/surfing")
public class SurfController {
    private final SurfService service;
    public SurfController(SurfService service) { this.service=service; }
    @PostMapping("/extension-connections") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SurfService.Connection> connect(@AuthenticationPrincipal Long user) { return ApiResponse.ok(service.connect(user)); }
    @PostMapping("/sessions") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SurfService.View> start(@AuthenticationPrincipal Long user,@RequestHeader("X-Extension-Token") String token) { return ApiResponse.ok(service.start(user,token)); }
    @PatchMapping("/sessions/{id}/end")
    public ApiResponse<SurfService.View> end(@AuthenticationPrincipal Long user,@PathVariable Long id,@RequestHeader("X-Extension-Token") String token,@Valid @RequestBody SurfRequests.End body) { return ApiResponse.ok(service.end(user,id,token,body.reason())); }
    @PostMapping("/sessions/{id}/events")
    public ApiResponse<SurfService.Accepted> events(@AuthenticationPrincipal Long user,@PathVariable Long id,@RequestHeader("X-Extension-Token") String token,@Valid @RequestBody SurfRequests.Batch body) { return ApiResponse.ok(service.collect(user,id,token,body)); }
    @GetMapping("/sessions/{id}/analysis-status")
    public ApiResponse<SurfService.View> status(@AuthenticationPrincipal Long user,@PathVariable Long id) { return ApiResponse.ok(service.status(user,id)); }
    @PostMapping("/sessions/{id}/reanalyze")
    public ApiResponse<SurfService.View> retry(@AuthenticationPrincipal Long user,@PathVariable Long id) { return ApiResponse.ok(service.retry(user,id)); }
    @PostMapping("/sessions/{id}/scraps") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SurfScrap> scrap(@AuthenticationPrincipal Long user,@PathVariable Long id,@RequestHeader("X-Extension-Token") String token,@Valid @RequestBody SurfRequests.Scrap body) { return ApiResponse.ok(service.scrap(user,id,token,body)); }
    @GetMapping("/sessions/{id}/scraps")
    public ApiResponse<List<SurfScrap>> scraps(@AuthenticationPrincipal Long user,@PathVariable Long id) { return ApiResponse.ok(service.scraps(user,id)); }
}
