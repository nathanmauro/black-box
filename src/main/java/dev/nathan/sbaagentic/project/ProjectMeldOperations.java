package dev.nathan.sbaagentic.project;

public interface ProjectMeldOperations {

    ProjectMeldPreviewResponse preview(String projectKey, ProjectMeldPreviewRequest request);

    ProjectSavedMeld save(ProjectMeldSaveRequest request);
}
