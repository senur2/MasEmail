package fr.mas.email.mail;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.AddressException;
import java.util.Locale;

/** Public account settings. Credentials are never serialized. */
public record MailAccount(String email, String displayName) {
    public MailAccount {
        email = address(email).toLowerCase(Locale.ROOT);
        displayName = header(displayName == null ? "" : displayName.strip());
    }

    public static String address(String value) {
        try {
            var parsed = new InternetAddress(header(value).strip(), true);
            parsed.validate();
            if (parsed.isGroup() || parsed.getPersonal() != null || !parsed.getAddress().contains("@")) {
                throw new IllegalArgumentException("Indiquez une seule adresse email, sans nom ni liste.");
            }
            return parsed.getAddress();
        } catch (AddressException e) {
            throw new IllegalArgumentException("Adresse email invalide.");
        }
    }

    static String header(String value) {
        if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\0') >= 0) throw new IllegalArgumentException("En-tête email invalide.");
        return value;
    }
}
