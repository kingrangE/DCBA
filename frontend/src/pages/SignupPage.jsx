import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import AuthShell from '../components/AuthShell'
import { useAuth } from '../context/AuthContext'

export default function SignupPage() {
  const { signup } = useAuth()
  const navigate = useNavigate()
  const [form, setForm] = useState({ name: '', password: '', confirm: '' })
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const submit = async (event) => {
    event.preventDefault()
    if (form.password !== form.confirm) {
      setError('비밀번호가 일치하지 않습니다.')
      return
    }
    setSubmitting(true)
    setError('')
    try {
      await signup({ name: form.name, password: form.password })
      navigate('/login', { replace: true })
    } catch (requestError) {
      setError(requestError.message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AuthShell
      eyebrow="JOIN DCBA"
      title="학습 루틴을 만드세요."
      description="10자 이내의 이름과 안전한 비밀번호로 시작하세요."
      footer={<>이미 계정이 있나요? <Link to="/login">로그인</Link></>}
    >
      <form className="stack-form" onSubmit={submit}>
        <label>이름<input autoComplete="username" maxLength="10" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="사용할 이름" /></label>
        <label>비밀번호<input type="password" autoComplete="new-password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} placeholder="비밀번호" /></label>
        <label>비밀번호 확인<input type="password" autoComplete="new-password" value={form.confirm} onChange={(e) => setForm({ ...form, confirm: e.target.value })} placeholder="비밀번호 다시 입력" /></label>
        {error && <p className="form-status form-status--error" role="alert">{error}</p>}
        <button className="button button--primary button--wide" disabled={submitting} type="submit">{submitting ? '가입 중...' : '회원가입 →'}</button>
      </form>
    </AuthShell>
  )
}
