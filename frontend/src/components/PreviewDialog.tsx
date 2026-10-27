import { useEffect, useRef } from 'react';
import { X } from 'lucide-react';

export function PreviewDialog({
  title,
  children,
  onClose,
}: {
  title: string;
  children: React.ReactNode;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    dialog.current?.showModal();
  }, []);
  return (
    <dialog
      ref={dialog}
      className="preview-dialog"
      aria-labelledby="preview-title"
      onClose={onClose}
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <button
        className="dialog-close"
        aria-label="Close preview"
        onClick={onClose}
      >
        <X aria-hidden="true" />
      </button>
      <span className="eyebrow">DEMO PREVIEW</span>
      <h2 id="preview-title">{title}</h2>
      {children}
      <button className="button primary" onClick={onClose}>
        Back to your community
      </button>
    </dialog>
  );
}
