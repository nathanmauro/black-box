import { render } from "solid-js/web";
import { Navigate, Route, Router } from "@solidjs/router";
import App from "./App";
import ActivityPage from "./pages/ActivityPage";
import BoardPage from "./pages/BoardPage";
import GraphPage from "./pages/GraphPage";
import OverviewPage from "./pages/OverviewPage";
import ProjectsPage from "./pages/ProjectsPage";
import RecallPage from "./pages/RecallPage";
import SessionsPage from "./pages/SessionsPage";
import StatsPage from "./pages/StatsPage";
import "./theme.css";

const root = document.getElementById("root");
const BoardRoute = () => <BoardPage />;

if (!root) {
  throw new Error("Missing #root mount point");
}

render(
  () => (
    <Router root={App}>
      <Route path="/" component={ActivityPage} />
      <Route path="/board" component={BoardRoute} />
      <Route path="/overview" component={OverviewPage} />
      <Route path="/sessions" component={SessionsPage} />
      <Route path="/sessions/:sessionId" component={SessionsPage} />
      <Route path="/search" component={() => <Navigate href={({ location }) => `/${location.search}`} />} />
      <Route path="/recall" component={RecallPage} />
      <Route path="/projects" component={ProjectsPage} />
      <Route path="/projects/:projectKey" component={ProjectsPage} />
      <Route path="/stats" component={StatsPage} />
      <Route path="/graph" component={GraphPage} />
    </Router>
  ),
  root,
);
