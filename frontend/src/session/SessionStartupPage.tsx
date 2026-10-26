export function SessionStartupPage() {
  return (
    <section className="home-page session-startup">
      <p className="eyebrow">Sessions</p>
      <h1>Welcome to Media Compare</h1>
      <p className="page-intro">
        A Session keeps your catalog and previews together, separate from your original media.
      </p>
      <p className="startup-note">Session controls are coming in a later update.</p>
      <div className="home-actions">
        <article>
          <h2>New Session</h2>
          <p>Choose where to save a new catalog.</p>
          <button type="button" disabled aria-describedby="session-coming-soon">New Session</button>
        </article>
        <article>
          <h2>Open Existing Session</h2>
          <p>Continue with a Session you already have.</p>
          <button type="button" disabled aria-describedby="session-coming-soon">Open Existing Session</button>
        </article>
        <article>
          <h2>Recent Sessions</h2>
          <p>Previously opened Sessions will appear here when Session management is available.</p>
        </article>
      </div>
      <p className="startup-note" id="session-coming-soon">New and Open are not available yet.</p>
    </section>
  )
}
