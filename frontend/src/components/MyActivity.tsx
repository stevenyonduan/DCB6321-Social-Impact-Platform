import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/auth';
import { loadFeed, type Post } from '../api/posts';
import { CommunityPost, type CommunityAccount } from './CommunityPost';

const batchSize = 5;
const lastFeedPage = 10_000;

export function MyActivity({ account }: { account: CommunityAccount }) {
  const { accessToken, onUnauthorized, profile } = account;
  const authorId = profile.id;
  const [posts, setPosts] = useState<Post[]>([]);
  const [batch, setBatch] = useState(0);
  const [result, setResult] = useState<{
    batch: number;
    status: 'more' | 'done' | 'error';
  }>();
  const cursor = useRef({
    page: 0 as number | null,
    waiting: [] as Post[],
    seen: new Set<string>(),
    remaining: batchSize,
  });
  const loadMore = useRef<HTMLButtonElement>(null);
  const status = result?.batch === batch ? result.status : undefined;

  useEffect(() => {
    const controller = new AbortController();
    const { signal } = controller;
    const position = cursor.current;

    async function readBatch() {
      while (position.remaining > 0) {
        const matches = position.waiting.splice(0, position.remaining);
        if (matches.length) {
          position.remaining -= matches.length;
          setPosts((current) => [...current, ...matches]);
        }
        const page = position.page;
        if (position.remaining === 0 || page === null) break;

        const result = await loadFeed(page, accessToken, signal);
        signal.throwIfAborted();
        position.page = result.hasNext && page < lastFeedPage ? page + 1 : null;
        for (const post of result.items) {
          if (post.authorId !== authorId || position.seen.has(post.id))
            continue;
          position.seen.add(post.id);
          position.waiting.push(post);
        }
      }
    }

    void Promise.resolve()
      .then(() => {
        signal.throwIfAborted();
        return readBatch();
      })
      .then(
        () => {
          if (signal.aborted) return;
          position.remaining = batchSize;
          setResult({
            batch,
            status:
              position.page !== null || position.waiting.length
                ? 'more'
                : 'done',
          });
        },
        (failure: unknown) => {
          if (signal.aborted) return;
          if (failure instanceof ApiError && failure.status === 401)
            onUnauthorized();
          else setResult({ batch, status: 'error' });
        },
      );
    return () => controller.abort();
  }, [accessToken, authorId, batch, onUnauthorized]);

  useEffect(() => {
    const button = loadMore.current;
    if (
      status !== 'more' ||
      !button ||
      typeof IntersectionObserver === 'undefined'
    )
      return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return;
        observer.disconnect();
        setBatch((current) => (current === batch ? current + 1 : current));
      },
      { rootMargin: '0px 0px 240px 0px' },
    );
    observer.observe(button);
    return () => observer.disconnect();
  }, [batch, status]);

  return (
    <section aria-labelledby="profile-activity">
      <h2 className="profile-section-heading" id="profile-activity">
        Activity
      </h2>
      {posts.map((post) => (
        <CommunityPost
          key={post.id}
          post={post}
          account={account}
          onUpdate={(changes) =>
            setPosts((current) =>
              current.map((item) =>
                item.id === post.id ? { ...item, ...changes } : item,
              ),
            )
          }
        />
      ))}
      {!status && (
        <p className="profile-empty" role="status">
          {posts.length ? 'Loading older posts…' : 'Loading activity…'}
        </p>
      )}
      {status === 'done' && posts.length === 0 && (
        <p className="profile-empty">You haven’t posted yet.</p>
      )}
      {status === 'error' && (
        <p className="form-error" role="alert">
          Couldn’t load your activity.
        </p>
      )}
      {(status === 'more' || status === 'error') && (
        <button
          className="button dialog-cancel"
          ref={loadMore}
          type="button"
          onClick={() =>
            setBatch((current) => (current === batch ? current + 1 : current))
          }
        >
          {status === 'error' ? 'Retry activity' : 'Load older posts'}
        </button>
      )}
    </section>
  );
}
