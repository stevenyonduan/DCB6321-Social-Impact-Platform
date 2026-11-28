import { createContext, useEffect, useRef, useState } from 'react';
import { ApiError, type Profile } from '../api/auth';
import { isManagedAvatar, loadAvatar } from '../api/avatar';

type Avatar = {
  src?: string;
  rememberPhoto: (reference: string, file: File) => void;
};

export const OwnAvatarContext = createContext<Avatar | undefined>(undefined);

export function useOwnAvatar(
  account: { profile: Profile; accessToken: string } | undefined,
  onUnauthorized: () => void,
): Avatar {
  const userId = account?.profile.id;
  const reference = account?.profile.avatarUrl;
  const accessToken = account?.accessToken;
  const savedFile = useRef<{ userId: string; reference: string; file: File } | null>(null);
  const [photo, setPhoto] = useState<{ userId: string; reference: string; src: string }>();

  useEffect(() => {
    if (savedFile.current?.userId !== userId || savedFile.current?.reference !== reference)
      savedFile.current = null;
    if (!userId || !accessToken || !reference || !isManagedAvatar(reference))
      return;

    const controller = new AbortController();
    let objectUrl: string | undefined;
    const image = savedFile.current
      ? Promise.resolve(savedFile.current.file)
      : loadAvatar(accessToken, controller.signal);
    void image.then(
      (file) => {
        if (controller.signal.aborted) return;
        objectUrl = URL.createObjectURL(file);
        setPhoto({ userId, reference, src: objectUrl });
      },
      (failure: unknown) => {
        if (!controller.signal.aborted && failure instanceof ApiError && failure.status === 401)
          onUnauthorized();
      },
    );
    return () => {
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [userId, reference, accessToken, onUnauthorized]);

  return {
    src: photo?.userId === userId && photo?.reference === reference ? photo?.src : undefined,
    rememberPhoto: (nextReference, file) => {
      if (userId) savedFile.current = { userId, reference: nextReference, file };
    },
  };
}
