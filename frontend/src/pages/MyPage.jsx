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
      const updatedUser = await updateSlack(slackId)
      setSlackId(updatedUser.slackId || '')
      setStatus({ type: 'success', text: 'Slack 멤버 ID를 저장했어요.' })
    } catch (error) {
      setStatus({ type: 'error', text: error.status ? error.message : '저장 여부를 확인할 수 없어요. 연결을 확인한 뒤 다시 저장해 주세요.' })
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Layout>
      <main className="profile-page" id="main-content" tabIndex={-1}>
        <section className="profile-intro">
          <h1>계정 설정</h1>
          <dl>
            <div><dt>닉네임</dt><dd>{user.name}</dd></div>
            <div><dt>가입일</dt><dd>{user.createdAt ? new Date(user.createdAt).toLocaleDateString('ko-KR') : '정보 없음'}</dd></div>
          </dl>
        </section>
        <section className="profile-card">
          <h2>Slack 설정</h2>
          <p className="muted">{user.slackId ? '멤버 ID가 등록되어 있어요.' : '등록된 멤버 ID가 없어요.'}</p>
          <form className="stack-form" onSubmit={submit}>
            <div className="form-field">
              <label>Slack 멤버 ID<input name="slackId" required readOnly={submitting} autoCapitalize="none" spellCheck={false} aria-describedby="slack-help" value={slackId} onChange={(event) => {
                setSlackId(event.target.value)
                setStatus(null)
              }} placeholder="예: U012ABCDEF" /></label>
              <small id="slack-help" className="field-help">Slack 프로필의 더 보기 메뉴에서 ‘멤버 ID 복사’를 선택하세요.</small>
            </div>
            {status && <p className={`form-status form-status--${status.type}`} role={status.type === 'error' ? 'alert' : 'status'}>{status.text}</p>}
            <button className="button button--primary" disabled={submitting} type="submit">{submitting ? '저장 중…' : '멤버 ID 저장'}</button>
          </form>
        </section>
      </main>
    </Layout>
  )
}
