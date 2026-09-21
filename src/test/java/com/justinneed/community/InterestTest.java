package com.justinneed.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.interest.InterestService;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class InterestTest extends CommunityTestSupport {
    @Autowired private InterestService service;

    @Test
    void registersKoreanEnglishAndNumbersAndRejectsCaseInsensitiveDuplicate() throws Exception {
        for (String tag : List.of("Java", "한글123")) {
            mvc.perform(post("/community/me/interests").header("Authorization", auth(ownerId))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"hashtag\":\"" + tag + "\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/community/me/interests").header("Authorization", auth(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hashtag\":\"JAVA\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/community/me/interests").header("Authorization", auth(ownerId)))
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/community/me/interests").header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void rejectsInvalidAndMissingHashtags() throws Exception {
        for (String tag : List.of("", "#Java", "two words", "12345678901", " Java ")) {
            mvc.perform(post("/community/me/interests").header("Authorization", auth(ownerId))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"hashtag\":\"" + tag + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/community/me/interests").header("Authorization", auth(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/community/me/interests").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hashtag\":\"Java\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void enforcesTenTagLimitWithoutChangingExistingInterests() throws Exception {
        var profile = new CommunityProfile(ownerId);
        profile.replaceInterests(IntStream.range(0, 9).mapToObj(i -> "tag" + i).toList());
        profiles.saveAndFlush(profile);
        mvc.perform(post("/community/me/interests").header("Authorization", auth(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hashtag\":\"1234567890\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(10));
        mvc.perform(post("/community/me/interests").header("Authorization", auth(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hashtag\":\"extra\"}"))
                .andExpect(status().isBadRequest());
        assertThat(profiles.findById(ownerId).orElseThrow().getInterests()).hasSize(10).doesNotContain("extra");
    }

    @Test
    void visitorDoesNotSeeHiddenInterestWhileOwnerStillDoes() throws Exception {
        var profile = new CommunityProfile(ownerId);
        profile.replaceInterests(List.of("Java", "Secret"));
        profile.replaceHiddenHashtags(List.of("secret"));
        profiles.saveAndFlush(profile);
        mvc.perform(get("/community/profiles/{id}/interests", ownerId).header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0]").value("Java"));
        mvc.perform(get("/community/me/interests").header("Authorization", auth(ownerId)))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentAddsCannotExceedLimit() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var profile = new CommunityProfile(ownerId);
            profile.replaceInterests(IntStream.range(0, 9).mapToObj(i -> "tag" + i).toList());
            profiles.saveAndFlush(profile);
            var start = new CyclicBarrier(2);
            Callable<Boolean> first = () -> attemptAdd(start, "First");
            Callable<Boolean> second = () -> attemptAdd(start, "Second");
            var a = executor.submit(first);
            var b = executor.submit(second);
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(profiles.findById(ownerId).orElseThrow().getInterests()).hasSize(10);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            profiles.deleteById(ownerId);
            members.deleteById(ownerId);
            members.deleteById(viewerId);
        }
    }

    private boolean attemptAdd(CyclicBarrier start, String tag) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            service.add(ownerId, tag);
            return true;
        } catch (CustomException exception) {
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TAG_LIMIT_EXCEEDED);
            return false;
        }
    }
}
