import { Routes, Route, Navigate } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import Dashboard from './pages/Dashboard';
import VesselsPage from './pages/vessels/VesselsPage';
import CompaniesPage from './pages/companies/CompaniesPage';
import PeoplePage from './pages/people/PeoplePage';
import CirculationListsPage from './pages/circulationLists/CirculationListsPage';
import CircularsPage from './pages/circulars/CircularsPage';

export default function App() {
  return (
    <AppLayout>
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/vessels" element={<VesselsPage />} />
        <Route path="/companies" element={<CompaniesPage />} />
        <Route path="/people" element={<PeoplePage />} />
        {/* Contacts merged into People; keep old links working. */}
        <Route path="/contacts" element={<Navigate to="/people" replace />} />
        <Route path="/circulation-lists" element={<CirculationListsPage />} />
        {/* The single client-side email list became named, DB-backed lists. */}
        <Route path="/email-list" element={<Navigate to="/circulation-lists" replace />} />
        <Route path="/circulars" element={<CircularsPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AppLayout>
  );
}
