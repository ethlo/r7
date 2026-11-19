import { defineConfig } from 'vite';

export default defineConfig({
    base: './', // Critical for embedding in sub-directories
    define: {
        'module': '{}',
        'global': 'globalThis'
    },
    // The server proxy is ignored in production builds, which is fine
    // because Zensical and the schema will ultimately live on the same domain.
    server: {
        proxy: {
            '/schemas': {
                target: 'https://r7.ethlo.com',
                changeOrigin: true,
                secure: false,
            }
        }
    }
});