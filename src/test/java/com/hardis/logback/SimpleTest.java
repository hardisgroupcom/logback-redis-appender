package com.hardis.logback;

import static org.junit.Assert.fail;

import java.io.IOException;

import javax.xml.parsers.ParserConfigurationException;

import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.xml.sax.SAXException;

public class SimpleTest {
	Logger log = LoggerFactory.getLogger(SimpleTest.class);

	@Test
	public void test() {
		  MDC.put("first", "Dorothy");
		  MDC.put("second", "McBeth");
		for (int i = 0; i < 1; i++) {
			log.error("coucou" + i);
		}
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		fail("Not yet implemented");
	}

}
