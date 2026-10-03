package dev.nathan.sbaagentic.project;

public interface ProjectMeldOperations {

    ProjectMeldPreviewResponse preview(String projectKey, ProjectMeldPreviewRequest request);

    ProjectSavedMeld save(ProjectMeldSaveRequest request);

    ProjectSavedMeld get(String id);

    ProjectMeldListResponse list(String kind, String scope, int limit, String before);
}
