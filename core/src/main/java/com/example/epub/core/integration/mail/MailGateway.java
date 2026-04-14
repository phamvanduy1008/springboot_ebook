package com.example.epub.core.integration.mail;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
public class MailGateway {
    private final JavaMailSender mail;
    private final String origin,from;
    public MailGateway(JavaMailSender mail,String origin,String from) { this.mail=mail;this.origin=origin;this.from=from; }
    public void sendToken(String email,String purpose,String path,String token) {
        var message=new SimpleMailMessage();message.setFrom(from);message.setTo(email);
        message.setSubject("VERIFY_EMAIL".equals(purpose)?"Verify your email":"Reset your password");
        message.setText("Use this link to continue:\n"+origin+path+"?token="+token+"\n\nThis link expires and can be used only once. If you did not request this, ignore this message.");
        mail.send(message);
    }
}
