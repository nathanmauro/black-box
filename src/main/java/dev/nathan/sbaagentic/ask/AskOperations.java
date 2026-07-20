package dev.nathan.sbaagentic.ask;

public interface AskOperations {

    AskStatus status();

    AskRetrieveResponse retrieve(String query, int limit);

    AskResponse ask(AskRequest request);
}
