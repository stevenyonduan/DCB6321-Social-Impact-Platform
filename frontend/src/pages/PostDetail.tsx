import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/auth';
import { loadPost, type Post } from '../api/posts';
import {
  CommunityPost,
  type CommunityAccount,
} from '../components/CommunityPost';
import { PageLink } from '../components/PageLink';

export function PostDetail({
  id,
  account,
}: {
  id: string;
  account: CommunityAccount;
}) {
  const { accessToken, onUnauthorized } = account;
  const [post, setPost] = useState<Post>();
  const [failure, setFailure] = useState<'missing' | 'error' | 'deleted'>();
  const [attempt, setAttempt] = useState(0);
  const title = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    const controller = new AbortController();
    void loadPost(id, accessToken, controller.signal).then(
      (value) => {
        if (!controller.signal.aborted) setPost(value);
      },
      (error: unknown) => {
        if (controller.signal.aborted) return;
        if (error instanceof ApiError && error.status === 401) onUnauthorized();
        else
          setFailure(
            error instanceof ApiError && [403, 404].includes(error.status)
              ? 'missing'
              : 'error',
          );
      },
    );
    return () => controller.abort();
  }, [id, accessToken, attempt, onUnauthorized]);

  return (
    <section
      className="content-width post-detail-page"
      aria-labelledby="post-detail-title"
    >
      <h1 id="post-detail-title" ref={title} tabIndex={-1}>
        Post
      </h1>
      {failure === 'deleted' || failure === 'missing' ? (
        <div className="post-card" role="status">
          <p>
            {failure === 'deleted'
              ? 'Post deleted.'
              : 'This post is no longer available.'}
          </p>
          <PageLink className="text-link" page="home">
            Back to feed
          </PageLink>
        </div>
      ) : failure === 'error' ? (
        <div className="post-card post-load-error">
          <p role="alert">Couldn’t load this post.</p>
          <button
            className="button dialog-cancel"
            type="button"
            onClick={() => {
              setFailure(undefined);
              setAttempt((current) => current + 1);
            }}
          >
            Try again
          </button>
        </div>
      ) : post ? (
        <CommunityPost
          post={post}
          account={account}
          commentsInitiallyOpen
          onUpdate={(changes) =>
            setPost((current) => current && { ...current, ...changes })
          }
          onRemove={(deleted) => {
            setPost(undefined);
            setFailure(deleted ? 'deleted' : 'missing');
            requestAnimationFrame(() => title.current?.focus());
          }}
        />
      ) : (
        <p role="status">Loading post…</p>
      )}
    </section>
  );
}
