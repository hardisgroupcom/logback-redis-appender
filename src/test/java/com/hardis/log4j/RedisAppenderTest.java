/**
* This file is part of log4j2redis
*
* Copyright (c) 2012 by Pavlo Baron (pb at pbit dot org)
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*

* @author Pavlo Baron <pb at pbit dot org>
* @author Landro Silva
* @copyright 2012 Pavlo Baron */

package com.hardis.log4j;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedisAppenderTest {

	public static class LogThread extends Thread {
		private static final AtomicInteger count = new AtomicInteger();		
		Logger log = LoggerFactory.getLogger("LogThread" + count.incrementAndGet());
		public void run() {
			try {
				for (long i = 0; i < 10; i++) {
					log.trace("whatever " + i);
					Thread.sleep(110);
				}
			} catch (InterruptedException e) {}
		}
	}

	private static final Logger log = LoggerFactory.getLogger("LogMainThread");

	@Test
	public void test() throws Throwable {
		for (int i = 1; i <= 9; i++) {
			new RedisAppenderTest.LogThread().start();
		}

		for (long i = 0; i < 1000; i++) {
			log.debug("that's me " + i);
			Thread.sleep(1000);
		}
	}

}
