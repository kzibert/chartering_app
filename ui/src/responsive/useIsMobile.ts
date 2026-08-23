import { useSyncExternalStore } from 'react';

/**
 * Where the phone layout starts, in pixels.
 *
 * Deliberately the same number as antd's `md` breakpoint. Every filter form in this app is
 * already laid out with `xs`/`md` Cols, so a hook that drew the line anywhere else would
 * open a band of widths where the fields had switched to their narrow layout but the shell
 * around them had not — or the reverse, which looks worse.
 */
export const MOBILE_BREAKPOINT = 768;

const QUERY = `(max-width: ${MOBILE_BREAKPOINT - 1}px)`;

function subscribe(onChange: () => void) {
  const mql = window.matchMedia(QUERY);
  mql.addEventListener('change', onChange);
  return () => mql.removeEventListener('change', onChange);
}

const narrow = () => window.matchMedia(QUERY).matches;

/**
 * True on phone-sized viewports.
 *
 * Viewport width, not the user agent. A UA string is a guess about a device and it gets
 * both cases that matter here wrong: a tablet held in portrait wants the narrow layout,
 * and so does a desktop window dragged narrow — which is how this gets developed and
 * tested. Width is the thing the layout actually cares about, so width is what is asked.
 *
 * useSyncExternalStore rather than an effect, so the very first render already knows which
 * shell to paint. With an effect the desktop layout renders once and is then torn down and
 * replaced, which on a phone is a visible flash of a sidebar that should never have been
 * there — and, worse, a round of mount/unmount for every table on the page.
 */
export function useIsMobile(): boolean {
  return useSyncExternalStore(subscribe, narrow, () => false);
}
