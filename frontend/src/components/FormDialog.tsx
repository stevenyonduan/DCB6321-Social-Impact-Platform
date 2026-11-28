import {
  useEffect,
  useId,
  useRef,
  type FormEvent,
  type ReactNode,
  type RefObject,
} from 'react';

export function FormDialog({
  title,
  initialFocus,
  busy,
  onClose,
  onSubmit,
  className = 'post-editor',
  children,
}: {
  title: string;
  initialFocus: RefObject<HTMLElement | null>;
  busy: boolean;
  onClose: () => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  className?: string;
  children: ReactNode;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();

  useEffect(() => {
    const element = dialog.current;
    if (element && !element.open) element.showModal();
    initialFocus.current?.focus();
  }, [initialFocus]);

  return (
    <dialog
      ref={dialog}
      className={`preview-dialog organization-form-dialog ${className}`}
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault();
        if (!busy) onClose();
      }}
    >
      <h2 id={titleId}>{title}</h2>
      <form onSubmit={onSubmit}>{children}</form>
    </dialog>
  );
}
