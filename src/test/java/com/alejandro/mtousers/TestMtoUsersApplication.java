package com.alejandro.mtousers;

import org.springframework.boot.SpringApplication;

public class TestMtoUsersApplication {

    public static void main(String[] args) {
        SpringApplication.from(MtoUsersApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
