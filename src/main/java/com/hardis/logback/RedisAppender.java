/**
* Based on code by Pavlo Baron, Landro Silva & Ryan Tenney
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
**/

package com.hardis.logback;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.util.SafeEncoder;



public class RedisAppender extends UnsynchronizedAppenderBase<ILoggingEvent> implements Runnable, RedisAppenderMBean {

	final static Logger logger = LoggerFactory.getLogger(RedisAppender.class);
	
    // configs
    private String host = "localhost";    
    private int port = 6379;
    private String password;
    private String key;
    private int queueSize = 5000;
    private int batchSize = 100;
    private long flushInterval = 500;
    private boolean alwaysBatch = true;
    private boolean purgeOnFailure = true;
    private long waitTerminate = 1000;
    private boolean registerMBean = true;

    // runtime stuff
    private Queue<ILoggingEvent> events;
    private int messageIndex = 0;
    private byte[][] batch;
    private Jedis jedis;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> task;

    // metrics
    private int eventCounter = 0;
    private int eventsDroppedInQueueing = 0;
    private int eventsDroppedInPush = 0;
    private int connectCounter = 0;
    private int connectFailures = 0;
    private int batchPurges = 0;
    private int eventsPushed = 0;

	// keep this for config compatibility for now
	JSONEventLayout jsonlayout;    
	
	Layout<ILoggingEvent> layout;
    
	public RedisAppender() {
		jsonlayout = new JSONEventLayout();
	}	
	
	@Override
	public void start() {
		super.start();
		this.activateOptions();
	}

	@Override
	public void stop() {
		super.stop();
        try {
            task.cancel(false);
            executor.shutdown();

            boolean finished = executor.awaitTermination(waitTerminate, TimeUnit.MILLISECONDS);
            if (finished) {
                // We finished successfully, process any events in the queue if any still remain.
                if (!events.isEmpty())
                    run();
                // Flush any remainder regardless of the alwaysBatch flag.
                if (messageIndex > 0)
                    push();
            } else {
            	logger.warn("Executor did not complete in " + waitTerminate + " milliseconds. Log entries may be lost.");
            }

            safeDisconnect();
        } catch (Exception e) {
        	logger.error(e.getMessage(), e);
        }
	}	
	
	@Override
	protected void append(ILoggingEvent event) {
		
        try {
            eventCounter++ ;
            int size = events.size();
            if (size < queueSize) {
                populateEvent(event);
                try {
                    events.add(event);
                } catch (IllegalStateException e) {
                    // safeguard in case multiple threads raced on almost full queue
                    eventsDroppedInQueueing++;
                }
            } else {
                eventsDroppedInQueueing++;
            }

        } catch (Exception e) {
            logger.error("Error populating event and adding to queue", e, event);
        }		
		
	}    


    public void activateOptions() {
        try {

            if (key == null) throw new IllegalStateException("Must set 'key'");
            if (!(flushInterval > 0)) throw new IllegalStateException("FlushInterval (ex. Period) must be > 0. Configured value: " + flushInterval);
            if (!(queueSize > 0)) throw new IllegalStateException("QueueSize must be > 0. Configured value: " + queueSize);
            if (!(batchSize > 0)) throw new IllegalStateException("BatchSize must be > 0. Configured value: " + batchSize);

            if (executor == null) executor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory(this.getClass().getSimpleName(), true));

            if (task != null && !task.isDone()) task.cancel(true);

            events = new ArrayBlockingQueue<ILoggingEvent>(queueSize);
            batch = new byte[batchSize][];
            messageIndex = 0;

            createJedis();

            if (registerMBean) {
                registerMBean();
            }

            task = executor.scheduleWithFixedDelay(this, flushInterval, flushInterval, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            logger.error("Error during activateOptions", e);
        }
    }

    protected void createJedis() {
        if (jedis != null && jedis.isConnected()) {
            jedis.disconnect();
        }
        jedis = new Jedis(host, port);
    }


    protected void populateEvent(ILoggingEvent event) {
        event.getThreadName();
        event.getMessage();
        event.getMDCPropertyMap();
    }


    /**
     * Pre: jedis not null
     */
    protected void safeDisconnect() {
        try {
            jedis.disconnect();
        } catch (Exception e) {
        	logger.warn("Disconnect failed to Redis at " + getRedisAddress());
        }
    }

