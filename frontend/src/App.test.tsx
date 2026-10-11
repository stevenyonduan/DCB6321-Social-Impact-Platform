import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import App from './App';

describe('starter application', () => {
  it('renders an accessible main landmark and project heading', () => {
    const html = renderToStaticMarkup(<App />);
    expect(html).toContain('<main>');
    expect(html).toContain('<h1>Social Impact Platform</h1>');
  });
});
