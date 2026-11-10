package com.panchayat.backend.newbie;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on the timer for the Newbie room upkeep (expired rooms, absent hosts). */
@Configuration
@EnableScheduling
public class NewbieSchedulingConfig {
}
