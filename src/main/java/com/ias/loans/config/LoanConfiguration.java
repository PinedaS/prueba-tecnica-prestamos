package com.ias.loans.config;

import com.ias.loans.domain.LoanEvaluator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(LoanPolicyProperties.class)
public class LoanConfiguration {

    @Bean
    Clock clock(LoanPolicyProperties properties) {
        return Clock.system(properties.businessZone());
    }

    @Bean
    LoanEvaluator loanEvaluator(LoanPolicyProperties properties) {
        return new LoanEvaluator(properties.minCreditScore(), properties.maxIncomeMultiplier());
    }
}
