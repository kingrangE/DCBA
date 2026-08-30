import { useState } from 'react'
import Layout from '../components/Layout'
import { useAuth } from '../context/AuthContext'

export default function MyPage() {
  const { user, updateSlack } = useAuth()
  const [slackId, setSlackId] = useState(user.slackId || '')
  const [status, setStatus] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  const submit = async (event) => {
    event.preventDefault()
    setSubmitting(true)
    setStatus(null)
    try {
      await updateSlack(slackId)
      setStatus({ type: 'success', text: 'Slack ID를 저장했습니다.' })
    } catch (error) {
      setStatus({ type: 'error', text: error.message })
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Layout>
      <main className="profile-page">
        <section className="profile-intro">
          <p className="eyebrow">MY PROFILE</p>
          <h1>{user.name}님의<br />학습 설정.</h1>
          <dl>
            <div><dt>가입일</dt><dd>{user.createdAt ? new Date(user.createdAt).toLocaleDateString('ko-KR') : '-'}</dd></div>
            <div><dt>Slack 연동</dt><dd>{user.slackId ? '연결됨' : '연결 전'}</dd></div>
          </dl>
        </section>
        <section className="profile-card">
          <p className="eyebrow">NOTIFICATION</p>
          <h2>Slack 알림 설정</h2>
          <p className="muted">문제 알림을 받을 Slack Member ID를 등록하세요.</p>
          <form className="stack-form" onSubmit={submit}>
            <label>Slack Member ID<input value={slackId} onChange={(e) => setSlackId(e.target.value)} placeholder="예: U012ABCDEF" /></label>
            {status && <p className={`form-status form-status--${status.type}`}>{status.text}</p>}
            <button className="button button--primary" disabled={submitting} type="submit">{submitting ? '저장 중...' : '설정 저장 →'}</button>
          </form>
        </section>
      </main>
    </Layout>
  )
}
