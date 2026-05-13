package com.sec.rag;

import com.sec.rag.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class Sec10KRagApplication {

    public static void main(String[] args) {
        System.out.println("RUNNING JVM = " + System.getProperty("java.version"));
        SpringApplication.run(Sec10KRagApplication.class, args);
    }
}
