package fr.mas.email.mail;

import jakarta.mail.*;
import jakarta.mail.internet.ContentType;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Bounded text extraction. Attachments, nested emails and remote HTML resources are not opened. */
final class MimeText {
    static final int MAX_BYTES = 1_000_000;
    private int remaining = MAX_BYTES;
    private int parts;

    String read(Part part) throws MessagingException, IOException { return read(part, 0); }

    private String read(Part part, int depth) throws MessagingException, IOException {
        if (++parts > 100 || depth > 12) throw new IOException("Email MIME trop complexe.");
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null) return "";
        if (part.isMimeType("text/plain") || part.isMimeType("text/html")) {
            if (part.getSize() > remaining) throw new IOException("Email trop volumineux (limite 1 Mo).");
            byte[] bytes;
            try (var input = part.getInputStream()) { bytes = input.readNBytes(remaining + 1); }
            remaining -= bytes.length;
            if (remaining < 0) throw new IOException("Email trop volumineux (limite 1 Mo).");
            String encoding = new ContentType(part.getContentType()).getParameter("charset");
            Charset charset;
            try { charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding); }
            catch (IllegalArgumentException e) { throw new IOException("Encodage email non pris en charge."); }
            String text = new String(bytes, charset);
            return part.isMimeType("text/html") ? htmlText(text) : text;
        }
        if (part.isMimeType("multipart/*")) {
            var multipart = (Multipart) part.getContent();
            if (multipart.getCount() > 100) throw new IOException("Email MIME trop complexe.");
            if (part.isMimeType("multipart/alternative")) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    var child = multipart.getBodyPart(i);
                    if (child.isMimeType("text/plain")) {
                        String text = read(child, depth + 1);
                        if (!text.isBlank()) return text;
                    }
                }
                for (int i = multipart.getCount() - 1; i >= 0; i--) {
                    String text = read(multipart.getBodyPart(i), depth + 1);
                    if (!text.isBlank()) return text;
                }
                return "";
            }
            var text = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                String value = read(multipart.getBodyPart(i), depth + 1);
                if (!value.isBlank()) text.append(value).append('\n');
            }
            return text.toString().strip();
        }
        return "";
    }

    private static String htmlText(String html) throws IOException {
        var result = new StringBuilder();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            private boolean hidden;
            @Override public void handleStartTag(HTML.Tag tag, javax.swing.text.MutableAttributeSet attrs, int pos) {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE || tag == HTML.Tag.HEAD) hidden = true;
                if (tag.isBlock()) result.append('\n');
            }
            @Override public void handleEndTag(HTML.Tag tag, int pos) {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE || tag == HTML.Tag.HEAD) hidden = false;
                if (tag.isBlock()) result.append('\n');
            }
            @Override public void handleSimpleTag(HTML.Tag tag, javax.swing.text.MutableAttributeSet attrs, int pos) {
                if (tag == HTML.Tag.BR) result.append('\n');
            }
            @Override public void handleText(char[] data, int pos) { if (!hidden) result.append(data); }
        }, true);
        return result.toString().strip();
    }
}
