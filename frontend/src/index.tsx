import { render } from "solid-js/web";
import { Route, Router } from "@solidjs/router";
import App from "./App";
import OverviewPage from "./pages/OverviewPage";
import ProjectsPage from "./pages/ProjectsPage";
import RecallPage from "./pages/RecallPage";
import SessionsPage from "./pages/SessionsPage";
import SearchPage from "./pages/SearchPage";
import "./theme.css";

const root = document.getElementById("root");

if (!root) {
  throw new Error("Missing #root mount point");
}

render(
  () => (
    <Router root={App}>
      <Route path="/" component={OverviewPage} />
      <Route path="/sessions" component={SessionsPage} />
      <Route path="/sessions/:sessionId" component={SessionsPage} />
      <Route path="/search" component={SearchPage} />
      <Route path="/recall" component={RecallPage} />
      <Route path="/projects" component={ProjectsPage} />
      <Route path="/projects/:projectKey" component={ProjectsPage} />
    </Router>
  ),
  root,
);
