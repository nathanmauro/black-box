import { render } from "solid-js/web";
import { Navigate, Route, Router } from "@solidjs/router";
import App from "./App";
import ActivityPage from "./pages/ActivityPage";
import BoardPage from "./pages/BoardPage";
import GraphPage from "./pages/GraphPage";
import ProjectsPage from "./pages/ProjectsPage";
import RecallPage from "./pages/RecallPage";
import SessionsPage from "./pages/SessionsPage";
import "./theme.css";

const root = document.getElementById("root");
const BoardRoute = () => <BoardPage />;
const HomeRoute = () => <ActivityPage />;
// /stream is the promoted deep-link route (spec §6.6): the same workspace shell, locked to stream
// mode. `/` keeps rendering the stream directly and `?view=stream` links keep working.
const StreamRoute = () => <ActivityPage lockedMode="stream" />;

if (!root) {
  throw new Error("Missing #root mount point");
}

render(
  () => (
    <Router root={App}>
      <Route path="/" component={HomeRoute} />
      <Route path="/stream" component={StreamRoute} />
      <Route path="/board" component={BoardRoute} />
      <Route path="/sessions" component={SessionsPage} />
      <Route path="/sessions/:sessionId" component={SessionsPage} />
      <Route path="/search" component={() => <Navigate href={({ location }) => `/${location.search}`} />} />
      <Route path="/recall" component={RecallPage} />
      <Route path="/projects" component={ProjectsPage} />
      <Route path="/projects/:projectKey" component={ProjectsPage} />
      <Route path="/graph" component={GraphPage} />
    </Router>
  ),
  root,
);
