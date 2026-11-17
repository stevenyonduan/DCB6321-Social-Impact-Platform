package edu.gatech.sip6321.social;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<PostComment, UUID> {
    Slice<PostComment> findByPostIdOrderByCreatedAtAscIdAsc(UUID postId, Pageable pageable);

    Optional<PostComment> findByIdAndPostId(UUID id, UUID postId);
}
