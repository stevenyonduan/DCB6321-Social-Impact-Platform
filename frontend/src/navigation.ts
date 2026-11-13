import { createContext } from 'react';

type OrganizationPage = `organizations/${string}`;
export type Page =
  'landing' | 'login' | 'signup' | 'home' | 'profile' | OrganizationPage;
export const NavigationContext = createContext<(page: Page) => void>(() => {});

export function isOrganizationPage(page: string): page is OrganizationPage {
  return /^organizations\/[\da-f]{8}-(?:[\da-f]{4}-){3}[\da-f]{12}$/i.test(
    page,
  );
}

export function isAccountPage(page: Page) {
  return page === 'home' || page === 'profile' || isOrganizationPage(page);
}

export function getPage(): Page {
  const path = window.location.pathname.replace(/\/$/, '');
  if (path === '/login') return 'login';
  if (path === '/signup') return 'signup';
  if (path === '/home') return 'home';
  if (path === '/profile') return 'profile';
  const route = path.slice(1);
  if (isOrganizationPage(route)) return route;
  return 'landing';
}
