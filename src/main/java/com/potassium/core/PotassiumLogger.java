package com.potassium.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PotassiumLogger {
	public static final String MOD_ID = "potassium";
	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private PotassiumLogger() {
	}

	public static Logger logger() {
		return LOGGER;
	}
}
