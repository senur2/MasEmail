package fr.mas.email.contract;

import java.util.List;

/** Parser payload: identities come from Java; extraction never directly requests user input. */
public record EmailAnalysis(String emailId, String threadId, String language, String intent,
                            List<String> topics, List<Entity> entities, List<String> requestedInformation,
                            List<RequestedAction> requestedActions, List<String> senderClaims,
                            List<String> uncertainties, List<Evidence> evidence) {
    public EmailAnalysis {
        emailId = Checks.text(emailId, "emailId"); threadId = Checks.text(threadId, "threadId");
        var content = new Content(language, intent, topics, entities, requestedInformation,
                requestedActions, senderClaims, uncertainties, evidence);
        topics = content.topics(); entities = content.entities();
        requestedInformation = content.requestedInformation(); requestedActions = content.requestedActions();
        senderClaims = content.senderClaims();
        uncertainties = content.uncertainties();
        evidence = content.evidence();
    }

    /** The only fields the Parser LLM may produce. */
    public record Content(String language, String intent, List<String> topics, List<Entity> entities,
                          List<String> requestedInformation, List<RequestedAction> requestedActions,
                          List<String> senderClaims, List<String> uncertainties, List<Evidence> evidence) {
        public Content {
            language = Checks.text(language, "language"); intent = Checks.text(intent, "intent");
            topics = Checks.texts(topics, "topics"); entities = Checks.list(entities, "entities");
            requestedInformation = Checks.texts(requestedInformation, "requestedInformation");
            requestedActions = Checks.list(requestedActions, "requestedActions");
            senderClaims = Checks.texts(senderClaims, "senderClaims");
            uncertainties = Checks.texts(uncertainties, "uncertainties"); evidence = Checks.list(evidence, "evidence");
        }

        public EmailAnalysis enrich(String emailId, String threadId) {
            return new EmailAnalysis(emailId, threadId, language, intent, topics, entities,
                    requestedInformation, requestedActions, senderClaims, uncertainties, evidence);
        }
    }

    public record Entity(String type, String value) {
        public Entity { type = Checks.text(type, "type"); value = Checks.text(value, "value"); }
    }

    public record RequestedAction(String action, String description, String deadline) {
        public RequestedAction {
            action = Checks.text(action, "action");
            if (!action.matches("[A-Z][A-Z0-9_]*")) {
                throw new IllegalArgumentException("action must use ASCII upper snake case");
            }
            description = Checks.text(description, "description");
        }
    }

    public record Evidence(String field, String text) {
        public Evidence { field = Checks.text(field, "field"); text = Checks.text(text, "text"); }
    }
}
