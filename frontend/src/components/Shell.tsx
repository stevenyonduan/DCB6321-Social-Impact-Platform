import { ArrowUpRight, LogOut } from 'lucide-react';
import logo from '../assets/sow-logo.png';
import { PageLink } from '../components/PageLink';
import type { Page } from '../navigation';
import { isAccountPage } from '../navigation';

export function Header({
  page,
  signedIn,
  onLogout,
}: {
  page: Page;
  signedIn: boolean;
  onLogout: () => void;
}) {
  return (
    <header className="site-header">
      <div className="header-inner">
        <PageLink
          page={signedIn ? 'home' : 'landing'}
          className="brand"
          aria-label={signedIn ? 'SOW feed' : 'SOW home'}
        >
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
          ) : isAccountPage(page) ? (
            <>
              <div className="account-nav-links">
                <PageLink
                  page="home"
                  aria-current={page === 'home' ? 'page' : undefined}
                >
                  Feed
                </PageLink>
                <PageLink
                  page="profile"
                  aria-current={page === 'profile' ? 'page' : undefined}
                >
                  Profile
                </PageLink>
              </div>
              <button className="text-link logout" onClick={onLogout}>
                Log out <LogOut aria-hidden="true" />
              </button>
            </>
          ) : null}
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
