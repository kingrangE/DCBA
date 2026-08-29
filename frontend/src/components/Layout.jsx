import { NavLink, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'

export default function Layout({ children }) {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  const handleLogout = async () => {
    await logout()
    navigate('/login')
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <NavLink className="wordmark" to="/dashboard">DCBA</NavLink>
        <nav className="main-nav" aria-label="주요 메뉴">
          <NavLink to="/dashboard">문제 풀기</NavLink>
          <NavLink to="/mypage">마이페이지</NavLink>
        </nav>
        <div className="account-menu">
          <span><strong>{user.name}</strong> 님</span>
          <button className="text-button" type="button" onClick={handleLogout}>로그아웃</button>
        </div>
      </header>
      {children}
    </div>
  )
}
