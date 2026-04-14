package com.example.epub.core.service.identity;
import com.example.epub.core.integration.mail.MailGateway;
import com.example.epub.core.repository.identity.EmailTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import org.springframework.mail.MailException;
/** Called within the owning service transaction: token writes and delivery roll back together. */
public class EmailTokenService {
    private final EmailTokenRepository tokens;
    private final MailGateway mail;
    private final SecureRandom random=new SecureRandom();
    public EmailTokenService(EmailTokenRepository tokens,MailGateway mail) { this.tokens=tokens;this.mail=mail; }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public void send(UUID user,String email,String purpose,String path,Duration ttl) {
        byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.replace(user,hash(token),purpose,ttl);
        try { mail.sendToken(email,purpose,path,token); } catch(MailException unavailable) { throw new Registration.MailUnavailable(); }
    }
    private static void validate(String token) { if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))throw new Registration.InvalidToken(); }
    public UUID consume(String token,String purpose) { validate(token);return tokens.consume(hash(token),purpose).orElseThrow(Registration.InvalidToken::new); }
    public UUID userFor(String token,String purpose) { validate(token);return tokens.userFor(hash(token),purpose).orElseThrow(Registration.InvalidToken::new); }
}
