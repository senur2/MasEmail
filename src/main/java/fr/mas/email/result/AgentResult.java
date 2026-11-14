package fr.mas.email.result;

import java.util.function.Function;

/** No failure or clarification can masquerade as a successful payload. */
public sealed interface AgentResult<T> permits Success, DiscussionPlan, ErrorPlan, CorrectionRequest {
    default <R> AgentResult<R> map(Function<? super T, ? extends R> mapper) {
        return switch (this) {
            case Success<T> success -> new Success<>(mapper.apply(success.value()), success.rawOutput());
            case DiscussionPlan<T> discussion -> discussion.retype();
            case ErrorPlan<T> error -> error.retype();
            case CorrectionRequest<T> correction -> correction.retype();
        };
    }
}
