package com.justinneed.community.reaction;

import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.repository.CommunityMemberRepository;
import java.util.Collection;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class InteractionLocks {
    private final CommunityMemberRepository members;
    public InteractionLocks(CommunityMemberRepository members) { this.members = members; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire(Long actorId, Collection<Long> owners) {
        // All multi-profile writers acquire the same ascending lock order.
        var ids = new TreeSet<>(owners);
        ids.add(actorId);
        ids.forEach(id -> members.lockById(id).orElseThrow(CommunityErrors::missing));
    }
}
