package com.example.epub.infrastructure.identity;

import com.example.epub.application.identity.Registration;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;

/** Raw tokens exist only in memory and in the intended recipient's mail, never in database rows. */
public class EmailTokens {
    private final JdbcTemplate jdbc;
    private final JavaMailSender mail;
    private final String origin,from;
    private final SecureRandom random=new SecureRandom();
    public EmailTokens(JdbcTemplate jdbc,JavaMailSender mail,String origin,String from) {this.jdbc=jdbc;this.mail=mail;this.origin=origin;this.from=from;}
    static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    public void send(UUID user,String email,String purpose,String path,Duration ttl) {
        byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("UPDATE auth_tokens SET consumed_at=now() WHERE user_id=? AND purpose=? AND consumed_at IS NULL",user,purpose);
        jdbc.update("INSERT INTO auth_tokens(user_id,token_hash,purpose,expires_at) VALUES (?,?,?,now()+(? * interval '1 second'))",user,hash(token),purpose,ttl.toSeconds());
        var message=new SimpleMailMessage();message.setFrom(from);message.setTo(email);
        message.setSubject("VERIFY_EMAIL".equals(purpose)?"Verify your email":"Reset your password");
        message.setText("Use this link to continue:\n"+origin+path+"?token="+token+"\n\nThis link expires and can be used only once. If you did not request this, ignore this message.");
        try {mail.send(message);}catch(MailException unavailable) {throw new Registration.MailUnavailable();}
    }
    public UUID consume(String token,String purpose) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))throw new Registration.InvalidToken();
        var ids=jdbc.queryForList("""
                UPDATE auth_tokens SET consumed_at=clock_timestamp()
                WHERE token_hash=? AND purpose=? AND consumed_at IS NULL AND expires_at>clock_timestamp()
                RETURNING user_id
                """,UUID.class,hash(token),purpose);
        if(ids.isEmpty())throw new Registration.InvalidToken();
        return ids.getFirst();
    }
    public UUID userFor(String token,String purpose) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))throw new Registration.InvalidToken();
        var ids=jdbc.queryForList("SELECT user_id FROM auth_tokens WHERE token_hash=? AND purpose=? AND consumed_at IS NULL AND expires_at>clock_timestamp()",UUID.class,hash(token),purpose);
        if(ids.isEmpty())throw new Registration.InvalidToken();return ids.getFirst();
    }
}
