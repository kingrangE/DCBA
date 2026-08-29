import { Link } from 'react-router-dom'

export default function AuthShell({ eyebrow, title, description, children, footer }) {
  return (
    <main className="auth-page">
      <section className="auth-brand">
        <Link className="wordmark wordmark--light" to="/">DCBA</Link>
        <div>
          <p className="eyebrow eyebrow--light">DAILY CS, BASE TO ADVANCED</p>
          <h1>오늘의 한 문제로<br />단단해지는 개발 기본기.</h1>
          <p>컴퓨터 구조부터 알고리즘까지, 매일 새롭게 생성되는 문제로 CS 감각을 이어가세요.</p>
        </div>
        <span className="auth-index">01—06 · LEARN EVERY DAY</span>
      </section>
      <section className="auth-panel">
        <div className="auth-card">
          <p className="eyebrow">{eyebrow}</p>
          <h2>{title}</h2>
          <p className="muted">{description}</p>
          {children}
          <p className="auth-footer">{footer}</p>
        </div>
      </section>
    </main>
  )
}
