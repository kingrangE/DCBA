import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import AuthShell from '../components/AuthShell'
import { useAuth } from '../context/AuthContext'

export default function SignupPage() {
  const { signup } = useAuth()
  const navigate = useNavigate()
  const [form, setForm] = useState({ name: '', password: '', confirm: '' })
  const [error, setError] = useState(null)
  const [submitting, setSubmitting] = useState(false)
  const passwordError = error?.field === 'password'
  const confirmError = error?.field === 'confirm'

  const change = (event) => {
    const { name, value } = event.target
    setForm({ ...form, [name]: value })
    if (error?.field === name || (confirmError && name === 'password')) setError(null)
  }

  const submit = async (event) => {
    event.preventDefault()
    if (new TextEncoder().encode(form.password).length > 72) {
      setError({ field: 'password', text: '비밀번호를 72바이트 이하로 줄여 주세요.' })
      event.currentTarget.elements.namedItem('password').focus()
      return
    }
    if (form.password !== form.confirm) {
      setError({ field: 'confirm', text: '위에 입력한 비밀번호와 같게 입력해 주세요.' })
      event.currentTarget.elements.namedItem('confirm').focus()
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      await signup({ name: form.name, password: form.password })
      navigate('/login', { replace: true, state: { registeredName: form.name } })
    } catch (requestError) {
      setError({ text: requestError.status ? requestError.message : '가입이 완료됐는지 확인할 수 없어요. 먼저 로그인해 보세요.' })
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AuthShell
      title="회원가입"
      description="계정을 만들면 문제를 저장하고 다시 풀 수 있어요."
      footer={<>이미 계정이 있나요? <Link to="/login">로그인</Link></>}
    >
      <form className="stack-form" onSubmit={submit}>
        <div className="form-field">
          <label>닉네임<input name="name" required readOnly={submitting} autoComplete="username" autoCapitalize="none" aria-describedby="nickname-help" value={form.name} onChange={change} /></label>
          <small id="nickname-help" className="field-help">앞뒤 공백 없이 1~10자</small>
        </div>
        <div className="form-field">
          <label>비밀번호<input name="password" required readOnly={submitting} type="password" autoComplete="new-password" aria-invalid={passwordError} aria-describedby={passwordError ? 'password-help password-error' : 'password-help'} value={form.password} onChange={change} /></label>
          <small id="password-help" className="field-help">최대 72바이트 (영문 최대 72자, 한글 최대 24자)</small>
          {passwordError && <p id="password-error" className="form-status form-status--error" role="alert">{error.text}</p>}
        </div>
        <div className="form-field">
          <label>비밀번호 확인<input name="confirm" required readOnly={submitting} type="password" autoComplete="new-password" aria-invalid={confirmError} aria-describedby={confirmError ? 'confirm-error' : undefined} value={form.confirm} onChange={change} /></label>
          {confirmError && <p id="confirm-error" className="form-status form-status--error" role="alert">{error.text}</p>}
        </div>
        {error && !error.field && <p className="form-status form-status--error" role="alert">{error.text}</p>}
        <button className="button button--primary button--wide" disabled={submitting} type="submit">{submitting ? '가입 중…' : '회원가입'}</button>
      </form>
    </AuthShell>
  )
}
