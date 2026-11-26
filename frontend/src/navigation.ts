import { createContext } from 'react';

type OrganizationPage = `organizations/${string}`;
export type PostPage = `posts/${string}`;
export type Page =
  | 'landing'
  | 'login'
  | 'signup'
  | 'home'
  | 'events'
  | 'profile'
  | 'profile/saved-posts'
  | OrganizationPage
  | PostPage;
export const NavigationContext = createContext<(page: Page) => void>(() => {});

export function isOrganizationPage(page: string): page is OrganizationPage {
  return /^organizations\/[\da-f]{8}-(?:[\da-f]{4}-){3}[\da-f]{12}$/i.test(
    page,
  );
}

export function isPostPage(page: string): page is PostPage {
  return /^posts\/[\da-f]{8}-(?:[\da-f]{4}-){3}[\da-f]{12}$/i.test(page);
}

export function isAccountPage(page: Page) {
  return (
    page === 'home' ||
    page === 'events' ||
    page === 'profile' ||
    page === 'profile/saved-posts' ||
    isOrganizationPage(page) ||
    isPostPage(page)
  );
}

export function getPage(): Page {
  const path = window.location.pathname.replace(/\/$/, '');
  if (path === '/login') return 'login';
  if (path === '/signup') return 'signup';
  if (path === '/home') return 'home';
  if (path === '/events') return 'events';
  if (path === '/profile') return 'profile';
  if (path === '/profile/saved-posts') return 'profile/saved-posts';
  const route = path.slice(1);
  if (isOrganizationPage(route) || isPostPage(route)) return route;
  return 'landing';
}
