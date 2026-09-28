package com.skillmasterai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * The API, the authorization server and blob storage in one process (§2.2).
 *
 * <p>{@code UserDetailsServiceAutoConfiguration} is excluded because it would create an
 * in-memory user with a generated password and print that password at startup. Nothing here
 * authenticates with a username and password: P0 uses a bearer token, and P1's login flow goes
 * through the authorization server. Leaving it on would mean shipping a working credential in
 * the logs of every boot.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class SkillMasterServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkillMasterServerApplication.class, args);
    }
}
