import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from 'react';
import { Minus, UserRound, Users } from 'lucide-react';
import { ApiError } from '../api/auth';
import {
  addOrganizationAdmin,
  loadOrganizationTeam,
  removeOrganizationAdmin,
  type OrganizationTeamMember,
} from '../api/organizations';
import { ProfileAvatar } from '../components/ProfileAvatar';

type AdminEditorProps = {
  accessToken: string;
  onUnauthorized: () => void;
};

export function EditOrganizationAdmins({
  accessToken,
  onUnauthorized,
}: AdminEditorProps) {
  const [open, setOpen] = useState(false);
  const [denied, setDenied] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const feedback = useRef<HTMLParagraphElement>(null);

  const deny = useCallback(() => {
    setOpen(false);
    setDenied(true);
    requestAnimationFrame(() => feedback.current?.focus());
  }, []);

  if (denied) {
    return (
      <p className="form-error" role="alert" ref={feedback} tabIndex={-1}>
        You do not have permission to edit organization admins.
      </p>
    );
  }

  return (
    <>
      <button
        ref={trigger}
        type="button"
        className="button primary"
        onClick={() => setOpen(true)}
      >
        <Users aria-hidden="true" /> Edit admins
      </button>
      {open && (
        <EditAdminsDialog
          accessToken={accessToken}
          onUnauthorized={onUnauthorized}
          onForbidden={deny}
          onClose={() => {
            setOpen(false);
            requestAnimationFrame(() => trigger.current?.focus());
          }}
        />
      )}
    </>
  );
}

function memberName(member: OrganizationTeamMember) {
  return `${member.firstName} ${member.lastName}`;
}

