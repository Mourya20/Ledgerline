import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './context/AuthContext';
import { ProtectedRoute } from './components/ProtectedRoute';
import { PublicRoute } from './components/PublicRoute';
import { AppLayout } from './components/layout/AppLayout';
import { SignInPage } from './pages/SignInPage';
import { RegisterPage } from './pages/RegisterPage';
import { DashboardPage } from './pages/DashboardPage';
import { AccountsPage } from './pages/AccountsPage';
import { MoveMoneyPage } from './pages/MoveMoneyPage';
import { HistoryPage } from './pages/HistoryPage';
import { AuditorPage } from './pages/AuditorPage';
import { AdminPage } from './pages/AdminPage';
import { AccessDeniedPage } from './pages/AccessDeniedPage';

const RootRedirect: React.FC = () => {
  const { user, isAuthenticated, isLoading } = useAuth();

  if (isLoading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh', color: '#6B7280' }}>
        Loading Ledgerline...
      </div>
    );
  }

  if (!isAuthenticated || !user) {
    return <Navigate to="/login" replace />;
  }

  if (user.role === 'AUDITOR') {
    return <Navigate to="/auditor" replace />;
  }
  if (user.role === 'ADMIN') {
    return <Navigate to="/admin" replace />;
  }
  if (user.role === 'CUSTOMER') {
    return <Navigate to="/dashboard" replace />;
  }

  return <Navigate to="/access-denied" replace />;
};

export const App: React.FC = () => {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          {/* Public Authentication Routes */}
          <Route
            path="/login"
            element={
              <PublicRoute>
                <SignInPage />
              </PublicRoute>
            }
          />
          <Route
            path="/register"
            element={
              <PublicRoute>
                <RegisterPage />
              </PublicRoute>
            }
          />

          {/* Access Denied Route */}
          <Route path="/access-denied" element={<AccessDeniedPage />} />

          {/* Protected Customer Banking Routes with AppLayout Shell */}
          <Route
            element={
              <ProtectedRoute allowedRoles={['CUSTOMER']}>
                <AppLayout />
              </ProtectedRoute>
            }
          >
            <Route path="/dashboard" element={<DashboardPage />} />
            <Route path="/accounts" element={<AccountsPage />} />
            <Route path="/move-money" element={<MoveMoneyPage />} />
            <Route path="/history" element={<HistoryPage />} />
          </Route>

          {/* Protected Auditor Portal Route */}
          <Route
            path="/auditor"
            element={
              <ProtectedRoute allowedRoles={['AUDITOR', 'ADMIN']}>
                <AuditorPage />
              </ProtectedRoute>
            }
          />

          {/* Protected Admin Console Route */}
          <Route
            path="/admin"
            element={
              <ProtectedRoute allowedRoles={['ADMIN']}>
                <AdminPage />
              </ProtectedRoute>
            }
          />

          {/* Fallback & Root Redirects */}
          <Route path="/" element={<RootRedirect />} />
          <Route path="*" element={<RootRedirect />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
};

export default App;
