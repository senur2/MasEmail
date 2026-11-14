package fr.mas.email.mail;

import java.util.Objects;
import java.util.UUID;

/** An atomic pointer to a fully initialized set of user MiniWorlds. */
public record UserConfiguration(MailAccount account, UUID knowledgeId) {
    public UserConfiguration { Objects.requireNonNull(account); Objects.requireNonNull(knowledgeId); }
}
