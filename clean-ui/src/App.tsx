import { Routes, Route, Navigate } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import Dashboard from './pages/Dashboard';
import VesselsPage from './pages/vessels/VesselsPage';
import CompaniesPage from './pages/companies/CompaniesPage';
import PeoplePage from './pages/people/PeoplePage';
import ContactsPage from './pages/contacts/ContactsPage';

export default function App() {
  return (
    <AppLayout>
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/vessels" element={<VesselsPage />} />
        <Route path="/companies" element={<CompaniesPage />} />
        <Route path="/people" element={<PeoplePage />} />
        <Route path="/contacts" element={<ContactsPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AppLayout>
  );
}
