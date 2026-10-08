import { createContext, useContext, type ReactNode } from 'react';
import type { SessionResponse } from '../api/auth';

/**
 * Who is logged in, for the components that need to know: the header, the admin screens,
 * and anything that hides a control an account's role cannot use.
 *
 * A context here and not in the token store, because the two answer different questions.
 * The store holds a credential and has to be readable from a plain module (the axios
 * interceptor); this holds what the server said about it, is only meaningful inside the
 * authenticated tree, and is fetched once per token — App.tsx remounts the tree on a new one.
 *
 * Hiding a control is a courtesy, never the protection: the server refuses the call either way.
 */
const SessionContext = createContext<SessionResponse | null>(null);

export function SessionProvider({ session, children }: { session: SessionResponse; children: ReactNode }) {
  return <SessionContext.Provider value={session}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionResponse {
  const session = useContext(SessionContext);
  if (!session) throw new Error('useSession outside SessionProvider');
  return session;
}
