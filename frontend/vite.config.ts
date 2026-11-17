import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { loadEnv, type ProxyOptions } from 'vite';

const devPort = 5173;
const localApiTarget = 'http://127.0.0.1:8080';
const localDevOrigins = [
  `http://127.0.0.1:${devPort}`,
  `http://localhost:${devPort}`,
];

export default defineConfig(({ command, isPreview, mode }) => {
  const isDevServer = command === 'serve' && !isPreview;
  const devEnv = isDevServer
    ? loadEnv(mode, process.cwd(), 'DEV_API_TARGET')
    : {};
  const target =
    process.env.DEV_API_TARGET ?? devEnv.DEV_API_TARGET ?? localApiTarget;
  let apiProxy: string | ProxyOptions = target;

  if (isDevServer && target.startsWith('https://')) {
    apiProxy = {
      target,
      changeOrigin: true,
      configure(proxy) {
        proxy.on('proxyReq', (proxyRequest, request) => {
          const origin = request.headers.origin;
          // The browser calls Vite on the same origin. Forward that request as server-to-server.
          if (
            origin === `http://${request.headers.host}` &&
            localDevOrigins.includes(origin)
          ) {
            proxyRequest.removeHeader('origin');
          }
        });
      },
    };
  }

  return {
    plugins: [react()],
    server: {
      port: devPort,
      strictPort: true,
      proxy: { '/api': apiProxy },
    },
    test: { environment: 'node', include: ['src/**/*.test.{ts,tsx}'] },
  };
});
