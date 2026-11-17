package edu.gatech.sip6321.social;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface PostRepository extends JpaRepository<Post, UUID> {
    String VIEW =
            """
        select new edu.gatech.sip6321.social.PostResponse(p.id,p.authorId,p.organizationId,
        p.textContent,p.imageReference,p.createdAt,p.updatedAt,
        (select count(i) from PostInteraction i where i.postId=p.id and i.interactionType='LIKE'),
        (select count(c) from PostComment c where c.postId=p.id),
        (select count(i) from PostInteraction i where i.postId=p.id and i.userId=:user and i.interactionType='LIKE') > 0,
        (select count(i) from PostInteraction i where i.postId=p.id and i.userId=:user and i.interactionType='SAVE') > 0)
        from Post p
        """;

    @Query(VIEW + " where p.organizationId is null order by p.createdAt desc,p.id desc")
    Slice<PostResponse> feed(UUID user, Pageable pageable);

    @Query(VIEW + " where p.organizationId is null and p.id=:id")
    Optional<PostResponse> view(UUID user, UUID id);

    @Query(
            VIEW
                    + " join PostInteraction s on s.postId=p.id and s.userId=:user and s.interactionType='SAVE' where p.organizationId is null order by s.createdAt desc,p.id desc")
    Slice<PostResponse> saved(UUID user, Pageable pageable);

    // Serialize all per-post writes with deletion; child inserts cannot race the existence check.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Post p where p.id=:id and p.organizationId is null")
    Optional<Post> lock(UUID id);
}
