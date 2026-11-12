package edu.gatech.sip6321.opportunity;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
public class OpportunityConfiguration {
    @Bean
    Clock opportunityClock() {
        return Clock.systemUTC();
    }
}
