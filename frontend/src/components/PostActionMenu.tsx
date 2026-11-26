import {
  useEffect,
  useId,
  useRef,
  useState,
  type ReactNode,
  type RefObject,
} from 'react';
import { Ellipsis } from 'lucide-react';

type Action = {
  label: string;
  select: () => void;
  destructive?: boolean;
};

export function PostActionMenu({
  label,
  trigger,
  actions,
  onOpen,
  children,
}: {
  label: string;
  trigger: RefObject<HTMLButtonElement | null>;
  actions: Action[];
  onOpen?: () => void;
  children?: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const group = useRef<HTMLDivElement>(null);
  const firstAction = useRef<HTMLButtonElement>(null);
  const actionsId = useId();

  useEffect(() => {
    if (!open) return;
    firstAction.current?.parentElement?.scrollIntoView?.({
      block: 'nearest',
      behavior: 'instant',
    });
    firstAction.current?.focus({ preventScroll: true });
    function closeOutside(event: PointerEvent) {
      if (
        event.target instanceof Node &&
        !group.current?.contains(event.target)
      )
        setOpen(false);
    }
    document.addEventListener('pointerdown', closeOutside);
    return () => document.removeEventListener('pointerdown', closeOutside);
  }, [open]);

  function closeMenu() {
    setOpen(false);
    trigger.current?.focus({ preventScroll: true });
  }

  return (
    <div
      className="post-options"
      ref={group}
      onBlur={(event) => {
        if (
          event.relatedTarget instanceof Node &&
          !event.currentTarget.contains(event.relatedTarget)
        )
          setOpen(false);
      }}
      onKeyDown={(event) => {
        if (open && event.key === 'Escape') {
          event.preventDefault();
          closeMenu();
        }
      }}
    >
      <button
        type="button"
        className="post-options-trigger"
        aria-label={label}
        aria-expanded={open}
        aria-controls={open ? actionsId : undefined}
        ref={trigger}
        onClick={() => {
          if (!open) onOpen?.();
          setOpen(!open);
        }}
      >
        <Ellipsis aria-hidden="true" />
      </button>
      {open && (
        <div className="post-options-list" id={actionsId}>
          {actions.map(({ label, select, destructive }, index) => (
            <button
              key={label}
              type="button"
              ref={index === 0 ? firstAction : undefined}
              className={destructive ? 'post-delete-action' : undefined}
              onClick={() => {
                closeMenu();
                select();
              }}
            >
              {label}
            </button>
          ))}
        </div>
      )}
      {children}
    </div>
  );
}
