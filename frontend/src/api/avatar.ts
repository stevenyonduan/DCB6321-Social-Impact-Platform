import { ApiError, apiFetch, isProfile, request } from './auth';
import { imageTypes, maxImageBytes } from './imageFiles';

const avatarPath = '/users/me/avatar';
// Match the backend's decoder limits for the original upload.
const maxSourceDimension = 8192;
const maxSourcePixels = 16_000_000;
const normalizedAvatarEdge = 1024;
const managedReference =
  /^[a-z\d_-]{1,100}\/avatars\/[\da-f]{8}-(?:[\da-f]{4}-){3}[\da-f]{12}\/[\da-f]{8}-[\da-f]{4}-4[\da-f]{3}-[89ab][\da-f]{3}-[\da-f]{12}\.(?:jpg|png)$/i;

export function isManagedAvatar(value?: string | null) {
  return !!value && managedReference.test(value);
}

export async function loadAvatar(accessToken: string, signal: AbortSignal) {
  const response = await apiFetch(
    avatarPath,
    {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        Accept: [...imageTypes, 'application/json'].join(', '),
      },
    },
    signal,
  );
  if (!response.ok)
    throw new ApiError(
      response.status,
      response.status === 401 ? 'UNAUTHORIZED' : 'AVATAR_UNAVAILABLE',
    );

  const image = await response.blob();
  if (
    !imageTypes.includes(image.type) ||
    !image.size ||
    image.size > maxImageBytes
  )
    throw new ApiError(response.status, 'INVALID_RESPONSE');
  return image;
}

function sendAvatar(
  file: File | null,
  accessToken: string,
  signal: AbortSignal,
) {
  const body = file ? new FormData() : undefined;
  if (file && body) body.append('image', file);
  return request(
    avatarPath,
    {
      method: file ? 'PUT' : 'DELETE',
      headers: {
        Authorization: `Bearer ${accessToken}`,
        Accept: 'application/json',
      },
      body,
    },
    isProfile,
    signal,
  );
}

async function normalizePhoto(image: HTMLImageElement, file: File) {
  const { naturalWidth: width, naturalHeight: height } = image;
  const scale = Math.min(1, normalizedAvatarEdge / Math.max(width, height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(width * scale));
  canvas.height = Math.max(1, Math.round(height * scale));
  const context = canvas.getContext('2d');
  if (!context) throw new ApiError(415, 'AVATAR_PREPARATION_FAILED');
  context.drawImage(image, 0, 0, canvas.width, canvas.height);

  const blob = await new Promise<Blob | null>((resolve) =>
    canvas.toBlob(resolve, file.type, 0.9),
  );
  if (
    !blob ||
    blob.type !== file.type ||
    !blob.size ||
    blob.size > maxImageBytes
  )
    throw new ApiError(415, 'AVATAR_PREPARATION_FAILED');
  return new File([blob], file.name, { type: blob.type });
}

export async function updateAvatar(
  photo: { file: File; image: HTMLImageElement } | null,
  accessToken: string,
  signal: AbortSignal,
) {
  if (!photo) return sendAvatar(null, accessToken, signal);
  const { file, image } = photo;
  const { naturalWidth: width, naturalHeight: height } = image;
  const exceedsDimensions =
    Math.max(width, height) > maxSourceDimension ||
    width * height > maxSourcePixels;

  if (!exceedsDimensions) {
    try {
      return await sendAvatar(file, accessToken, signal);
    } catch (error) {
      // Only retry a definite rejection. A timeout may already have saved the photo.
      if (!(
        error instanceof ApiError &&
        error.status === 415 &&
        error.code === 'INVALID_IMAGE'
      ))
        throw error;
    }
  }

  signal.throwIfAborted();
  const normalized = await normalizePhoto(image, file);
  signal.throwIfAborted();
  return sendAvatar(normalized, accessToken, signal);
}
