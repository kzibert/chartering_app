import { Routes, Route, Navigate } from 'react-router-dom';
import { Spin } from 'antd';
import { useQuery } from '@tanstack/react-query';
import AppLayout from './components/AppLayout';
import Dashboard from './pages/Dashboard';
import VesselsPage from './pages/vessels/VesselsPage';
import CargoesPage from './pages/cargoes/CargoesPage';
import OpenFleetPage from './pages/openFleet/OpenFleetPage';
import MatchPage from './pages/match/MatchPage';
import CompaniesPage from './pages/companies/CompaniesPage';
import PeoplePage from './pages/people/PeoplePage';
import CirculationListsPage from './pages/circulationLists/CirculationListsPage';
import CircularsPage from './pages/circulars/CircularsPage';
import MailboxPage from './pages/mailbox/MailboxPage';
import AnalysisPage from './pages/analysis/AnalysisPage';
import HistoryPage from './pages/history/HistoryPage';
import SettingsPage from './pages/settings/SettingsPage';
import LoginPage from './pages/login/LoginPage';
import { useToken } from './auth/store';
import { authApi } from './api/auth';

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
  if (session.isError) return <LoginPage />;

  return (
    <AppLayout username={session.data?.username}>
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/cargoes" element={<CargoesPage />} />
        <Route path="/open-fleet" element={<OpenFleetPage />} />
        <Route path="/match" element={<MatchPage />} />
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
        <Route path="/history" element={<HistoryPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AppLayout>
  );
}
