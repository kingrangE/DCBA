import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './context/AuthContext'
import AdminPage from './pages/AdminPage'
import DashboardPage from './pages/DashboardPage'
import GenerationRequestsPage from './pages/GenerationRequestsPage'
import LoginPage from './pages/LoginPage'
import MyPage from './pages/MyPage'
import SignupPage from './pages/SignupPage'

function ProtectedRoute({ children, admin = false }) {
  const { user, loading } = useAuth()
  if (loading) return <div className="screen-loader">DCBA를 불러오는 중...</div>
  if (!user) return <Navigate to="/login" replace />
  if (admin && user.admin !== true) return <Navigate to="/dashboard" replace />
  return children
}

function GuestRoute({ children }) {
  const { user, loading } = useAuth()
  if (loading) return <div className="screen-loader">DCBA를 불러오는 중...</div>
  return user ? <Navigate to="/dashboard" replace /> : children
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<GuestRoute><LoginPage /></GuestRoute>} />
      <Route path="/signup" element={<GuestRoute><SignupPage /></GuestRoute>} />
      <Route path="/dashboard" element={<ProtectedRoute><DashboardPage /></ProtectedRoute>} />
      <Route path="/generation-requests/:requestId" element={<ProtectedRoute><GenerationRequestsPage /></ProtectedRoute>} />
      <Route path="/generation-requests" element={<Navigate to="/dashboard" replace />} />
      <Route path="/mypage" element={<ProtectedRoute><MyPage /></ProtectedRoute>} />
      <Route path="/admin" element={<ProtectedRoute admin><AdminPage /></ProtectedRoute>} />
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  )
}
