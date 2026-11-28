import { useEffect, useRef, useState } from 'react';
import type { Post } from '../api/posts';
import type { CommunityAccount } from './CommunityPost';
import { ActionMenu } from './ActionMenu';
import { PostEditor, type PostEditAction } from './PostEditor';

export function PostOptions({
  post,
  account,
  onUpdate,
  onRemove,
}: {
  post: Post;
  account: CommunityAccount;
  onUpdate: (changes: Partial<Post>) => void;
  onRemove: (deleted: boolean) => void;
}) {
  const [editing, setEditing] = useState<PostEditAction>();
  const [copyState, setCopyState] = useState<'copied' | 'manual'>();
  const trigger = useRef<HTMLButtonElement>(null);
  const manualLink = useRef<HTMLInputElement>(null);
  const ownPost = post.authorId === account.profile.id;
  const link = new URL(
    `/posts/${encodeURIComponent(post.id)}`,
    window.location.origin,
  ).href;

  useEffect(() => {
    if (copyState === 'manual') {
      manualLink.current?.focus();
      manualLink.current?.select();
    }
  }, [copyState]);

  async function copyLink() {
    setCopyState(undefined);
    try {
      await navigator.clipboard.writeText(link);
      setCopyState('copied');
    } catch {
      setCopyState('manual');
    }
  }

  const actions = [{ label: 'Copy link', select: () => void copyLink() }];
  return (
    <ActionMenu
      label="Post options"
      trigger={trigger}
      onOpen={() => setCopyState(undefined)}
      actions={
        ownPost
          ? [
              ...actions,
              { label: 'Edit content', select: () => setEditing('content') },
              {
                label: post.imageReference ? 'Edit image' : 'Add image',
                select: () => setEditing('image'),
              },
              {
                label: 'Delete post',
                select: () => setEditing('delete'),
                destructive: true,
              },
            ]
          : actions
      }
    >
      {copyState === 'copied' && (
        <span className="post-link-feedback" role="status">
          Link copied.
        </span>
      )}
      {copyState === 'manual' && (
        <div className="post-link-feedback">
          <label>
            Copy this post link
            <input ref={manualLink} readOnly value={link} />
          </label>
        </div>
      )}
      {editing && ownPost && (
        <PostEditor
          action={editing}
          post={post}
          account={account}
          onUpdate={onUpdate}
          onRemove={onRemove}
          onClose={() => {
            setEditing(undefined);
            requestAnimationFrame(() => trigger.current?.focus());
          }}
        />
      )}
    </ActionMenu>
  );
}
