import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import AuthShell from '../components/AuthShell'
import { useAuth } from '../context/AuthContext'

export default function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const { state } = useLocation()
  const [form, setForm] = useState({ name: state?.registeredName ?? '', password: '' })
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const submit = async (event) => {
    event.preventDefault()
    setSubmitting(true)
    setError('')
    try {
      await login(form)
      navigate('/dashboard', { replace: true })
    } catch (requestError) {
      if (requestError.status === 401) setError('닉네임과 비밀번호를 확인해 주세요.')
      else setError(requestError.status ? requestError.message : '로그인 요청에 실패했어요. 연결을 확인한 뒤 다시 시도해 주세요.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AuthShell
      title="로그인"
      footer={<>아직 계정이 없나요? <Link to="/signup">회원가입</Link></>}
    >
      {state?.registeredName && <p className="form-status form-status--success" role="status">회원가입이 완료됐어요. 비밀번호를 입력해 로그인하세요.</p>}
      <form className="stack-form" onSubmit={submit}>
        <label>닉네임<input name="name" required readOnly={submitting} autoComplete="username" autoCapitalize="none" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
        <label>비밀번호<input name="password" required readOnly={submitting} type="password" autoComplete="current-password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} /></label>
        {error && <p className="form-status form-status--error" role="alert">{error}</p>}
        <button className="button button--primary button--wide" disabled={submitting} type="submit">{submitting ? '로그인 중…' : '로그인'}</button>
      </form>
    </AuthShell>
  )
}
