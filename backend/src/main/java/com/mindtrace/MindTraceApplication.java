package com.mindtrace;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.mindtrace.mapper")
public class MindTraceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MindTraceApplication.class, args);
    }
}

