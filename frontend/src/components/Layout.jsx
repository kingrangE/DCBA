import { useState } from 'react'
import { NavLink, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'

export default function Layout({ children }) {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const [loggingOut, setLoggingOut] = useState(false)
  const [logoutError, setLogoutError] = useState('')

  const handleLogout = async () => {
    if (loggingOut) return

    setLoggingOut(true)
    setLogoutError('')
    try {
      await logout()
      navigate('/login', { replace: true })
    } catch {
      setLogoutError('로그아웃 상태를 확인하지 못했어요. 다시 시도해 주세요.')
      setLoggingOut(false)
    }
  }

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">본문 바로가기</a>
      <header className="topbar">
        <div className="topbar-inner">
          <NavLink className="wordmark" to="/dashboard">DCBA</NavLink>
          <nav className="main-nav" aria-label="주요 메뉴">
            <NavLink to="/dashboard">문제 풀기</NavLink>
            <NavLink to="/mypage">계정 설정</NavLink>
            {user.admin === true && <NavLink to="/admin">관리자</NavLink>}
          </nav>
          <div className="account-menu">
            <span>{user.name} 님</span>
            <button className="text-button" type="button" disabled={loggingOut} onClick={handleLogout}>{loggingOut ? '로그아웃 중…' : '로그아웃'}</button>
          </div>
        </div>
      </header>
      {logoutError && <p className="logout-error notice notice--error" role="alert">{logoutError}</p>}
      {children}
    </div>
  )
}
