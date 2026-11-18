export default function AuthShell({ title, description, children, footer }) {
  return (
    <main className="auth-page">
      <section className="auth-panel">
        <div className="auth-card">
          <span className="wordmark">DCBA</span>
          <h1>{title}</h1>
          {description && <p className="muted">{description}</p>}
          {children}
          <p className="auth-footer">{footer}</p>
        </div>
      </section>
      <div className="auth-brand">
        <div>
          <h2>하루 한 문제로 익히는 CS 기본기</h2>
          <p>컴퓨터 구조부터 알고리즘까지, 분야와 난이도를 골라 연습하세요.</p>
        </div>
      </div>
    </main>
  )
}
