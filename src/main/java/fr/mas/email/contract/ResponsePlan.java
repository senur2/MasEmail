        package fr.mas.email.contract;

        import com.fasterxml.jackson.annotation.JsonPropertyOrder;

        import java.util.List;
        import java.util.UUID;

        /** Writer payload; Java adds identity and the configured writing profile. */
        @JsonPropertyOrder({"language", "tone", "replyStructure", "responseIntent", "requiresClarification",
                "missingInformation", "factsToCommunicate", "agentId", "planId", "emailId"})
        public record ResponsePlan(String planId, String agentId, String emailId, Intent responseIntent,
                                   List<Fact> factsToCommunicate, List<String> missingInformation,
                                   boolean requiresClarification, List<String> replyStructure, String language, String tone) {
            public enum Intent { ANSWER_INFORMATION_REQUEST, PROVIDE_DOCUMENT, ACKNOWLEDGE_REQUEST, REQUEST_CLARIFICATION }

            public ResponsePlan {
                planId = Checks.text(planId, "planId"); agentId = Checks.text(agentId, "agentId");
                emailId = Checks.text(emailId, "emailId"); language = Checks.text(language, "language");
                tone = Checks.text(tone, "tone"); replyStructure = Checks.texts(replyStructure, "replyStructure");
                var content = new Content(responseIntent, factsToCommunicate, missingInformation, requiresClarification);
                factsToCommunicate = content.factsToCommunicate(); missingInformation = content.missingInformation();
            }

            @JsonPropertyOrder({"responseIntent", "requiresClarification", "missingInformation", "factsToCommunicate"})
            public record Content(Intent responseIntent, List<Fact> factsToCommunicate,
                                  List<String> missingInformation, boolean requiresClarification) {
                public Content {
                    Checks.required(responseIntent, "responseIntent");
                    factsToCommunicate = Checks.list(factsToCommunicate, "factsToCommunicate");
                    missingInformation = Checks.texts(missingInformation, "missingInformation");
            Checks.rule(requiresClarification == (responseIntent == Intent.REQUEST_CLARIFICATION),
                    "responseIntent and requiresClarification are inconsistent");
            Checks.rule(requiresClarification ? !missingInformation.isEmpty() : missingInformation.isEmpty(),
                    "missingInformation must be non-empty exactly when clarification is required");
                    Checks.rule(requiresClarification || responseIntent == Intent.ACKNOWLEDGE_REQUEST
                    || !factsToCommunicate.isEmpty(), "This responseIntent requires factsToCommunicate");
                }

                /** A new plan identity invalidates any draft tied to the previous plan. */
                public ResponsePlan enrich(String agentId, String emailId, List<String> replyStructure,
                                           String language, String tone) {
                    return new ResponsePlan(UUID.randomUUID().toString(), agentId, emailId, responseIntent,
                            factsToCommunicate, missingInformation, requiresClarification, replyStructure, language, tone);
                }
            }

            @JsonPropertyOrder({"sourceRef", "text"})
            public record Fact(String text, String sourceRef) {
                public Fact { text = Checks.text(text, "text"); sourceRef = Checks.text(sourceRef, "sourceRef"); }
            }
        }
