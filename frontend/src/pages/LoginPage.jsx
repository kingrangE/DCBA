import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import AuthShell from '../components/AuthShell'
import { useAuth } from '../context/AuthContext'

export default function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [form, setForm] = useState({ name: '', password: '' })
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const submit = async (event) => {
    event.preventDefault()
    setSubmitting(true)
    setError('')
    try {
      await login(form)
      navigate('/dashboard')
    } catch (requestError) {
      setError(requestError.message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AuthShell
      eyebrow="WELCOME BACK"
      title="다시 시작해 볼까요?"
      description="오늘의 CS 문제를 만나려면 로그인하세요."
      footer={<>아직 계정이 없나요? <Link to="/signup">회원가입</Link></>}
    >
      <form className="stack-form" onSubmit={submit}>
        <label>이름<input autoComplete="username" maxLength="10" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="이름을 입력하세요" /></label>
        <label>비밀번호<input type="password" autoComplete="current-password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} placeholder="비밀번호를 입력하세요" /></label>
        {error && <p className="form-status form-status--error" role="alert">{error}</p>}
        <button className="button button--primary button--wide" disabled={submitting} type="submit">{submitting ? '로그인 중...' : '로그인 →'}</button>
      </form>
    </AuthShell>
  )
}
