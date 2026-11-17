import { useEffect, useState } from 'react';
import { authClient } from '../auth/client';

export function PostImage({ reference }: { reference: string }) {
  const [src, setSrc] = useState<string | null>();
  const [publicFailed, setPublicFailed] = useState(false);
  useEffect(() => {
    const match =
      /^([A-Za-z0-9_-]{1,100})\/(posts\/[a-f0-9-]{36}\.(?:png|jpg))$/i.exec(
        reference,
      );
    const controller = new AbortController();
    let objectUrl: string | undefined;

    async function load() {
      if (!match) throw new Error();
      const [, bucket, path] = match;
      const storage = authClient().storage.from(bucket);
      if (!publicFailed) return storage.getPublicUrl(path).data.publicUrl;
      const { data, error } = await storage.download(
        path,
        {},
        { signal: controller.signal },
      );
      if (error || !data || !['image/jpeg', 'image/png'].includes(data.type))
        throw new Error();
      controller.signal.throwIfAborted();
      objectUrl = URL.createObjectURL(data);
      return objectUrl;
    }

    void load().then(
      (url) => {
        if (!controller.signal.aborted) setSrc(url);
      },
      () => {
        if (!controller.signal.aborted) setSrc(null);
      },
    );
    return () => {
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [reference, publicFailed]);

  if (src === undefined) return null;
  if (src === null)
    return <p className="post-media-note">Image unavailable.</p>;
  return (
    <img
      className="post-image"
      src={src}
      alt="Image attached to this post"
      loading="lazy"
      referrerPolicy="no-referrer"
      onError={() => {
        if (publicFailed) setSrc(null);
        else {
          setSrc(undefined);
          setPublicFailed(true);
        }
      }}
    />
  );
}
