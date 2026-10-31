package com.justinneed.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.DatabaseMetaData;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

class FollowTest extends CommunityTestSupport {
    @Autowired JdbcTemplate jdbc;

    private ResultActions follow(Long actor, Object target, String body) throws Exception {
        return mvc.perform(patch("/community/profiles/{userId}/follow", target)
                .header("Authorization", auth(actor)).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private ResultActions follow(Long actor, Object target, boolean following) throws Exception {
        return follow(actor, target, "{\"following\":" + following + "}");
    }
    private int rows() {
        return jdbc.queryForObject("select count(*) from community_follows where actor_id = ? and target_id = ?",
                Integer.class, viewerId, ownerId);
    }
    private void withdraw(Long id) {
        var member = members.findById(id).orElseThrow();
        member.withdraw("test", LocalDateTime.now());
        members.saveAndFlush(member);
    }

    @Test
    void followAndUnfollowAreIdempotent() throws Exception {
        follow(viewerId, ownerId, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.following").value(true))
                .andExpect(jsonPath("$.data.length()").value(1));
        assertThat(rows()).isEqualTo(1);

        follow(viewerId, ownerId, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.following").value(true));
        assertThat(rows()).isEqualTo(1);

        follow(viewerId, ownerId, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.following").value(false));
        assertThat(rows()).isZero();

        follow(viewerId, ownerId, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.following").value(false));
        assertThat(rows()).isZero();
    }

    @Test
    void followIsOneWay() throws Exception {
        follow(viewerId, ownerId, true).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from community_follows where actor_id = ?",
                Integer.class, ownerId)).isZero();
    }

    @Test
    void createdAtIsRecorded() throws Exception {
        follow(viewerId, ownerId, true).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select created_at from community_follows where actor_id = ?",
                LocalDateTime.class, viewerId)).isNotNull();
    }

    @Test
    void selfFollowIsRejectedInKorean() throws Exception {
        for (boolean following : List.of(true, false)) {
            follow(viewerId, viewerId, following).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("자기 자신은 팔로우할 수 없습니다."));
        }
    }

    @Test
    void missingTargetIs404() throws Exception {
        for (boolean following : List.of(true, false)) {
            follow(viewerId, 999_999_999L, following).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("회원을 찾을 수 없습니다."));
        }
    }

    @Test
    void withdrawnTargetIs404AndExistingRowStays() throws Exception {
        follow(viewerId, ownerId, true).andExpect(status().isOk());
        withdraw(ownerId);

        follow(viewerId, ownerId, true).andExpect(status().isNotFound());
        follow(viewerId, ownerId, false).andExpect(status().isNotFound());
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void invalidBodyAndPathAre400() throws Exception {
        follow(viewerId, ownerId, "{}").andExpect(status().isBadRequest());
        follow(viewerId, ownerId, "{\"following\":\"maybe\"}").andExpect(status().isBadRequest());
        follow(viewerId, "abc", true).andExpect(status().isBadRequest());
        follow(viewerId, 0, true).andExpect(status().isBadRequest());
    }

    @Test
    void loginIsRequired() throws Exception {
        mvc.perform(patch("/community/profiles/{userId}/follow", ownerId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"following\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pairIsUniqueAndTargetIsIndexed() throws Exception {
        String insert = "insert into community_follows (actor_id, target_id, created_at) values (?, ?, ?)";
        jdbc.update(insert, viewerId, ownerId, LocalDateTime.now());
        assertThatThrownBy(() -> jdbc.update(insert, viewerId, ownerId, LocalDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);

        List<String> leadingColumns = jdbc.execute((ConnectionCallback<List<String>>) connection -> {
            DatabaseMetaData meta = connection.getMetaData();
            var found = new ArrayList<String>();
            for (String table : List.of("community_follows", "COMMUNITY_FOLLOWS")) {
                try (var rows = meta.getIndexInfo(null, null, table, false, false)) {
                    while (rows.next()) {
                        if (rows.getShort("ORDINAL_POSITION") == 1) {
                            found.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
            return found;
        });
        assertThat(leadingColumns).contains("target_id");
    }
}
