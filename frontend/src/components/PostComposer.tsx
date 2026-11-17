import {
  useEffect,
  useId,
  useRef,
  useState,
  type ChangeEvent,
  type FormEvent,
} from 'react';
import { ApiError } from '../api/auth';
import {
  createPost,
  maxPostImageBytes,
  postImageTypes,
  type Post,
} from '../api/posts';
import { CommunityAvatar, type CommunityAccount } from './CommunityPost';

export function PostComposer({
  account,
  onPosted,
}: {
  account: CommunityAccount;
  onPosted: (post: Post) => void;
}) {
  const { profile, accessToken, onUnauthorized } = account;
  const [expanded, setExpanded] = useState(false);
  const [text, setText] = useState('');
  const [image, setImage] = useState<File | null>(null);
  const [preview, setPreview] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [imageError, setImageError] = useState('');
  const imageHint = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  const textArea = useRef<HTMLTextAreaElement>(null);
  const picker = useRef<HTMLInputElement>(null);
  const pending = useRef<AbortController | null>(null);

  useEffect(() => () => pending.current?.abort(), []);
  useEffect(() => {
    if (expanded) textArea.current?.focus();
  }, [expanded]);
  useEffect(
    () => () => {
      if (preview) URL.revokeObjectURL(preview);
    },
    [preview],
  );

  function chooseImage(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    if (
      !postImageTypes.includes(file.type) ||
      file.size === 0 ||
      file.size > maxPostImageBytes
    ) {
      event.target.value = '';
      setImage(null);
      setPreview('');
      setImageError('Choose a JPEG or PNG image up to 5 MB.');
      return;
    }
    setImageError('');
    setImage(file);
    setPreview(URL.createObjectURL(file));
  }

  function close() {
    setExpanded(false);
    setText('');
    setImage(null);
    setPreview('');
    setError('');
    setImageError('');
    requestAnimationFrame(() => trigger.current?.focus());
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending.current || (!text.trim() && !image)) return;
    const controller = new AbortController();
    pending.current = controller;
    setSaving(true);
    setError('');
    try {
      const post = await createPost(
        text,
        image,
        accessToken,
        controller.signal,
      );
      if (controller.signal.aborted) return;
      onPosted(post);
      close();
    } catch (failure) {
      if (controller.signal.aborted) return;
      if (failure instanceof ApiError && failure.status === 401)
        onUnauthorized();
      else if (
        failure instanceof ApiError &&
        failure.code === 'STORAGE_UNAVAILABLE'
      )
        setError(
          'Image uploads are unavailable. Remove the image to post text, or try again later.',
        );
      else if (failure instanceof ApiError && failure.code === 'INVALID_IMAGE')
        setError(
          'This image couldn’t be uploaded. Try a different JPEG or PNG.',
        );
      else if (failure instanceof ApiError && failure.status === 413)
        setError(
          'This post is too large. Try shortening the text or using a smaller image.',
        );
      else setError('Couldn’t confirm your post. Your draft is still here.');
    } finally {
      pending.current = null;
      if (!controller.signal.aborted) setSaving(false);
    }
  }

  return (
    <div className={expanded ? 'composer composer-expanded' : 'composer'}>
      <CommunityAvatar profile={profile} authorId={profile.id} />
      {expanded ? (
        <form onSubmit={(event) => void submit(event)}>
          <label>
            Write a post
            <textarea
              ref={textArea}
              rows={3}
              value={text}
              readOnly={saving}
              onChange={(event) => setText(event.target.value)}
            />
          </label>
          <label className="composer-image-field">
            Add image (optional)
            <input
              ref={picker}
              type="file"
              accept={postImageTypes.join(',')}
              aria-describedby={imageHint}
              disabled={saving}
              onChange={chooseImage}
            />
          </label>
          <p className="composer-image-hint" id={imageHint}>
            One JPEG or PNG image, up to 5 MB.
          </p>
          {image && (
            <div className="composer-image-preview">
              <img src={preview} alt="Selected image preview" />
              <button
                type="button"
                className="text-link"
                disabled={saving}
                onClick={() => {
                  if (picker.current) picker.current.value = '';
                  setImage(null);
                  setPreview('');
                  setImageError('');
                }}
              >
                Remove image
              </button>
            </div>
          )}
          {imageError && (
            <p className="form-error" role="alert">
              {imageError}
            </p>
          )}
          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}
          <div className="profile-form-actions composer-actions">
            <button
              className="button dialog-cancel"
              type="button"
              disabled={saving}
              onClick={close}
            >
              Cancel
            </button>
            <button
              className="button primary"
              type="submit"
              disabled={saving || (!text.trim() && !image)}
            >
              {saving ? 'Posting…' : 'Post'}
            </button>
          </div>
        </form>
      ) : (
        <button
          className="composer-trigger"
          type="button"
          ref={trigger}
          onClick={() => setExpanded(true)}
        >
          Share a little good with your community…
        </button>
      )}
    </div>
  );
}
