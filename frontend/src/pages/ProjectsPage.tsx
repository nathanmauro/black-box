import { A } from "@solidjs/router";

export default function ProjectsPage() {
  return (
    <section class="page parked-page" aria-labelledby="projects-parked-title">
      <div class="parked-panel">
        <p class="eyebrow">back burner</p>
        <h1 id="projects-parked-title">Projects are parked</h1>
        <p>
          Project storylines and melds are disabled while Black Box focuses on the core
          reading loop: finding sessions, scanning user prompts, and recalling durable
          decisions when needed.
        </p>
        <div class="parked-actions">
          <A href="/sessions">Open Sessions</A>
          <A href="/search">Open Search</A>
        </div>
      </div>
    </section>
  );
}
