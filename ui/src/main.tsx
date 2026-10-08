import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntApp, ConfigProvider } from 'antd';
import App from './App';
import { onOwnerChange } from './auth/store';
import './index.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { refetchOnWindowFocus: false, retry: 1 },
  },
});

// Every cached answer is one person's. Logout already cleared it, but a session that expires
// (the 401 path) or ends in another tab did not — and `['auth', 'me']` is cached forever, so
// the next login rendered with the previous person's session, desk and figures until something
// happened to refetch. Cleared here, on the store's own signal, whichever way the login changed.
onOwnerChange(() => queryClient.clear());

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <ConfigProvider>
        {/* Circulation lists live in the database now, so react-query is the only store
            they need — the old EmailListProvider context is gone with them. */}
        <AntApp>
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </AntApp>
      </ConfigProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
