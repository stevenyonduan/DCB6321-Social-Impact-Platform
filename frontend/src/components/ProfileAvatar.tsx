import { useState, type ReactNode } from 'react';

function avatarSource(value?: string | null) {
  if (!value?.trim()) return undefined;
  try {
    const url = new URL(value, window.location.origin);
    if (url.protocol === 'https:' || url.protocol === 'http:') return url.href;
  } catch {
    return undefined;
  }
}

export function ProfileAvatar({
  avatarUrl,
  className,
  fallback,
}: {
  avatarUrl?: string | null;
  className: string;
  fallback: ReactNode;
}) {
  const [failedSrc, setFailedSrc] = useState<string>();
  const src = avatarSource(avatarUrl);

  return (
    <div className={`avatar ${className}`} aria-hidden="true">
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
    </div>
  );
}
