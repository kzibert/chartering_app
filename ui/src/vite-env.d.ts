/// <reference types="vite/client" />

/**
 * Build-time configuration. Vite substitutes these at build, so they are baked into the
 * bundle — they are not secrets and cannot be changed after the fact without rebuilding.
 */
interface ImportMetaEnv {
  /**
   * Where the API lives, when it is not behind the same origin as this bundle.
   *
   * Empty (the default, and what compose and `npm run dev` both use) means calls go to
   * `/api/v1` on whatever host served the page — nginx proxies it locally, and a Render
   * static site with a rewrite rule does the same thing. Set it to the API's full origin
   * (`https://chartering-api.onrender.com`) when there is nothing in the middle to proxy,
   * and set CORS_ORIGINS on the API to this site's address to match.
   */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
