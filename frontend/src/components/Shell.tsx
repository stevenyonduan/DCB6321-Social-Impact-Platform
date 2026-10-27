import { ArrowUpRight, LogOut } from 'lucide-react';
import logo from '../assets/sow-logo.png';
import { PageLink } from '../components/PageLink';
import type { Page } from '../navigation';

export function Header({
  page,
  onLogout,
}: {
  page: Page;
  onLogout: () => void;
}) {
  return (
    <header className="site-header">
      <div className="header-inner">
        <PageLink page="landing" className="brand" aria-label="SOW home">
          <img src={logo} alt="SOW" width="64" height="64" />
          <span>
            Making Service
            <br />
            Social.
          </span>
        </PageLink>
        <nav aria-label="Main navigation">
          {page === 'landing' ? (
            <>
              <a className="section-link" href="#how-it-works">
                How it works
              </a>
              <a className="section-link" href="#find-your-cause">
                Find your cause
              </a>
              <PageLink page="login" className="text-link">
                Log in
              </PageLink>
              <PageLink page="signup" className="button primary">
                Join SOW <ArrowUpRight aria-hidden="true" />
              </PageLink>
            </>
          ) : (
            <>
              {page === 'home' && (
                <button className="text-link logout" onClick={onLogout}>
                  Log out <LogOut aria-hidden="true" />
                </button>
              )}
            </>
          )}
        </nav>
      </div>
    </header>
  );
}

export function Footer() {
  return (
    <footer className="site-footer">
      <div>
        <span>
          <strong>SOW</strong>{' '}
          <span className="footer-tag">Connect. Serve. Impact.</span>
        </span>
      </div>
    </footer>
  );
}
