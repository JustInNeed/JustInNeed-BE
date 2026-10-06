package com.justinneed.community.search;

import com.justinneed.community.graph.PublicGraphService;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.util.function.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PublicDirectory {
    private final CommunityDirectoryRepository directory;
    private final PublicGraphService graphs;
    public PublicDirectory(CommunityDirectoryRepository directory, PublicGraphService graphs) {
        this.directory = directory; this.graphs = graphs;
    }
    public void visit(Predicate<PublicGraphService.Graph> visitor) {
        // Deliberately no enclosing transaction: each graph releases its owner's lock.
        long after = 0;
        while (true) {
            var ids = directory.candidates(after, PageRequest.of(0, 100));
            if (ids.isEmpty()) return;
            for (Long id : ids) {
                PublicGraphService.Graph graph;
                try { graph = graphs.graph(id, null); }
                catch (CustomException error) {
                    if (error.getErrorCode() == ErrorCode.MEMBER_NOT_FOUND) continue;
                    throw error;
                }
                catch (ResponseStatusException error) {
                    if (error.getStatusCode().value() == 404) continue;
                    throw error;
                }
                if (!visitor.test(graph)) return;
            }
            after = ids.get(ids.size() - 1);
        }
    }
}
