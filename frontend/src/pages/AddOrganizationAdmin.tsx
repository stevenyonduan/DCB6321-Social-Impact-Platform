import { useEffect, useRef, useState, type FormEvent } from 'react';
import { UserPlus } from 'lucide-react';
import { ApiError } from '../api/auth';
import { addOrganizationAdmin } from '../api/organizations';

export function AddOrganizationAdmin({
  accessToken,
  onUnauthorized,
}: {
  accessToken: string;
  onUnauthorized: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [result, setResult] = useState<'added' | 'forbidden'>();
  const trigger = useRef<HTMLButtonElement>(null);
  const feedback = useRef<HTMLParagraphElement>(null);

  function close() {
    setOpen(false);
    requestAnimationFrame(() => trigger.current?.focus());
  }

  if (result === 'forbidden') {
    return (
      <p className="form-error" role="alert" ref={feedback} tabIndex={-1}>
        You do not have permission to add organization admins.
      </p>
    );
  }

  return (
    <div>
      <button
        ref={trigger}
        type="button"
        className="button primary"
        onClick={() => {
          setResult(undefined);
          setOpen(true);
        }}
      >
        <UserPlus aria-hidden="true" /> Add admin
      </button>
      {result === 'added' && (
        <p className="organization-admin-success" role="status">
          Admin added.
        </p>
      )}
      {open && (
        <AddAdminDialog
          accessToken={accessToken}
          onUnauthorized={onUnauthorized}
          onClose={close}
          onAdded={() => {
            setResult('added');
            close();
          }}
          onForbidden={() => {
            setOpen(false);
            setResult('forbidden');
            requestAnimationFrame(() => feedback.current?.focus());
          }}
        />
      )}
    </div>
  );
}

function AddAdminDialog({
  accessToken,
  onUnauthorized,
  onClose,
  onAdded,
  onForbidden,
}: {
  accessToken: string;
  onUnauthorized: () => void;
  onClose: () => void;
  onAdded: () => void;
  onForbidden: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const emailField = useRef<HTMLInputElement>(null);
  const pending = useRef<AbortController | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    const element = dialog.current;
    if (element && !element.open) element.showModal();
    emailField.current?.focus();
    return () => pending.current?.abort();
  }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending.current) return;
    const email = String(new FormData(event.currentTarget).get('email') ?? '');
    if (
      email.length > 255 ||
      !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) ||
      /\p{Cc}/u.test(email)
    ) {
      setError('Enter one email address of up to 255 characters.');
      emailField.current?.focus();
      return;
    }

    const controller = new AbortController();
    pending.current = controller;
    setBusy(true);
    setError('');
    try {
      await addOrganizationAdmin(email, accessToken, controller.signal);
      if (!controller.signal.aborted) onAdded();
    } catch (failure) {
      if (controller.signal.aborted) return;
      switch (failure instanceof ApiError ? failure.status : undefined) {
        case 400:
          setError('Check the email address and try again.');
          break;
        case 401:
          onUnauthorized();
          break;
        case 403:
          onForbidden();
          break;
        case 409:
          setError(
            'This account couldn’t be added. Check the email and try again.',
          );
          break;
        case 429:
          setError('Too many attempts. Please wait before trying again.');
          break;
        default:
          setError('The addition couldn’t be confirmed.');
      }
    } finally {
      pending.current = null;
      if (!controller.signal.aborted) setBusy(false);
    }
  }

  return (
    <dialog
      ref={dialog}
      className="preview-dialog organization-form-dialog"
      aria-labelledby="add-admin-title"
      aria-describedby="add-admin-note"
      onCancel={(event) => {
        event.preventDefault();
        if (!pending.current) onClose();
      }}
    >
      <h2 id="add-admin-title">Add admin</h2>
      <p className="dialog-note" id="add-admin-note">
        Enter the email on their existing account. Admin access starts
        immediately.
      </p>
      <form onSubmit={(event) => void submit(event)}>
        <label>
          Email address
          <input
            ref={emailField}
            name="email"
            inputMode="email"
            autoCapitalize="none"
            spellCheck={false}
            required
            disabled={busy}
          />
        </label>
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        <div className="organization-form-actions">
          <button
            type="button"
            className="button dialog-cancel"
            disabled={busy}
            onClick={onClose}
          >
            Cancel
          </button>
          <button type="submit" className="button primary" disabled={busy}>
            {busy ? 'Adding…' : 'Add admin'}
          </button>
        </div>
      </form>
    </dialog>
  );
}
