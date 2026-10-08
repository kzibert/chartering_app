import { Routes, Route, Navigate } from 'react-router-dom';
import { Spin } from 'antd';
import { useQuery } from '@tanstack/react-query';
import AppLayout from './components/AppLayout';
import { ComposerProvider } from './compose/store';
import ComposerHost from './compose/ComposerHost';
import Dashboard from './pages/Dashboard';
import VesselsPage from './pages/vessels/VesselsPage';
import CargoesPage from './pages/cargoes/CargoesPage';
import OpenFleetPage from './pages/openFleet/OpenFleetPage';
import CompaniesPage from './pages/companies/CompaniesPage';
import PeoplePage from './pages/people/PeoplePage';
import CirculationListsPage from './pages/circulationLists/CirculationListsPage';
import CircularsPage from './pages/circulars/CircularsPage';
import MailboxPage from './pages/mailbox/MailboxPage';
import AnalysisPage from './pages/analysis/AnalysisPage';
import IntakePage from './pages/intake/IntakePage';
import HistoryPage from './pages/history/HistoryPage';
import FeedPage from './pages/feed/FeedPage';
import SettingsPage from './pages/settings/SettingsPage';
import LoginPage from './pages/login/LoginPage';
import ChangePasswordPage from './pages/login/ChangePasswordPage';
import UsersPage from './pages/admin/UsersPage';
import TenantsPage from './pages/admin/TenantsPage';
import { useToken } from './auth/store';
import { authApi, roleAtLeast } from './api/auth';
import { SessionProvider } from './auth/session';

export default function App() {
  const token = useToken();

  // No token: nothing else is mounted, so no query fires and no request goes out logged out.
  if (!token) return <LoginPage />;

  return <AuthenticatedApp key={token} />;
}

/**
 * Checks the stored token before mounting anything that queries.
 *
 * Worth the extra round trip: a token in localStorage may be expired, or signed with a key
 * the server no longer has (a restart without JWT_SECRET set does that). Rendering the
 * dashboard first would fire a handful of queries that all 401 at once and tear the screen
 * back down. One call, one spinner, and a rejected token drops straight to the login screen
 * — the interceptor clears it, and App re-renders without it.
 *
 * The `key={token}` on this component is what makes a new login a clean slate: React
 * remounts the subtree, so nothing from the previous session's render survives.
 */
function AuthenticatedApp() {
  const session = useQuery({
    queryKey: ['auth', 'me'],
    queryFn: authApi.me,
    retry: false,
    staleTime: Infinity,
  });

  if (session.isLoading) {
    return (
      <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center' }}>
        <Spin size="large" />
      </div>
    );
  }

  // A failure that was not a 401 (the server is down, say) still leaves us logged in with
  // nothing to show; the login screen is the honest place to wait for it to come back.
  if (session.isError || !session.data) return <LoginPage />;

  // The server refuses everything but this to such a session, so nothing else is mounted —
  // no query fires only to come back 403.
  if (session.data.mustChangePassword) {
    return <ChangePasswordPage username={session.data.username} />;
  }

  const isAdmin = roleAtLeast(session.data.role, 'TENANT_ADMIN');
  const isPlatformAdmin = session.data.role === 'PLATFORM_ADMIN';

  return (
    <SessionProvider session={session.data}>
      {/* Drafts sit above the layout, not inside a page: a reply half written stays half
          written while the rest of the app is used, until it is sent or discarded. */}
      <ComposerProvider>
        <AppLayout>
          <Routes>
            <Route path="/" element={<Dashboard />} />
            <Route path="/cargoes" element={<CargoesPage />} />
            <Route path="/open-fleet" element={<OpenFleetPage />} />
            {/* Match moved into the records it answers for: tonnage on a cargo's drawer, cargoes
                on a vessel's. Old bookmarks land on the cargoes, which is where it starts. */}
            <Route path="/match" element={<Navigate to="/cargoes" replace />} />
            <Route path="/vessels" element={<VesselsPage />} />
            <Route path="/companies" element={<CompaniesPage />} />
            <Route path="/people" element={<PeoplePage />} />
            {/* Contacts merged into People; keep old links working. */}
            <Route path="/contacts" element={<Navigate to="/people" replace />} />
            <Route path="/circulation-lists" element={<CirculationListsPage />} />
            {/* The single client-side email list became named, DB-backed lists. */}
            <Route path="/email-list" element={<Navigate to="/circulation-lists" replace />} />
            <Route path="/circulars" element={<CircularsPage />} />
            <Route path="/mailbox" element={<MailboxPage />} />
            {/* Registered whether or not ANALYSIS_ENABLED is on. The nav entry is hidden
                when it is off, but a bookmarked URL still has to land somewhere that
                explains itself rather than bouncing to the dashboard. */}
            <Route path="/analysis" element={<AnalysisPage />} />
            <Route path="/intake" element={<IntakePage />} />
            <Route path="/feed" element={<FeedPage />} />
            <Route path="/history" element={<HistoryPage />} />
            <Route path="/settings" element={<SettingsPage />} />
            {/* Only routed for an administrator; anybody else lands on the dashboard, and the
                server would refuse them anyway. */}
            {isAdmin && <Route path="/admin/users" element={<UsersPage />} />}
            {isPlatformAdmin && <Route path="/admin/tenants" element={<TenantsPage />} />}
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </AppLayout>
        <ComposerHost />
      </ComposerProvider>
    </SessionProvider>
  );
}
