import { useEffect, useRef, useState } from 'react';
import { Header, Footer } from './components/Shell';
import { Landing } from './pages/Landing';
import { Account } from './pages/Account';
import { Home } from './pages/Home';
import {
  readDemoSession,
  startDemoSession,
  endDemoSession,
} from './demo/session';
import type { Page } from './navigation';
import { getPage, NavigationContext } from './navigation';

export default function App() {
  const [page, setPage] = useState<Page>(() => getPage());
  const [session, setSession] = useState(readDemoSession);
  const main = useRef<HTMLElement>(null);
  const visiblePage = page === 'home' && !session ? 'login' : page;

  useEffect(() => {
    const onPopState = () => setPage(getPage());
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  useEffect(() => {
    if (page === 'home' && !session) {
      window.history.replaceState(null, '', '/login');
    }
    document.title = `${visiblePage === 'landing' ? 'Making Service Social' : visiblePage === 'home' ? 'Your community' : visiblePage === 'signup' ? 'Join the community' : 'Welcome back'} | SOW`;
    main.current?.focus();
    window.scrollTo(0, 0);
  }, [page, session, visiblePage]);

  function navigate(next: Page) {
    window.history.pushState(null, '', next === 'landing' ? '/' : `/${next}`);
    setPage(next);
  }

  function enterDemo(displayName: string) {
    setSession(startDemoSession(displayName));
    navigate('home');
  }

  function logout() {
    endDemoSession();
    setSession(null);
    navigate('landing');
  }

  return (
    <NavigationContext value={navigate}>
      <a className="skip-link" href="#main-content">
        Skip to content
      </a>
      <Header page={visiblePage} onLogout={logout} />
      <main id="main-content" ref={main} tabIndex={-1}>
        {visiblePage === 'landing' && <Landing />}
        {(visiblePage === 'login' || visiblePage === 'signup') && (
          <Account key={visiblePage} mode={visiblePage} onEnter={enterDemo} />
        )}
        {visiblePage === 'home' && session && (
          <Home name={session.displayName} />
        )}
      </main>
      <Footer />
    </NavigationContext>
  );
}
