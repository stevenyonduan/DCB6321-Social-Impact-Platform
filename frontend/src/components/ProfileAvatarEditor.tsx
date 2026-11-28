import {
  useContext,
  useEffect,
  useRef,
  useState,
  type ChangeEvent,
  type FormEvent,
} from 'react';
import { Camera } from 'lucide-react';
import { ApiError, type Profile } from '../api/auth';
import { isManagedAvatar, updateAvatar } from '../api/avatar';
import { imageTypes, isUploadImage, maxImageBytes } from '../api/imageFiles';
import { OwnAvatarContext } from '../auth/ownAvatar';
import { ActionMenu } from './ActionMenu';
import { FormDialog } from './FormDialog';
import { ProfileAvatar } from './ProfileAvatar';

type Choice = { file: File; preview: string };

export function ProfileAvatarEditor({
  profile,
  accessToken,
  initials,
  onUnauthorized,
  onUpdated,
}: {
  profile: Profile;
  accessToken: string;
  initials: string;
  onUnauthorized: () => void;
  onUpdated: (updated: Profile) => void;
}) {
  const avatar = useContext(OwnAvatarContext);
  const [action, setAction] = useState<'upload' | 'remove'>();
  const [choice, setChoice] = useState<Choice>();
  const [decoded, setDecoded] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [feedback, setFeedback] = useState('');
  const pending = useRef<AbortController | null>(null);
  const previewUrl = useRef<string | undefined>(undefined);
  const trigger = useRef<HTMLButtonElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const previewImage = useRef<HTMLImageElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  const reference = profile.avatarUrl;
  const hasPhoto = Boolean(reference);

  useEffect(
    () => () => {
      pending.current?.abort();
      if (previewUrl.current) URL.revokeObjectURL(previewUrl.current);
    },
    [],
  );

  function clearPreview() {
    if (previewUrl.current) URL.revokeObjectURL(previewUrl.current);
    previewUrl.current = undefined;
    setChoice(undefined);
    setDecoded(undefined);
  }

  function pickPhoto(event: ChangeEvent<HTMLInputElement>) {
    const file = event.currentTarget.files?.[0];
    event.currentTarget.value = '';
    if (!file || pending.current) return;
    setAction('upload');
    setFeedback('');
    clearPreview();
    if (file.size > maxImageBytes) {
      setError('Choose a photo that is 5 MB or smaller.');
    } else if (!isUploadImage(file)) {
      setError('Choose a valid JPEG or PNG photo.');
    } else {
      setError('');
      const preview = URL.createObjectURL(file);
      previewUrl.current = preview;
      setChoice({ file, preview });
    }
  }

  function closeDialog() {
    clearPreview();
    setAction(undefined);
    setError('');
    requestAnimationFrame(() => trigger.current?.focus());
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const photo = action === 'upload' ? choice : undefined;
    const image = previewImage.current;
    if (
      pending.current ||
      !action ||
      (action === 'upload' && (!photo || !image || photo.preview !== decoded))
    )
      return;

    const controller = new AbortController();
    pending.current = controller;
    setBusy(true);
    setError('');
    try {
      const updated = await updateAvatar(
        photo && image ? { file: photo.file, image } : null,
        accessToken,
        controller.signal,
      );
      if (controller.signal.aborted) return;
      if (updated.id !== profile.id)
        throw new ApiError(200, 'INVALID_RESPONSE');
      if (photo) {
        if (!updated.avatarUrl || !isManagedAvatar(updated.avatarUrl))
          throw new ApiError(200, 'INVALID_RESPONSE');
        avatar?.rememberPhoto(updated.avatarUrl, photo.file);
      } else if (updated.avatarUrl !== null) {
        throw new ApiError(200, 'INVALID_RESPONSE');
      }
      onUpdated(updated);
      closeDialog();
      setFeedback(photo ? 'Profile photo saved.' : 'Profile photo removed.');
    } catch (failure) {
      if (controller.signal.aborted) return;
      if (failure instanceof ApiError && failure.status === 401) {
        onUnauthorized();
      } else if (failure instanceof ApiError && failure.status === 413) {
        setError('Choose a photo that is 5 MB or smaller.');
      } else if (failure instanceof ApiError && failure.status === 415) {
        setError('This photo couldn’t be saved. Choose another JPEG or PNG.');
      } else {
        setError(
          photo
            ? 'Couldn’t confirm the photo save. Try again.'
            : 'Couldn’t confirm the photo removal. Try again.',
        );
      }
    } finally {
      pending.current = null;
      if (!controller.signal.aborted) setBusy(false);
    }
  }

  const filePicker = (
    <input
      ref={input}
      type="file"
      accept={imageTypes.join(',')}
      aria-label="Select profile photo"
      hidden
      disabled={busy}
      onChange={pickPhoto}
    />
  );
  const camera = <Camera aria-hidden="true" />;

  return (
    <div className="profile-avatar-editor">
      <ProfileAvatar
        avatarUrl={reference}
        imageSrc={avatar?.src}
        className="profile-avatar"
        fallback={initials}
      />
      {hasPhoto ? (
        <ActionMenu
          label="Change profile photo"
          trigger={trigger}
          icon={camera}
          className="profile-avatar-menu"
          triggerClassName="profile-avatar-trigger"
          actions={[
            { label: 'Replace photo', select: () => input.current?.click() },
            {
              label: 'Remove photo',
              destructive: true,
              select: () => {
                setFeedback('');
                setAction('remove');
              },
            },
          ]}
        />
      ) : (
        <button
          ref={trigger}
          type="button"
          className="profile-avatar-trigger"
          aria-label="Add profile photo"
          onClick={() => input.current?.click()}
        >
          {camera}
        </button>
      )}
      <span className="profile-avatar-announcement" role="status">
        {feedback}
      </span>
      {action ? (
        <FormDialog
          title={
            action === 'remove'
              ? 'Remove profile photo?'
              : hasPhoto
                ? 'Replace profile photo'
                : 'Add profile photo'
          }
          className="avatar-photo-dialog"
          initialFocus={cancel}
          busy={busy}
          onClose={closeDialog}
          onSubmit={(event) => void save(event)}
        >
          <fieldset disabled={busy}>
            {action === 'remove' ? (
              <p>Your initials will appear on your profile.</p>
            ) : (
              <>
                {filePicker}
                {choice && (
                  <img
                    key={choice.preview}
                    ref={previewImage}
                    className="profile-photo-preview"
                    src={choice.preview}
                    alt="Circular profile photo preview"
                    onLoad={() => setDecoded(choice.preview)}
                    onError={() => {
                      clearPreview();
                      setError(
                        'This photo couldn’t be opened. Choose another.',
                      );
                    }}
                  />
                )}
              </>
            )}
            {error && (
              <p className="form-error" role="alert">
                {error}
              </p>
            )}
            <div className="profile-form-actions avatar-photo-actions">
              {action === 'upload' && (
                <button
                  type="button"
                  className="button"
                  onClick={() => input.current?.click()}
                >
                  Choose another
                </button>
              )}
              <button
                ref={cancel}
                type="button"
                className="button secondary"
                onClick={closeDialog}
              >
                Cancel
              </button>
              <button
                type="submit"
                className={
                  action === 'remove'
                    ? 'button post-delete-confirm'
                    : 'button primary'
                }
                disabled={
                  action === 'upload' && (!choice || decoded !== choice.preview)
                }
              >
                {busy
                  ? action === 'remove'
                    ? 'Removing…'
                    : 'Saving…'
                  : action === 'remove'
                    ? 'Remove photo'
                    : 'Save photo'}
              </button>
            </div>
          </fieldset>
        </FormDialog>
      ) : (
        filePicker
      )}
    </div>
  );
}
