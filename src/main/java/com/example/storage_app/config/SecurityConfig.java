package com.example.storage_app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            AuthenticationProvider anyUserProvider) throws Exception {
        http
                .csrf(csrf -> csrf.disable())

                // Allow all requests through; controllers will inspect Authentication if they need it
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )

                // Enable Basic Auth so Spring will populate 'Authentication' if credentials are sent
                .httpBasic(Customizer.withDefaults())

                .authenticationProvider(anyUserProvider);

        return http.build();
    }

    @Bean
    AuthenticationProvider anyUserProvider() {
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication auth) {
                // accept any username/password if provided
                return new UsernamePasswordAuthenticationToken(
                        auth.getName(),
                        auth.getCredentials(),
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                );
            }
            @Override
            public boolean supports(Class<?> clz) {
                return UsernamePasswordAuthenticationToken.class.isAssignableFrom(clz);
            }
        };
    }
}
