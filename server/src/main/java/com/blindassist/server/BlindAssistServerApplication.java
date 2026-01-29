package com.blindassist.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BlindAssistServerApplication {

    public static void main(String[] args) {
        // 设置 UTF-8 编码（解决控制台中文乱码问题）
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.jnu.encoding", "UTF-8");
        System.setProperty("console.encoding", "UTF-8");

        SpringApplication.run(BlindAssistServerApplication.class, args);
    }
}


