package edu.gatech.sip6321.social;

import java.util.UUID;
import org.springframework.data.jpa.repository.*;

public interface InteractionRepository extends JpaRepository<PostInteraction, PostInteraction.Key> {
    @Modifying
    @Query(
            value =
                    "INSERT INTO public.post_interactions(user_id,post_id,interaction_type) VALUES (:user,:post,:type) ON CONFLICT (user_id,post_id,interaction_type) DO NOTHING",
            nativeQuery = true)
    int add(UUID user, UUID post, String type);

    @Modifying
    @Query(
            "delete from PostInteraction i where i.userId=:user and i.postId=:post and i.interactionType=:type")
    int remove(UUID user, UUID post, String type);
}
