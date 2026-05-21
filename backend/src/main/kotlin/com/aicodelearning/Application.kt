package com.aicodelearning

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class AiCodeLearningApplication

fun main(args: Array<String>) {
    runApplication<AiCodeLearningApplication>(*args)
}
