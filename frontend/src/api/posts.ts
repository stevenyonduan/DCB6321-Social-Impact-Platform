import { ApiError, object, request } from './auth';

export type Post = {
  id: string;
  authorId: string;
  textContent: string | null;
  imageReference: string | null;
  createdAt: string;
  likeCount: number;
  commentCount: number;
  liked: boolean;
  saved: boolean;
};

export type PostComment = {
  id: string;
  postId: string;
  authorId: string;
  content: string;
  createdAt: string;
};

type Page<T> = { items: T[]; hasNext: boolean };
export const maxPostImageBytes = 5 * 1024 * 1024;
export const postImageTypes = ['image/jpeg', 'image/png'];
const maxTextBytes = 16_384;

function isId(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

function isTimestamp(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value));
}

function isCount(value: unknown) {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function isPost(value: unknown): value is Post {
  return (
    object(value) &&
    isId(value.id) &&
    isId(value.authorId) &&
    (value.textContent === null || typeof value.textContent === 'string') &&
    (value.imageReference === null ||
      typeof value.imageReference === 'string') &&
    isTimestamp(value.createdAt) &&
    isCount(value.likeCount) &&
    isCount(value.commentCount) &&
    typeof value.liked === 'boolean' &&
    typeof value.saved === 'boolean'
  );
}

function isComment(value: unknown, postId: string): value is PostComment {
  return (
    object(value) &&
    isId(value.id) &&
    value.postId === postId &&
    isId(value.authorId) &&
    typeof value.content === 'string' &&
    isTimestamp(value.createdAt)
  );
}

function headers(token: string) {
  return { Authorization: `Bearer ${token}`, Accept: 'application/json' };
}

function jsonBody(value: object) {
  const body = JSON.stringify(value);
  if (new TextEncoder().encode(body).length > maxTextBytes)
    throw new ApiError(413, 'REQUEST_TOO_LARGE');
  return body;
}

function readPage<T>(
  path: string,
  page: number,
  size: number,
  token: string,
  validate: (item: unknown) => item is T,
  signal: AbortSignal,
) {
  return request(
    `${path}?page=${page}&size=${size}`,
    { headers: headers(token) },
    (value): value is Page<T> =>
      object(value) &&
      value.page === page &&
      value.size === size &&
      typeof value.hasNext === 'boolean' &&
      Array.isArray(value.items) &&
      value.items.every(validate),
    signal,
  );
}

async function readCollection<T extends { id: string }>(
  path: string,
  token: string,
  validate: (item: unknown) => item is T,
  signal: AbortSignal,
) {
  const items = new Map<string, T>();
  for (let page = 0; page <= 10_000; page++) {
    const result = await readPage(path, page, 100, token, validate, signal);
    signal.throwIfAborted();
    for (const item of result.items) items.set(item.id, item);
    if (!result.hasNext) return [...items.values()];
  }
  throw new ApiError(200, 'INVALID_RESPONSE');
}

export function loadFeed(page: number, token: string, signal: AbortSignal) {
  return readPage('/posts', page, 20, token, isPost, signal);
}

export function loadSavedPosts(token: string, signal: AbortSignal) {
  return readCollection('/users/me/saved-posts', token, isPost, signal);
}

export function loadComments(
  postId: string,
  token: string,
  signal: AbortSignal,
) {
  return readCollection(
    `/posts/${encodeURIComponent(postId)}/comments`,
    token,
    (value): value is PostComment => isComment(value, postId),
    signal,
  );
}

export async function createPost(
  text: string,
  image: File | null,
  token: string,
  signal: AbortSignal,
) {
  const requestHeaders = headers(token);
  let body: string | FormData;
  if (image) {
    if (new TextEncoder().encode(text).length > maxTextBytes)
      throw new ApiError(413, 'REQUEST_TOO_LARGE');
    body = new FormData();
    body.append('image', image);
    if (text.trim()) body.append('textContent', text);
  } else {
    body = jsonBody({ textContent: text });
  }
  return request(
    '/posts',
    {
      method: 'POST',
      headers: image
        ? requestHeaders
        : { ...requestHeaders, 'Content-Type': 'application/json' },
      body,
    },
    isPost,
    signal,
  );
}

export async function createComment(
  postId: string,
  content: string,
  token: string,
  signal: AbortSignal,
) {
  return request(
    `/posts/${encodeURIComponent(postId)}/comments`,
    {
      method: 'POST',
      headers: { ...headers(token), 'Content-Type': 'application/json' },
      body: jsonBody({ content }),
    },
    (value): value is PostComment => isComment(value, postId),
    signal,
  );
}

export function updatePostInteraction(
  postId: string,
  kind: 'like' | 'save',
  enabled: boolean,
  token: string,
  signal: AbortSignal,
) {
  return request(
    `/posts/${encodeURIComponent(postId)}/${kind}`,
    { method: enabled ? 'PUT' : 'DELETE', headers: headers(token) },
    (value): value is undefined => value === undefined,
    signal,
  );
}
