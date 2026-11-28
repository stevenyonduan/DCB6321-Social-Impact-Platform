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
export {
  imageTypes as postImageTypes,
  isUploadImage as isPostImage,
} from './imageFiles';
const maxTextBytes = 16_384;

export function hasPostText(text: string | null) {
  if (!text) return false;
  // Match Java's whitespace and space-character checks, including ASCII separators.
  for (const character of text) {
    const code = character.charCodeAt(0);
    if ((code < 28 || code > 31) && !/[\t-\r\p{Z}]/u.test(character))
      return true;
  }
  return false;
}

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

function postPath(id: string) {
  return `/posts/${encodeURIComponent(id)}`;
}

function postRequest(
  id: string,
  init: RequestInit,
  signal: AbortSignal,
  suffix = '',
) {
  return request(
    postPath(id) + suffix,
    init,
    (value): value is Post => isPost(value) && value.id === id,
    signal,
  );
}

export function loadPost(id: string, token: string, signal: AbortSignal) {
  return postRequest(id, { headers: headers(token) }, signal);
}

export async function editPostContent(
  id: string,
  textContent: string | null,
  token: string,
  signal: AbortSignal,
) {
  return postRequest(
    id,
    {
      method: 'PATCH',
      headers: { ...headers(token), 'Content-Type': 'application/json' },
      body: jsonBody({ textContent }),
    },
    signal,
  );
}

export function editPostImage(
  id: string,
  image: File | null,
  token: string,
  signal: AbortSignal,
) {
  const body = image ? new FormData() : undefined;
  if (image) body?.append('image', image);
  return postRequest(
    id,
    {
      method: body ? 'PUT' : 'DELETE',
      headers: headers(token),
      body,
    },
    signal,
    '/image',
  );
}

export function deletePost(id: string, token: string, signal: AbortSignal) {
  return request(
    postPath(id),
    { method: 'DELETE', headers: headers(token) },
    (value): value is undefined => value === undefined,
    signal,
  );
}

export function deleteComment(
  postId: string,
  commentId: string,
  token: string,
  signal: AbortSignal,
) {
  return request(
    `${postPath(postId)}/comments/${encodeURIComponent(commentId)}`,
    { method: 'DELETE', headers: headers(token) },
    (value): value is undefined => value === undefined,
    signal,
  );
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
    if (hasPostText(text)) body.append('textContent', text);
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
