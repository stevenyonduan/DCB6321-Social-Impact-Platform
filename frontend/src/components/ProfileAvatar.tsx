import { useState, type ReactNode } from 'react';
import { isManagedAvatar } from '../api/avatar';

function avatarSource(value?: string | null) {
  if (!value?.trim() || isManagedAvatar(value)) return undefined;
  try {
    const url = new URL(value, window.location.origin);
    if (url.protocol === 'https:' || url.protocol === 'http:') return url.href;
  } catch {
    return undefined;
  }
}

export function ProfileAvatar({
  as: Element = 'div',
  avatarUrl,
  imageSrc,
  className,
  fallback,
}: {
  as?: 'div' | 'span';
  avatarUrl?: string | null;
  imageSrc?: string;
  className: string;
  fallback: ReactNode;
}) {
  const [failedSrc, setFailedSrc] = useState<string>();
  const src = imageSrc ?? avatarSource(avatarUrl);

  return (
    <Element className={`avatar ${className}`} aria-hidden="true">
      {src && src !== failedSrc ? (
        <img
          src={src}
          alt=""
          referrerPolicy="no-referrer"
          onError={() => setFailedSrc(src)}
        />
      ) : (
        fallback
      )}
    </Element>
  );
}
