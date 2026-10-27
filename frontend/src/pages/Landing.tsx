import {
  ArrowRight,
  ArrowDown,
  MapPin,
  Compass,
  Users,
  Heart,
  Leaf,
  Utensils,
  BookOpen,
} from 'lucide-react';
import { PageLink } from '../components/PageLink';
import hero from '../assets/volunteers-donations.jpg';

export function Landing() {
  return (
    <>
      <section className="landing-hero">
        <img
          className="hero-photo"
          src={hero}
          alt="Volunteers sharing water and supplies with their neighbors outdoors"
          fetchPriority="high"
        />
        <div className="hero-inner">
          <div className="hero-copy">
            <div className="eyebrow">
              CONNECT <span>•</span> SERVE <span>•</span> IMPACT
            </div>
            <h1>
              Good things
              <br />
              happen <em>together.</em>
            </h1>
            <p>
              Find a cause you care about. Meet people who care, too. Make a
              difference in your community, one shared experience at a time.
            </p>
            <div className="hero-actions">
              <PageLink page="signup" className="button primary">
                Join SOW <ArrowRight aria-hidden="true" />
              </PageLink>
              <a href="#how-it-works" className="text-link">
                See how it works <ArrowDown aria-hidden="true" />
              </a>
            </div>
            <div className="hero-note">
              <MapPin aria-hidden="true" /> A little closer to your community.
              Starting in Atlanta.
            </div>
          </div>
        </div>
      </section>
      <section id="how-it-works" className="how-section content-width">
        <div className="section-heading">
          <span className="eyebrow">SMALL STEPS. SHARED PURPOSE.</span>
          <h2>Your time can mean so much.</h2>
        </div>
        <div className="steps">
          <article>
            <Compass aria-hidden="true" />
            <h3>Connect with a cause.</h3>
            <p>
              Discover local opportunities and organizations that speak to you.
            </p>
          </article>
          <article>
            <Users aria-hidden="true" />
            <h3>Serve with others.</h3>
            <p>
              Turn a shared interest into a shared experience. Show up together.
            </p>
          </article>
          <article>
            <Heart aria-hidden="true" />
            <h3>Create an impact.</h3>
            <p>See how the time you give helps your community grow stronger.</p>
          </article>
        </div>
      </section>
      <section id="find-your-cause" className="cause-section">
        <div className="content-width cause-inner">
          <div>
            <span className="eyebrow">THERE’S A PLACE FOR YOU HERE.</span>
            <h2>Find what moves you.</h2>
            <p>
              However you choose to give back, start with something you care
              about.
            </p>
          </div>
          <div className="cause-list">
            <span>
              <Leaf aria-hidden="true" />
              The environment
            </span>
            <span>
              <Utensils aria-hidden="true" />
              Food & community
            </span>
            <span>
              <BookOpen aria-hidden="true" />
              Learning & mentorship
            </span>
          </div>
        </div>
      </section>
      <section className="invite-section content-width">
        <h2>Make room for a little good.</h2>
        <PageLink page="signup" className="button primary">
          Join the community <ArrowRight aria-hidden="true" />
        </PageLink>
      </section>
    </>
  );
}
