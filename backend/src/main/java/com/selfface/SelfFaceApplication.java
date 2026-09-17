package com.selfface;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.selfface.mapper")
public class SelfFaceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SelfFaceApplication.class, args);
    }
}
