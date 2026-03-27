package com.example.epub.api.auth;

import java.net.URI;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name="app.google.enabled",havingValue="true")
public class GoogleConfiguration {
    @Bean @ConditionalOnMissingBean(ClientRegistrationRepository.class)
    ClientRegistrationRepository googleClient(Environment env) {
        String id=required(env,"app.google.client-id"),secret=required(env,"app.google.client-secret"),callback=required(env,"app.google.redirect-uri");
        URI uri;
        try {uri=URI.create(callback);}catch(IllegalArgumentException invalid){throw new IllegalStateException("GOOGLE_REDIRECT_URI is invalid");}
        boolean local=!env.acceptsProfiles(Profiles.of("prod")) && "http".equals(uri.getScheme())
                && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
        if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !("https".equals(uri.getScheme()) || local) || !"/api/v1/login/oauth2/code/google".equals(uri.getPath()))
            throw new IllegalStateException("GOOGLE_REDIRECT_URI must be an explicit permitted callback URI");
        return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(id).clientSecret(secret).redirectUri(callback).scope("openid","profile","email").build());
    }
    private String required(Environment env,String name) {
        String value=env.getProperty(name,"");
        if(value.isBlank())throw new IllegalStateException("Google login enabled but required configuration is missing");
        return value;
    }
}
