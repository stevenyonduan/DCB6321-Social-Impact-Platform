import { createContext } from 'react';

export type Page = 'landing' | 'login' | 'signup' | 'home';
export const NavigationContext = createContext<(page: Page) => void>(() => {});

export function getPage(): Page {
  const path = window.location.pathname.replace(/\/$/, '');
  if (path === '/login') return 'login';
  if (path === '/signup') return 'signup';
  if (path === '/home') return 'home';
  return 'landing';
}
