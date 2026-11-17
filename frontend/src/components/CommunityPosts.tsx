import { useEffect, useState } from 'react';
import { ApiError } from '../api/auth';
import { loadFeed, loadSavedPosts, type Post } from '../api/posts';
import { CommunityPost, type CommunityAccount } from './CommunityPost';
import { PostComposer } from './PostComposer';

export function CommunityPosts({
  account,
  saved = false,
}: {
  account: CommunityAccount;
  saved?: boolean;
}) {
  const [posts, setPosts] = useState<Post[]>([]);
  const [query, setQuery] = useState({ page: 0 });
  const [result, setResult] = useState<{
    query: typeof query;
    hasNext?: boolean;
  }>();
  const [posted, setPosted] = useState(false);
  const { accessToken, onUnauthorized } = account;
  const current = result?.query === query ? result : undefined;

  useEffect(() => {
    const controller = new AbortController();
    const { signal } = controller;
    const request = saved
      ? loadSavedPosts(accessToken, signal).then((items) => ({
          items,
          hasNext: false,
        }))
      : loadFeed(query.page, accessToken, signal);
    void request.then(
      ({ items, hasNext }) => {
        if (signal.aborted) return;
        setPosts((existing) => {
          const seen = new Set(existing.map((post) => post.id));
          return [...existing, ...items.filter((post) => !seen.has(post.id))];
        });
        setResult({ query, hasNext });
      },
      (failure: unknown) => {
        if (signal.aborted) return;
        if (failure instanceof ApiError && failure.status === 401)
          onUnauthorized();
        else setResult({ query });
      },
    );
    return () => controller.abort();
  }, [accessToken, onUnauthorized, query, saved]);

  return (
    <>
      {!saved && (
        <PostComposer
          account={account}
          onPosted={(post) => {
            setPosts((existing) => [
              post,
              ...existing.filter((item) => item.id !== post.id),
            ]);
            setPosted(true);
          }}
        />
      )}
      {posted && (
        <p className="post-media-note" role="status">
          Post published.
        </p>
      )}
      {posts.map((post) => (
        <CommunityPost
          key={post.id}
          account={account}
          post={post}
          onUpdate={(changes) =>
            setPosts((existing) =>
              existing.map((item) =>
                item.id === post.id ? { ...item, ...changes } : item,
              ),
            )
          }
        />
      ))}
      {!current && (
        <p className="post-media-note" role="status">
          Loading posts…
        </p>
      )}
      {current && current.hasNext === undefined && (
        <div className="profile-card post-load-error">
          <p role="alert">
            {saved
              ? 'Couldn’t load your saved posts.'
              : 'Couldn’t load the community feed.'}
          </p>
          <button
            className="button dialog-cancel"
            type="button"
            onClick={() => setQuery({ page: query.page })}
          >
            Try again
          </button>
        </div>
      )}
      {current?.hasNext === false && posts.length === 0 && (
        <p className="profile-empty">
          {saved
            ? 'No saved posts yet.'
            : 'No posts yet. Be the first to share something.'}
        </p>
      )}
      {current?.hasNext && query.page < 10_000 && (
        <button
          className="button dialog-cancel"
          type="button"
          onClick={() => setQuery({ page: query.page + 1 })}
        >
          Load more posts
        </button>
      )}
    </>
  );
}