function EditAdminsDialog({
  accessToken,
  onUnauthorized,
  onClose,
  onForbidden,
}: AdminEditorProps & {
  onClose: () => void;
  onForbidden: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const emailField = useRef<HTMLInputElement>(null);
  const cancelButton = useRef<HTMLButtonElement>(null);
  const removeButton = useRef<HTMLButtonElement>(null);
  const pending = useRef<AbortController | null>(null);
  const [admins, setAdmins] = useState<OrganizationTeamMember[] | null>();
  const [loadAttempt, setLoadAttempt] = useState(0);
  const [selected, setSelected] = useState<OrganizationTeamMember>();
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');

  useEffect(() => {
    const element = dialog.current;
    if (element && !element.open) element.showModal();
    emailField.current?.focus();
    return () => pending.current?.abort();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void loadOrganizationTeam(accessToken, controller.signal).then(
      (team) => {
        if (!controller.signal.aborted)
          setAdmins(
            team.filter((member) => member.organizationRole === 'ADMIN'),
          );
      },
      (failure: unknown) => {
        if (controller.signal.aborted) return;
        if (failure instanceof ApiError && failure.status === 401) {
          onUnauthorized();
        } else if (failure instanceof ApiError && failure.status === 403) {
          onForbidden();
        } else {
          setAdmins(null);
        }
      },
    );
    return () => controller.abort();
  }, [accessToken, loadAttempt, onForbidden, onUnauthorized]);

  useEffect(() => {
    if (selected) cancelButton.current?.focus();
  }, [selected]);

  function reloadTeam() {
    setAdmins(undefined);
    setLoadAttempt((attempt) => attempt + 1);
  }

  function cancelRemoval() {
    setSelected(undefined);
    requestAnimationFrame(() => removeButton.current?.focus());
  }

  async function changeTeam(change: { email: string } | { userId: string }) {
    if (pending.current || !admins) return;
    const adding = 'email' in change;
    const controller = new AbortController();
    pending.current = controller;
    setBusy(true);
    setStatus('');
    setError('');
    try {
      if (adding) {
        await addOrganizationAdmin(
          change.email,
          accessToken,
          controller.signal,
        );
      } else {
        await removeOrganizationAdmin(
          change.userId,
          accessToken,
          controller.signal,
        );
      }
      if (controller.signal.aborted) return;
      if (adding) setEmail('');
      setStatus(adding ? 'Admin added.' : 'Admin removed.');
      reloadTeam();
    } catch (failure) {
      if (controller.signal.aborted) return;
      const httpStatus =
        failure instanceof ApiError ? failure.status : undefined;
      if (httpStatus === 401) {
        onUnauthorized();
      } else if (httpStatus === 403) {
        onForbidden();
      } else if (adding && httpStatus === 400) {
        setError('Check the email address and try again.');
      } else if (adding && httpStatus === 409) {
        setError(
          'This account couldn’t be added. Check the email and try again.',
        );
      } else if (httpStatus === 429) {
        setError('Too many attempts. Please wait before trying again.');
      } else {
        setError(
          !adding &&
            failure instanceof ApiError &&
            failure.code === 'ADMIN_NOT_FOUND'
            ? 'This admin is no longer available.'
            : adding
              ? 'The addition couldn’t be confirmed. Check the list before trying again.'
              : 'The removal couldn’t be confirmed. Check the list before trying again.',
        );
        reloadTeam();
      }
    } finally {
      pending.current = null;
      if (!controller.signal.aborted) {
        setSelected(undefined);
        setBusy(false);
        requestAnimationFrame(() => emailField.current?.focus());
      }
    }
  }

  function add(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending.current || !admins || selected) return;
    if (
      email.length > 255 ||
      !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) ||
      /\p{Cc}/u.test(email)
    ) {
      setStatus('');
      setError('Enter one email address of up to 255 characters.');
      emailField.current?.focus();
      return;
    }
    void changeTeam({ email });
  }

  return (
    <dialog
      ref={dialog}
      className="preview-dialog organization-form-dialog edit-admins-dialog"
      aria-labelledby="edit-admins-title"
      onCancel={(event) => {
        event.preventDefault();
        if (!pending.current) onClose();
      }}
    >
      <h2 id="edit-admins-title">Edit admins</h2>
      <section aria-labelledby="current-admins-title">
        <h3 id="current-admins-title">Current admins</h3>
        {admins === undefined ? (
          <p role="status">Loading admins…</p>
        ) : admins === null ? (
          <>
            <p className="form-error" role="alert">
              The admin list couldn’t be loaded.
            </p>
            <button
              className="button dialog-cancel"
              type="button"
              onClick={reloadTeam}
            >
              Try again
            </button>
          </>
        ) : admins.length === 0 ? (
          <p className="dialog-note">No admins yet.</p>
        ) : (
          <ul className="organization-admin-list">
            {admins.map((member) => (
              <li key={member.id}>
                <div className="organization-admin-row">
                  <ProfileAvatar
                    avatarUrl={member.avatarUrl}
                    className="organization-admin-avatar"
                    fallback={<UserRound />}
                  />
                  <div className="organization-admin-identity">
                    <p>{memberName(member)}</p>
                    {member.email && (
                      <p className="organization-admin-email">{member.email}</p>
                    )}
                  </div>
                  <button
                    className="button organization-admin-remove"
                    type="button"
                    aria-label={`Remove ${memberName(member)}`}
                    disabled={busy || Boolean(selected)}
                    onClick={(event) => {
                      removeButton.current = event.currentTarget;
                      setSelected(member);
                      setError('');
                      setStatus('');
                    }}
                  >
                    <Minus aria-hidden="true" />
                  </button>
                </div>
                {selected?.id === member.id && (
                  <div className="organization-admin-confirm">
                    <p>
                      Remove {memberName(member)}? Their admin access ends
                      immediately.
                    </p>
                    <div className="organization-form-actions">
                      <button
                        ref={cancelButton}
                        className="button dialog-cancel"
                        type="button"
                        disabled={busy}
                        onClick={cancelRemoval}
                      >
                        Cancel
                      </button>
                      <button
                        className="button primary"
                        type="button"
                        disabled={busy}
                        onClick={() => void changeTeam({ userId: member.id })}
                      >
                        {busy ? 'Removing…' : 'Remove admin'}
                      </button>
                    </div>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
      <form onSubmit={add} aria-labelledby="add-admin-title">
        <fieldset disabled={busy || Boolean(selected)}>
          <legend id="add-admin-title">Add an admin</legend>
          <p className="dialog-note" id="add-admin-note">
            Enter the email on their existing account. Admin access starts
            immediately.
          </p>
          <label>
            Email address
            <input
              ref={emailField}
              name="email"
              inputMode="email"
              autoCapitalize="none"
              spellCheck={false}
              aria-describedby="add-admin-note"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </label>
          <div className="organization-form-actions">
            <button type="submit" className="button primary" disabled={!admins}>
              {busy && !selected ? 'Adding…' : 'Add admin'}
            </button>
          </div>
        </fieldset>
      </form>
      {status && (
        <p className="organization-admin-success" role="status">
          {status}
        </p>
      )}
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <div className="organization-form-actions edit-admins-footer">
        <button
          type="button"
          className="button dialog-cancel"
          disabled={busy}
          onClick={onClose}
        >
          Done
        </button>
      </div>
    </dialog>
  );
}
