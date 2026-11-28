import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { ApiError } from '../api/auth';
import {
  deletePost,
  editPostContent,
  editPostImage,
  hasPostText,
  isPostImage,
  loadPost,
  postImageTypes,
  type Post,
} from '../api/posts';
import type { CommunityAccount } from './CommunityPost';
import { FormDialog } from './FormDialog';
import { PostImage } from './PostImage';

export type PostEditAction = 'content' | 'image' | 'delete';

export function PostEditor({
  action,
  post,
  account,
  onClose,
  onUpdate,
  onRemove,
}: {
  action: PostEditAction;
  post: Post;
  account: CommunityAccount;
  onClose: () => void;
  onUpdate: (changes: Partial<Post>) => void;
  onRemove: (deleted: boolean) => void;
}) {
  const { accessToken, onUnauthorized } = account;
  const [text, setText] = useState(post.textContent ?? '');
  const [image, setImage] = useState<File>();
  const [preview, setPreview] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [needsCheck, setNeedsCheck] = useState(false);
  const [error, setError] = useState('');
  const pending = useRef<AbortController | null>(null);
  const contentField = useRef<HTMLTextAreaElement>(null);
  const imageField = useRef<HTMLInputElement>(null);
  const cancelButton = useRef<HTMLButtonElement>(null);
  const imageHintId = useId();
  const hasImage = Boolean(post.imageReference);
  const canSaveContent = hasPostText(text) || hasImage;
  const canRemoveImage = hasImage && hasPostText(post.textContent);
  const title =
    action === 'content'
      ? 'Edit content'
      : action === 'delete'
        ? 'Delete post'
        : hasImage
          ? 'Edit image'
          : 'Add image';

  useEffect(() => () => pending.current?.abort(), []);

  useEffect(
    () => () => {
      if (preview) URL.revokeObjectURL(preview);
    },
    [preview],
  );

  function updateContent(updated: Post) {
    onUpdate({
      textContent: updated.textContent,
      imageReference: updated.imageReference,
    });
  }

  async function change(operation: 'save' | 'remove-image' | 'check') {
    if (pending.current || (needsCheck && operation !== 'check')) return;
    const controller = new AbortController();
    pending.current = controller;
    const { signal } = controller;
    setBusy(true);
    setError('');
    try {
      if (operation === 'check') {
        const current = await loadPost(post.id, accessToken, signal);
        if (signal.aborted) return;
        updateContent(current);
        setNeedsCheck(false);
        return;
      }
      if (action === 'delete') {
        await deletePost(post.id, accessToken, signal);
        if (!signal.aborted) onRemove(true);
        return;
      }
      const updated =
        action === 'content'
          ? await editPostContent(
              post.id,
              hasPostText(text) ? text : null,
              accessToken,
              signal,
            )
          : await editPostImage(
              post.id,
              operation === 'remove-image' ? null : image!,
              accessToken,
              signal,
            );
      if (signal.aborted) return;
      updateContent(updated);
      onClose();
    } catch (failure) {
      if (signal.aborted) return;
      const status = failure instanceof ApiError ? failure.status : undefined;
      const code = failure instanceof ApiError ? failure.code : undefined;
      if (status === 401) {
        onUnauthorized();
      } else if (
        status === 404 &&
        (operation === 'check' || code === 'POST_NOT_FOUND')
      ) {
        onRemove(false);
      } else if (status === 403) {
        setError('You no longer have permission to make this change.');
      } else if (operation === 'check') {
        setError('Couldn’t check the post. Try again.');
      } else if (code === 'STORAGE_UNAVAILABLE') {
        setError('Image uploads are unavailable. Try again later.');
      } else if (code === 'INVALID_IMAGE') {
        setError('Choose a valid JPEG or PNG image.');
      } else if (status === 413) {
        setError(
          action === 'content'
            ? 'This text is too long. Try shortening it.'
            : 'Choose an image up to 5 MB.',
        );
      } else if (status === 400 && action !== 'delete') {
        setError('Check your changes. A post needs text or an image.');
      } else {
        setError(
          'Couldn’t confirm the change. Check the post before trying again.',
        );
        setNeedsCheck(true);
      }
    } finally {
      pending.current = null;
      if (!signal.aborted) setBusy(false);
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (
      (action === 'content' && !canSaveContent) ||
      (action === 'image' && !image)
    )
      return;
    void change('save');
  }

  return (
    <FormDialog
      title={title}
      initialFocus={
        action === 'content'
          ? contentField
          : action === 'image'
            ? imageField
            : cancelButton
      }
      busy={busy}
      onClose={onClose}
      onSubmit={submit}
    >
      {action === 'content' && (
        <label>
          Post text
          <textarea
            rows={5}
            ref={contentField}
            value={text}
            readOnly={busy}
            onChange={(event) => setText(event.target.value)}
          />
        </label>
      )}
      {action === 'content' && !canSaveContent && (
        <p className="dialog-note">
          Add text before saving a post without an image.
        </p>
      )}
      {action === 'image' && (
        <>
          {preview ? (
            <div className="composer-image-preview">
              <img src={preview} alt="Selected image preview" />
            </div>
          ) : (
            post.imageReference && <PostImage reference={post.imageReference} />
          )}
          <label>
            {hasImage ? 'Choose a replacement' : 'Choose an image'}
            <input
              type="file"
              accept={postImageTypes.join(',')}
              aria-describedby={imageHintId}
              ref={imageField}
              disabled={busy}
              onChange={(event) => {
                const file = event.target.files?.[0];
                setError('');
                if (file && !isPostImage(file)) {
                  event.target.value = '';
                  setImage(undefined);
                  setPreview(undefined);
                  setError('Choose a JPEG or PNG image up to 5 MB.');
                } else {
                  setImage(file);
                  setPreview(file ? URL.createObjectURL(file) : undefined);
                }
              }}
            />
          </label>
          <p className="dialog-note" id={imageHintId}>
            One JPEG or PNG image, up to 5 MB.
            {hasImage &&
              !canRemoveImage &&
              ' Add text to the post before removing its only image.'}
          </p>
        </>
      )}
      {action === 'delete' && <p>Delete this post permanently?</p>}
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <div className="organization-form-actions">
        <button
          type="button"
          className="button dialog-cancel"
          ref={cancelButton}
          disabled={busy}
          onClick={onClose}
        >
          Cancel
        </button>
        {needsCheck ? (
          <button
            type="button"
            className="button primary"
            disabled={busy}
            onClick={() => void change('check')}
          >
            Check post
          </button>
        ) : (
          <>
            {action === 'image' && canRemoveImage && (
              <button
                type="button"
                className="button dialog-cancel post-delete-action"
                disabled={busy}
                onClick={() => void change('remove-image')}
              >
                Remove image
              </button>
            )}
            <button
              type="submit"
              className={
                action === 'delete'
                  ? 'button post-delete-confirm'
                  : 'button primary'
              }
              disabled={
                busy ||
                (action === 'content' && !canSaveContent) ||
                (action === 'image' && !image)
              }
            >
              {busy
                ? 'Working…'
                : action === 'delete'
                  ? 'Delete post'
                  : action === 'image' && !hasImage
                    ? 'Add image'
                    : 'Save changes'}
            </button>
          </>
        )}
      </div>
    </FormDialog>
  );
}