    protected boolean connect() {
        try {
            if (!jedis.isConnected()) {
            	logger.debug("Connecting to Redis at " + getRedisAddress());
                connectCounter++;
                jedis.connect();

                if (password != null) {
                    String result = jedis.auth(password);
                    if (!"OK".equals(result)) {
                    	logger.error("Error authenticating with Redis at " + getRedisAddress());
                    }
                }

                // make sure we got a live connection
                jedis.ping();
            }
            return true;
        } catch (Exception e) {
            connectFailures++;
            // TODO: LogLog.error("Error connecting to Redis at " + getRedisAddress() + ": " + e.getMessage());
            return false;
        }
    }

    public void run() {

        if (!connect()) {
            return;
        }

        try {
            if (messageIndex == batchSize) push();

            ILoggingEvent event;
            while ((event = events.poll()) != null) {
                try {
                	String message = layout == null ? jsonlayout.doLayout(event) : layout.doLayout(event);
                    batch[messageIndex++] = SafeEncoder.encode(message);
                } catch (Exception e) {
                	logger.error(e.getMessage(), e, event);
                }

                if (messageIndex == batchSize) push();
            }

            if (!alwaysBatch && messageIndex > 0) {
                // push incomplete batches
                push();
            }

        } catch (JedisException je) {

        	logger.debug("Can't push " + messageIndex + " events to Redis. Reconnecting for retry.", je);
            safeDisconnect();

        } catch (Exception e) {
        	logger.error("Can't push events to Redis", e);
        }
    }

    protected boolean push() {
    	logger.debug("Sending " + messageIndex + " log messages to Redis at " + getRedisAddress());
        try {

            jedis.rpush(SafeEncoder.encode(key),
                batchSize == messageIndex
                    ? batch
                    : Arrays.copyOf(batch, messageIndex));

            eventsPushed += messageIndex;
            messageIndex = 0;
            return true;

        } catch (JedisDataException jde) {
            // Handling stuff like OOM's on Redis' side
            if (purgeOnFailure) {
            	logger.error("Can't push events to Redis at " + getRedisAddress() + ": " + jde.getMessage());
                eventsDroppedInPush += messageIndex;
                batchPurges++;
                messageIndex = 0;
            }
            return false;
        }
    }

    public void setHost(String host) {
        this.host = host;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getRedisAddress() {
        return host + ":" + port;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setFlushInterval(long millis) {
        this.flushInterval = millis;
    }

    // deprecated, use flushInterval instead
    public void setPeriod(long millis) {
        setFlushInterval(millis);
    }

    public void setKey(String key) {
        this.key = key;
    }

    public void setQueueSize(int queueSize) {
        this.queueSize = queueSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public void setPurgeOnFailure(boolean purgeOnFailure) {
        this.purgeOnFailure = purgeOnFailure;
    }

    public void setAlwaysBatch(boolean alwaysBatch) {
        this.alwaysBatch = alwaysBatch;
    }

    public void setRegisterMBean(boolean registerMBean) {
        this.registerMBean = registerMBean;
    }

    public void setWaitTerminate(long waitTerminate) {
        this.waitTerminate = waitTerminate;
    }

    public boolean requiresLayout() {
        return true;
    }
    
	public Layout<ILoggingEvent> getLayout() {
		return layout;
	}

	public void setLayout(Layout<ILoggingEvent> layout) {
		this.layout = layout;
	}    

    public int getEventCounter() { return eventCounter; }
    public int getEventsDroppedInQueueing() { return eventsDroppedInQueueing; }
    public int getEventsDroppedInPush() { return eventsDroppedInPush; }
    public int getConnectCounter() { return connectCounter; }
    public int getConnectFailures() { return connectFailures; }
    public int getBatchPurges() { return batchPurges; }
    public int getEventsPushed() { return eventsPushed; }
    public int getEventQueueSize() { return events.size(); }

    private void registerMBean() {
        Class me = this.getClass();
        String name = me.getPackage().getName() + ":type=" + me.getSimpleName();

        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName obj_name = new ObjectName(name);
            RedisAppenderMBean mbean = this;
            mbs.registerMBean(mbean, obj_name);
        } catch (Exception e) {
        	logger.error("Unable to register mbean", e);
        }

        logger.warn("INFO: Registered MBean " + name);

    }

}
