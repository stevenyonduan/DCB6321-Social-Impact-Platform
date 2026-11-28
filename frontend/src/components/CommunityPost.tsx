import { useContext, useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { Bookmark, Heart, MessageCircle, UserRound } from 'lucide-react';
import { ApiError, type Profile } from '../api/auth';
import {
  createComment,
  deleteComment,
  loadComments,
  updatePostInteraction,
  type Post,
  type PostComment,
} from '../api/posts';
import { PostImage } from './PostImage';
import { ActionMenu } from './ActionMenu';
import { FormDialog } from './FormDialog';
import { PostOptions } from './PostOptions';
import { OwnAvatarContext } from '../auth/ownAvatar';
import { ProfileAvatar } from './ProfileAvatar';

export type CommunityAccount = {
  profile: Profile;
  accessToken: string;
  onUnauthorized: () => void;
};

const relativeTime = new Intl.RelativeTimeFormat(undefined, {
  numeric: 'always',
});

function age(timestamp: string, now: number) {
  const minutes = Math.floor((now - Date.parse(timestamp)) / 60_000);
  if (minutes < 1) return 'Just now';
  if (minutes < 60) return relativeTime.format(-minutes, 'minute');
  const hours = Math.floor(minutes / 60);
  return hours < 24
    ? relativeTime.format(-hours, 'hour')
    : relativeTime.format(-Math.floor(hours / 24), 'day');
}

function authorName(profile: Profile, authorId: string) {
  return authorId === profile.id
    ? `${profile.firstName} ${profile.lastName}`
    : 'Community member';
}

export function CommunityAvatar({
  profile,
  authorId,
}: {
  profile: Profile;
  authorId: string;
}) {
  const ownAvatar = useContext(OwnAvatarContext);
  const own = authorId === profile.id;
  const legacyAvatar = profile.avatarUrl;
  const initials = [profile.firstName, profile.lastName]
    .map((name) => Array.from(name.trim())[0])
    .join('')
    .toUpperCase();
  return (
    <ProfileAvatar
      as="span"
      className="person-avatar community-avatar"
      imageSrc={own ? ownAvatar?.src : undefined}
      avatarUrl={own && legacyAvatar && /^https?:\/\//i.test(legacyAvatar) ? legacyAvatar : undefined}
      fallback={own ? initials : <UserRound aria-hidden="true" />}
    />
  );
}

export function CommunityPost({
  post,
  account,
  commentsInitiallyOpen = false,
  onUpdate,
  onRemove,
}: {
  post: Post;
  account: CommunityAccount;
  commentsInitiallyOpen?: boolean;
  onUpdate: (changes: Partial<Post>) => void;
  onRemove: (deleted: boolean) => void;
}) {
  const { profile, accessToken, onUnauthorized } = account;
  const [now] = useState(Date.now);
  const [commentsOpen, setCommentsOpen] = useState<
    'automatic' | 'manual' | false
  >(commentsInitiallyOpen ? 'automatic' : false);
  const [pending, setPending] = useState<'like' | 'save'>();
  const [error, setError] = useState('');
  const action = useRef<AbortController | null>(null);
  const commentButton = useRef<HTMLButtonElement>(null);
  const commentsId = useId();
  const titleId = useId();
  useEffect(() => () => action.current?.abort(), []);

  async function toggle(kind: 'like' | 'save') {
    if (action.current) return;
    const controller = new AbortController();
    action.current = controller;
    const enabled = kind === 'like' ? !post.liked : !post.saved;
    setPending(kind);
    setError('');
    try {
      await updatePostInteraction(
        post.id,
        kind,
        enabled,
        accessToken,
        controller.signal,
      );
      if (controller.signal.aborted) return;
      onUpdate(
        kind === 'save'
          ? { saved: enabled }
          : {
              liked: enabled,
              likeCount: Math.max(0, post.likeCount + (enabled ? 1 : -1)),
            },
      );
    } catch (failure) {
      if (controller.signal.aborted) return;
      if (failure instanceof ApiError && failure.status === 401)
        onUnauthorized();
      else
        setError(
          kind === 'like'
            ? 'Couldn’t update your like. Try again.'
            : 'Couldn’t update your save. Try again.',
        );
    } finally {
      action.current = null;
      if (!controller.signal.aborted) setPending(undefined);
    }
  }

  return (
    <article className="post-card" aria-labelledby={titleId}>
      <div className="post-card-header">
        <div className="post-author">
          <CommunityAvatar profile={profile} authorId={post.authorId} />
          <div>
            <h3 id={titleId}>{authorName(profile, post.authorId)}</h3>
            <p>
              Volunteer ·{' '}
              <time dateTime={post.createdAt}>{age(post.createdAt, now)}</time>
            </p>
          </div>
        </div>
        <PostOptions
          post={post}
          account={account}
          onUpdate={onUpdate}
          onRemove={onRemove}
        />
      </div>
      {post.textContent && <p className="post-text">{post.textContent}</p>}
      {post.imageReference && (
        <PostImage key={post.imageReference} reference={post.imageReference} />
      )}
      <p className="post-stats" aria-live="polite">
        {post.likeCount} {post.likeCount === 1 ? 'like' : 'likes'} ·{' '}
        {post.commentCount} {post.commentCount === 1 ? 'comment' : 'comments'}
      </p>
      <div className="post-actions">
        <button
          type="button"
          aria-pressed={post.liked}
          disabled={pending !== undefined}
          onClick={() => void toggle('like')}
        >
          <Heart aria-hidden="true" /> {post.liked ? 'Liked' : 'Like'}
        </button>
        <button
          type="button"
          ref={commentButton}
          aria-expanded={Boolean(commentsOpen)}
          aria-controls={commentsOpen ? commentsId : undefined}
          onClick={() => setCommentsOpen((open) => (open ? false : 'manual'))}
        >
          <MessageCircle aria-hidden="true" /> Comment
        </button>
        <button
          type="button"
          aria-pressed={post.saved}
          disabled={pending !== undefined}
          onClick={() => void toggle('save')}
        >
          <Bookmark aria-hidden="true" /> {post.saved ? 'Saved' : 'Save'}
        </button>
      </div>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      {commentsOpen && (
        <PostComments
          id={commentsId}
          postId={post.id}
          account={account}
          now={now}
          focusOnOpen={commentsOpen === 'manual'}
          onCount={(commentCount) => onUpdate({ commentCount })}
          onClose={() => {
            setCommentsOpen(false);
            commentButton.current?.focus();
          }}
        />
      )}
    </article>
  );
}

function PostComments({
  id,
  postId,
  account,
  now,
  focusOnOpen,
  onCount,
  onClose,
}: {
  id: string;
  postId: string;
  account: CommunityAccount;
  now: number;
  focusOnOpen: boolean;
  onCount: (count: number) => void;
  onClose: () => void;
}) {
  const { profile, accessToken, onUnauthorized } = account;
  const [thread, setThread] = useState<PostComment[] | null>();
  const [attempt, setAttempt] = useState(0);
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  const [selected, setSelected] = useState<{
    commentId: string;
    button: HTMLButtonElement | null;
  }>();
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState('');
  const [feedback, setFeedback] = useState('');
  const field = useRef<HTMLTextAreaElement>(null);
  const action = useRef<AbortController | null>(null);
  const cancelDelete = useRef<HTMLButtonElement>(null);
  const feedbackElement = useRef<HTMLParagraphElement>(null);

  useEffect(() => () => action.current?.abort(), []);
  useEffect(() => {
    const controller = new AbortController();
    void loadComments(postId, accessToken, controller.signal).then(
      (items) => {
        if (!controller.signal.aborted) setThread(items);
      },
      (failure: unknown) => {
        if (controller.signal.aborted) return;
        if (failure instanceof ApiError && failure.status === 401)
          onUnauthorized();
        else setThread(null);
      },
    );
    return () => controller.abort();
  }, [accessToken, attempt, onUnauthorized, postId]);

  useEffect(() => {
    if (focusOnOpen) field.current?.focus();
  }, [focusOnOpen]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!thread || !draft.trim() || selected || action.current) return;
    const controller = new AbortController();
    action.current = controller;
    setSending(true);
    setError('');
    try {
      const comment = await createComment(
        postId,
        draft,
        accessToken,
        controller.signal,
      );
      if (controller.signal.aborted) return;
      const updated = [...thread, comment];
      setThread(updated);
      onCount(updated.length);
      setDraft('');
    } catch (failure) {
      if (controller.signal.aborted) return;
      if (failure instanceof ApiError && failure.status === 401)
        onUnauthorized();
      else
        setError(
          failure instanceof ApiError && failure.status === 413
            ? 'This comment is too long. Try shortening it.'
            : 'Couldn’t confirm your comment. Your draft is still here.',
        );
    } finally {
      action.current = null;
      if (!controller.signal.aborted) setSending(false);
    }
  }

  function closeDelete() {
    if (action.current) return;
    const button = selected?.button;
    setSelected(undefined);
    requestAnimationFrame(() => button?.focus());
  }

  async function confirmDelete(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected || !thread || action.current) return;
    const currentThread = thread;
    const controller = new AbortController();
    action.current = controller;
    setDeleting(true);
    setDeleteError('');
    const { commentId } = selected;

    function remove(message: string) {
      const remaining = currentThread.filter(
        (comment) => comment.id !== commentId,
      );
      setThread(remaining);
      onCount(remaining.length);
      setSelected(undefined);
      setFeedback(message);
      requestAnimationFrame(() => feedbackElement.current?.focus());
    }

    try {
      await deleteComment(postId, commentId, accessToken, controller.signal);
      if (!controller.signal.aborted) remove('Comment deleted.');
    } catch (failure) {
      if (controller.signal.aborted) return;
      if (failure instanceof ApiError && failure.status === 401) {
        onUnauthorized();
      } else if (
        failure instanceof ApiError &&
        failure.code === 'COMMENT_NOT_FOUND'
      ) {
        remove('This comment is no longer available.');
      } else {
        setDeleteError(
          failure instanceof ApiError && failure.status === 403
            ? 'You no longer have permission to delete this comment.'
            : failure instanceof ApiError && failure.code === 'POST_NOT_FOUND'
              ? 'This post is no longer available.'
              : 'Couldn’t confirm deletion. Try again.',
        );
      }
    } finally {
      action.current = null;
      if (!controller.signal.aborted) setDeleting(false);
    }
  }

  return (
    <section id={id} className="post-comments" aria-label="Comments">
      {thread === undefined && <p role="status">Loading comments…</p>}
      {thread === null && (
        <div className="post-thread-error">
          <p role="alert">Couldn’t load comments.</p>
          <button
            className="text-link"
            type="button"
            onClick={() => {
              setThread(undefined);
              setAttempt((value) => value + 1);
            }}
          >
            Try again
          </button>
        </div>
      )}
      {thread?.length ? (
        <ul className="post-comment-list">
          {thread.map((comment) => (
            <li key={comment.id}>
              <CommunityAvatar profile={profile} authorId={comment.authorId} />
              <div className="post-comment-content">
                <strong>{authorName(profile, comment.authorId)}</strong>
                {' · '}
                <time dateTime={comment.createdAt}>
                  {age(comment.createdAt, now)}
                </time>
                <p>{comment.content}</p>
              </div>
              {comment.authorId === profile.id && (
                <CommentOptions
                  onDelete={(button) => {
                    if (action.current) return;
                    setDeleteError('');
                    setFeedback('');
                    setSelected({ commentId: comment.id, button });
                  }}
                />
              )}
            </li>
          ))}
        </ul>
      ) : (
        thread && <p className="profile-empty">No comments yet.</p>
      )}
      {feedback && (
        <p
          className="post-comment-feedback"
          role="status"
          tabIndex={-1}
          ref={feedbackElement}
        >
          {feedback}
        </p>
      )}
      <form onSubmit={(event) => void submit(event)}>
        <label>
          Write a comment
          <textarea
            ref={field}
            rows={2}
            value={draft}
            readOnly={sending}
            onChange={(event) => setDraft(event.target.value)}
          />
        </label>
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        <div className="profile-form-actions">
          <button
            className="button primary"
            type="submit"
            disabled={sending || !thread || !draft.trim()}
          >
            {sending ? 'Posting…' : 'Post comment'}
          </button>
          <button
            className="button dialog-cancel"
            type="button"
            disabled={sending}
            onClick={onClose}
          >
            Cancel
          </button>
        </div>
      </form>
      {selected && (
        <FormDialog
          title="Delete comment"
          initialFocus={cancelDelete}
          busy={deleting}
          onClose={closeDelete}
          onSubmit={(event) => void confirmDelete(event)}
        >
          <p>Delete this comment permanently?</p>
          {deleteError && (
            <p className="form-error" role="alert">
              {deleteError}
            </p>
          )}
          <div className="organization-form-actions">
            <button
              type="button"
              className="button dialog-cancel"
              ref={cancelDelete}
              disabled={deleting}
              onClick={closeDelete}
            >
              Cancel
            </button>
            <button
              type="submit"
              className="button post-delete-confirm"
              disabled={deleting}
            >
              {deleting ? 'Deleting…' : 'Delete comment'}
            </button>
          </div>
        </FormDialog>
      )}
    </section>
  );
}

function CommentOptions({
  onDelete,
}: {
  onDelete: (button: HTMLButtonElement | null) => void;
}) {
  const trigger = useRef<HTMLButtonElement>(null);
  return (
    <ActionMenu
      label="Comment options"
      trigger={trigger}
      actions={[
        {
          label: 'Delete comment',
          select: () => onDelete(trigger.current),
          destructive: true,
        },
      ]}
    />
  );
}
