import { createMock } from './server';
import type { Database } from './fixtures';
const key = 'utc-frontend-mock-v1';
let saved: Database | undefined;
try { const value = localStorage.getItem(key); if (value) saved = JSON.parse(value); } catch { /* Start with seed when storage is unavailable. */ }
export const mock = createMock(saved, database => { try { localStorage.setItem(key, JSON.stringify(database)); } catch { /* The current in-memory session still works. */ } });
export function installMock() {
  const original = window.fetch.bind(window);
  window.fetch = (input, init) => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, window.location.origin);
    if (url.pathname.startsWith('/api/')) {
      if (!url.pathname.startsWith('/api/v1/')) return Promise.resolve(new Response('Unknown mock API', { status: 404 }));
      return mock.fetch(input, init);
    }
    return original(input, init);
  };
}
